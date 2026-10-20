package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 逆地理编码代理（A1 地址改造）。
 *
 * <p>为什么不让前端直接调地图服务商：
 * <ol>
 *   <li><b>Key 不外泄</b>：地图 Key 配在服务端环境变量里，页面与小程序包里都没有；</li>
 *   <li><b>免跨域</b>：浏览器直连高德会被 CORS 挡住；</li>
 *   <li><b>免小程序域名白名单</b>：小程序只能请求已备案并在后台配置过的域名，
 *       走我们自己的域名就不用为地图服务商单独申请；</li>
 *   <li><b>归一化只有一处</b>：高德对直辖市的 city 字段会返回空数组、
 *       对省直辖县返回「省直辖县级行政区划」，这些差异在后端抹平，两端拿到的结构一致。</li>
 * </ol>
 *
 * <p><b>未配置 Key 时不报错</b>：返回 {@code configured=false} 的空结果，前端据此
 * 提示「请手动选择」，而不是把填地址这件事卡死。用 503 会让前端难以区分
 * 「服务坏了」与「还没配」。
 *
 * <p>本接口不进网关白名单（需登录）：它按次计费，匿名开放等于把账单交给公网。
 */
@RestController
@RequestMapping("/api/geo")
public class GeoController {

    private static final Logger log = LoggerFactory.getLogger(GeoController.class);
    private static final String AMAP_REGEO = "https://restapi.amap.com/v3/geocode/regeo";

    private final ObjectMapper objectMapper;
    /** 高德「Web 服务」Key；为空表示未配置，接口走降级分支 */
    private final String amapKey;

    /**
     * 超时必须给短：用户在填地址，定位只是「省点打字」的辅助，
     * 不能因为地图服务商慢而把整个弹窗拖住。前端还有 8 秒的定位超时。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public GeoController(ObjectMapper objectMapper,
                         @Value("${duoduo.geo.amap-key:}") String amapKey) {
        this.objectMapper = objectMapper;
        this.amapKey = amapKey == null ? "" : amapKey.trim();
    }

    @GetMapping("/regeo")
    public ResponseEntity<ApiResponse<RegeoView>> regeo(@RequestParam BigDecimal lng,
                                                        @RequestParam BigDecimal lat) {
        if (amapKey.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.ok(RegeoView.notConfigured()));
        }
        if (lng.abs().compareTo(BigDecimal.valueOf(180)) > 0
                || lat.abs().compareTo(BigDecimal.valueOf(90)) > 0) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("BAD_COORDINATE", "经纬度超出取值范围"));
        }
        try {
            String location = URLEncoder.encode(lng.stripTrailingZeros().toPlainString()
                    + "," + lat.stripTrailingZeros().toPlainString(), StandardCharsets.UTF_8);
            String url = AMAP_REGEO + "?key=" + URLEncoder.encode(amapKey, StandardCharsets.UTF_8)
                    + "&location=" + location + "&extensions=base&batch=false";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("高德逆地理编码 HTTP {}，lng={} lat={}", response.statusCode(), lng, lat);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(ApiResponse.fail("GEO_UPSTREAM_ERROR", "地图服务暂时不可用，请手动选择"));
            }
            return ResponseEntity.ok(ApiResponse.ok(parse(response.body(), lng, lat)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.fail("GEO_UPSTREAM_ERROR", "定位服务被中断，请手动选择"));
        } catch (Exception e) {
            log.warn("高德逆地理编码失败：{}", e.toString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.fail("GEO_UPSTREAM_ERROR", "地图服务暂时不可用，请手动选择"));
        }
    }

    /**
     * 把高德返回体归一化成三端一致的形状。
     *
     * <p>两个已知差异必须在这里抹平：
     * <ul>
     *   <li>直辖市：{@code province=北京市}、{@code city} 可能是空数组 —— 此时用省名补市名；</li>
     *   <li>省直辖县级行政区：{@code city} 可能是「省直辖县级行政区划」，原样返回没有意义，
     *       同样用省名补上，保证「省/市/区」三段都能填进表单。</li>
     * </ul>
     */
    private RegeoView parse(String body, BigDecimal lng, BigDecimal lat) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (!"1".equals(root.path("status").asText())) {
            String info = root.path("info").asText("");
            log.warn("高德逆地理编码返回失败 status={} info={}", root.path("status").asText(), info);
            return new RegeoView(true, null, null, null, null, null, null, null);
        }
        JsonNode component = root.path("regeocode").path("addressComponent");
        String province = text(component.path("province"));
        String city = text(component.path("city"));
        String district = text(component.path("district"));
        String adcode = text(component.path("adcode"));

        if (province == null) {
            // 海外坐标等场景：高德只给 formatted_address，此时不猜省市区
            return new RegeoView(true, null, null, null,
                    text(root.path("regeocode").path("formatted_address")), null,
                    lng.doubleValue(), lat.doubleValue());
        }
        if (city == null || city.contains("省直辖")) {
            city = province;
        }
        if (district == null || district.contains("省直辖")) {
            district = "";
        }

        String detail = buildDetail(component);
        return new RegeoView(true, province, city, district.isEmpty() ? null : district,
                detail, adcode, lng.doubleValue(), lat.doubleValue());
    }

    /** 详细地址 = 街道 + 门牌；都缺时退回 formatted_address 去掉省市区前缀 */
    private String buildDetail(JsonNode component) {
        String street = text(component.path("streetNumber").path("street"));
        String number = text(component.path("streetNumber").path("number"));
        StringBuilder sb = new StringBuilder();
        if (street != null) {
            sb.append(street);
        }
        if (number != null) {
            sb.append(number);
        }
        String township = text(component.path("township"));
        if (sb.length() == 0 && township != null) {
            sb.append(township);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 高德在字段缺失时返回空数组 `[]`，asText() 会得到 ""，统一转成 null */
    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** 逆地理编码结果（与前端 api-sdk 的 RegeoView 一一对应） */
    public record RegeoView(
            boolean configured,
            String province,
            String city,
            String district,
            String detail,
            String adcode,
            Double lng,
            Double lat
    ) {
        static RegeoView notConfigured() {
            return new RegeoView(false, null, null, null, null, null, null, null);
        }
    }
}

package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.user.support.CoordTransform;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 逆地理编码代理（A1 地址改造）。
 *
 * <p>为什么不让前端直接调地图服务商：
 * <ol>
 *   <li><b>Key 不外泄</b>：地图 Key 配在服务端环境变量里，页面与小程序包里都没有；</li>
 *   <li><b>免跨域</b>：浏览器直连高德会被 CORS 挡住；</li>
 *   <li><b>免小程序域名白名单</b>：走我们自己的域名，不必为地图服务商单独申请；</li>
 *   <li><b>归一化只有一处</b>：坐标系、直辖市、省直辖县的差异都在这里抹平。</li>
 * </ol>
 *
 * <h2>坐标系（2026-10-20 修「偏差 50 米以上」）</h2>
 * <p>高德只认 <b>GCJ-02</b>；网页端 {@code navigator.geolocation} 给的是 <b>WGS-84</b>。
 * 实测把 WGS-84 直接当 GCJ-02 传，地址会落到 500 米开外（深圳花园坪 → 龙城公园、
 * 天安门 → 西长安街）。所以本接口要求调用方用 {@code coord} 声明坐标系，
 * {@code wgs84} 时先做转换。
 * <b>注意方向性</b>：小程序 {@code wx.getLocation({type:'gcj02'})} 已是 GCJ-02，
 * 必须传 {@code coord=gcj02}，否则会二次偏移。
 *
 * <h2>候选地址（2026-10-20 修「只能回填单一位置」）</h2>
 * <p>{@code extensions=all} 会带回周边 POI；这里取出最近若干条作为候选，
 * 让用户在「小区门 / 几号门 / 隔壁楼」之间自己挑，而不是被动接受一个可能错的门牌。
 * 候选复用手头这次响应，不额外消耗高德配额。
 */
@RestController
@RequestMapping("/api/geo")
public class GeoController {

    private static final Logger log = LoggerFactory.getLogger(GeoController.class);
    private static final String AMAP_REGEO = "https://restapi.amap.com/v3/geocode/regeo";
    /** 候选数量上限：太多反而挑不出来，8 条覆盖绝大多数「门口 / 楼栋」歧义 */
    private static final int MAX_CANDIDATES = 8;

    private final ObjectMapper objectMapper;
    /** 高德「Web 服务」Key；为空表示未配置，接口走降级分支 */
    private final String amapKey;

    /**
     * 超时必须给短：用户在填地址，定位只是「省点打字」的辅助，
     * 不能因为地图服务商慢而把整个弹窗拖住。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public GeoController(ObjectMapper objectMapper,
                         @Value("${duoduo.geo.amap-key:}") String amapKey) {
        this.objectMapper = objectMapper;
        this.amapKey = amapKey == null ? "" : amapKey.trim();
    }

    /**
     * 逆地理编码 + 周边候选。
     *
     * @param coord 坐标系：{@code gcj02}（默认，小程序或已转换过的坐标）
     *              或 {@code wgs84}（网页端浏览器原始坐标，需要转换）
     */
    @GetMapping("/regeo")
    public ResponseEntity<ApiResponse<RegeoView>> regeo(@RequestParam BigDecimal lng,
                                                        @RequestParam BigDecimal lat,
                                                        @RequestParam(defaultValue = "gcj02") String coord) {
        if (amapKey.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.ok(RegeoView.notConfigured()));
        }
        if (lng.abs().compareTo(BigDecimal.valueOf(180)) > 0
                || lat.abs().compareTo(BigDecimal.valueOf(90)) > 0) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("BAD_COORDINATE", "经纬度超出取值范围"));
        }
        String normalizedCoord = normalizeCoord(coord);
        if (normalizedCoord == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("BAD_COORDINATE", "coord 只支持 gcj02 / wgs84"));
        }

        double[] gcj = "wgs84".equals(normalizedCoord)
                ? CoordTransform.wgs84ToGcj02(lng.doubleValue(), lat.doubleValue())
                : new double[]{lng.doubleValue(), lat.doubleValue()};

        try {
            HttpResponse<String> response = httpClient.send(buildRequest(gcj[0], gcj[1]),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("高德逆地理编码 HTTP {}，gcj=({},{})", response.statusCode(), gcj[0], gcj[1]);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(ApiResponse.fail("GEO_UPSTREAM_ERROR", "地图服务暂时不可用，请手动选择"));
            }
            return ResponseEntity.ok(ApiResponse.ok(parse(response.body(), gcj[0], gcj[1], normalizedCoord)));
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

    private HttpRequest buildRequest(double gcjLng, double gcjLat) {
        String location = URLEncoder.encode(trim(gcjLng) + "," + trim(gcjLat), StandardCharsets.UTF_8);
        // extensions=all 才带 pois（周边候选）；base 只到道路/门牌
        String url = AMAP_REGEO + "?key=" + URLEncoder.encode(amapKey, StandardCharsets.UTF_8)
                + "&location=" + location + "&extensions=all&radius=500&batch=false";
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
    }

    /** 保留 6 位小数（约 0.1 米），避免科学计数法传给高德 */
    private String trim(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private String normalizeCoord(String coord) {
        if (coord == null) {
            return "gcj02";
        }
        String c = coord.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty()) {
            return "gcj02";
        }
        return ("gcj02".equals(c) || "wgs84".equals(c)) ? c : null;
    }

    /**
     * 把高德返回体归一化成三端一致的形状。
     *
     * <p>三个已知差异必须在这里抹平：
     * <ul>
     *   <li>直辖市：{@code province=北京市}、{@code city} 可能是空数组 —— 用省名补市名；</li>
     *   <li>省直辖县级行政区：{@code city} 是「省直辖县级行政区划」，同样用省名补；</li>
     *   <li>字段缺失：高德用空数组 {@code []} 表示无值，{@code asText()} 得到空串，统一转 null。</li>
     * </ul>
     */
    private RegeoView parse(String body, double gcjLng, double gcjLat, String coord) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (!"1".equals(root.path("status").asText())) {
            String info = root.path("info").asText("");
            log.warn("高德逆地理编码返回失败 status={} info={}", root.path("status").asText(), info);
            return new RegeoView(true, coord, null, null, null, null, null, null, null, List.of());
        }
        JsonNode regeocode = root.path("regeocode");
        JsonNode component = regeocode.path("addressComponent");
        String province = text(component.path("province"));
        String city = text(component.path("city"));
        String district = text(component.path("district"));
        String adcode = text(component.path("adcode"));
        String formatted = text(regeocode.path("formatted_address"));
        List<Candidate> candidates = parseCandidates(regeocode.path("pois"));

        if (province == null) {
            // 海外坐标等场景：高德只给 formatted_address，此时不猜省市区
            return new RegeoView(true, coord, null, null, null, formatted, null,
                    gcjLng, gcjLat, candidates);
        }
        if (city == null || city.contains("省直辖")) {
            city = province;
        }
        if (district == null || district.contains("省直辖")) {
            district = "";
        }
        return new RegeoView(true, coord, province, city, district.isEmpty() ? null : district,
                buildDetail(component, formatted), adcode, gcjLng, gcjLat, candidates);
    }

    /** 周边 POI → 候选列表；取前 N 条后**按距离升序**重排 */
    private List<Candidate> parseCandidates(JsonNode pois) {
        List<Candidate> out = new ArrayList<>();
        if (pois == null || !pois.isArray()) {
            return out;
        }
        for (JsonNode poi : pois) {
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
            String name = text(poi.path("name"));
            if (name == null) {
                continue;
            }
            String distanceRaw = text(poi.path("distance"));
            double[] loc = parseLocation(text(poi.path("location")));
            out.add(new Candidate(
                    name,
                    text(poi.path("address")),
                    distanceRaw == null ? null : safeDistance(distanceRaw),
                    loc == null ? null : loc[0],
                    loc == null ? null : loc[1]));
        }
        // 高德返回的是「相关性」顺序，不是距离顺序；用户要在附近挑位置，
        // 必须按距离升序才有意义（无距离的排在最后）。
        out.sort(java.util.Comparator.comparing(
                Candidate::distance, java.util.Comparator.nullsLast(Integer::compareTo)));
        return out;
    }

    private double[] parseLocation(String raw) {
        if (raw == null || !raw.contains(",")) {
            return null;
        }
        String[] parts = raw.split(",", 2);
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 高德的 distance 是**小数字符串**（如 "72.898"），不是整数。
     * 早前按 Integer 解析会抛 NumberFormatException 被吞成 null，表现为
     * 「候选都没有距离」—— 这里按 double 解析后四舍五入到米。
     */
    private Integer safeDistance(String raw) {
        try {
            return (int) Math.round(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 详细地址 = 街道 + 门牌；都缺时用 formatted_address 兜底 */
    private String buildDetail(JsonNode component, String formatted) {
        String street = text(component.path("streetNumber").path("street"));
        String number = text(component.path("streetNumber").path("number"));
        StringBuilder sb = new StringBuilder();
        if (street != null) {
            sb.append(street);
        }
        if (number != null) {
            sb.append(number);
        }
        if (sb.length() == 0) {
            String township = text(component.path("township"));
            if (township != null) {
                sb.append(township);
            }
        }
        return sb.length() == 0 ? formatted : sb.toString();
    }

    /** 高德在字段缺失时返回空数组 `[]`，asText() 会得到 ""，统一转成 null */
    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** 周边候选地址（让用户在多个位置里挑，而不是被动接受单一位置） */
    public record Candidate(String name, String address, Integer distance, Double lng, Double lat) {
    }

    /** 逆地理编码结果（与前端 api-sdk 的 RegeoView 一一对应） */
    public record RegeoView(
            boolean configured,
            String coord,
            String province,
            String city,
            String district,
            String detail,
            String adcode,
            Double lng,
            Double lat,
            List<Candidate> candidates
    ) {
        static RegeoView notConfigured() {
            return new RegeoView(false, "gcj02", null, null, null, null, null, null, null, List.of());
        }
    }
}

package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.web.storage.ObjectStorage;
import com.duoduo.fulfilment.support.RemoteImageImport;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片上传与读取（A15）：评价晒单图 / 售后举证图的「从无到有」链路。
 *
 * <p>此前全系统只存图片直链（见 {@link com.duoduo.fulfilment.support.ImageUrls}），
 * URL 由用户自己粘贴 —— 小程序里没有「粘贴直链」的合理体验，举证图基本交白卷。
 * 本端点补上「上传 → 落盘 → 返回可访问 URL」的最小闭环。
 *
 * <p><b>安全边界（三条都有真实攻击面）：</b>
 * <ul>
 *   <li><b>POST /api/uploads 需登录</b>：不进网关白名单。匿名上传等于把磁盘当公共网盘。</li>
 *   <li><b>GET /uploads/{file} 公开读</b>：{@code <image>} 标签发不出 Authorization 头，
 *       鉴权会让图全挂；防线放在「文件名是服务端生成的 UUID」——不可枚举、无业务语义，
 *       且与售后单 ID 无关，拿到 URL 也反查不出谁上传的。</li>
 *   <li><b>双前缀刻意不同</b>：网关白名单按路径匹配、<b>不区分 HTTP 方法</b>，
 *       若读写同用 /api/uploads，给 GET 开白名单会把匿名 POST 一起放行。
 *       读走无 /api 前缀的 /uploads/**，写走受保护的 /api/uploads，白名单只放 /uploads/**。</li>
 * </ul>
 *
 * <p><b>存储介质（S9）</b>：落盘位置改由 {@link ObjectStorage} 决定 ——
 * 默认是本地盘（行为与改造前逐字节一致），配置 {@code duoduo.storage.type=cos} 后
 * 落到腾讯云 COS。<b>本类的对外契约一个字都没变</b>：
 * 上传仍返回 {@code <base>/uploads/<uuid>.<ext>}，读取仍是同一个路径 ——
 * 存量图片 URL 一条都不会失效。这是接对象存储时最重要的一条约束。
 */
@RestController
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    /** 允许的扩展名 → Content-Type。白名单之外一律拒绝，不做「能解析就收」的宽容。 */
    private static final Map<String, String> ALLOWED = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "gif", "image/gif");

    /** GET 只接受服务端自己生成的文件名（UUID + 白名单扩展名），路径穿越在正则这一层就死掉 */
    private static final Pattern SERVABLE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png|webp|gif)$");

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private final ObjectStorage storage;
    private final int maxSizeMb;
    private final String publicBaseUrl;

    /**
     * 下载远程图片的客户端：<b>不跟随重定向</b>。
     * 跟随 3xx 会绕过「公网地址校验」（先给公网，再 302 到内网），是最常见的一类 SSRF 绕法。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public UploadController(
            @Qualifier("publicObjectStorage") ObjectStorage storage,
            @Value("${duoduo.uploads.max-size-mb:5}") int maxSizeMb,
            @Value("${duoduo.uploads.public-base-url:}") String publicBaseUrl) {
        this.storage = storage;
        this.maxSizeMb = maxSizeMb;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    /**
     * 上传一张图片，返回可直接塞进 images 列表的绝对 URL。
     *
     * <p>原文件名只用来取扩展名（并校验），绝不入盘 —— 用户文件名里什么都可以有。
     */
    @PostMapping("/api/uploads")
    public ApiResponse<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        // 网关对非白名单路径已验 JWT，这里再取一次登录态是纵深防御：
        // 直连服务端口的调用方绕不过 UserContext 为空这一关（requireUserId 为空即抛 401）
        Long userId = UserContext.requireUserId();
        if (file == null || file.isEmpty()) {
            throw BizException.validation("请选择要上传的图片");
        }
        if (maxSizeMb > 0 && file.getSize() > maxSizeMb * 1024L * 1024L) {
            throw BizException.validation("图片不能超过 " + maxSizeMb + "MB");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!IMAGE_CONTENT_TYPES.contains(contentType)) {
            throw BizException.validation("只支持 jpg / png / webp / gif 图片");
        }
        String ext = extOf(file.getOriginalFilename());
        if (ext == null || !ALLOWED.containsKey(ext)) {
            throw BizException.validation("只支持 jpg / png / webp / gif 图片");
        }

        String stored = UUID.randomUUID() + "." + ext;
        // ★ 逻辑 key 带 uploads/ 前缀 —— 这一层让本地盘与对象存储**共用同一个对外 URL**
        //   （<base>/uploads/<uuid>.<ext>）。本地实现会把前缀剥掉落到 /data/uploads/<uuid>，
        //   存量文件因此原地可用；COS 实现则原样作为桶内对象 key。
        try {
            storage.put(ObjectStorage.PREFIX_PUBLIC + stored, file.getBytes(), contentType);
        } catch (IOException e) {
            // 读上传流失败（客户端中断等）不该以 500 蒙面，给可行动的提示并留 ERROR 现场
            log.error("读取上传流失败 stored={}", stored, e);
            throw BizException.businessRule("图片保存失败，请稍后重试或联系客服");
        } catch (BizException e) {
            // 存储层（含 COS）的失败已带可读原因，这里只补一句用户可行动的话，原样抛出
            log.error("图片写入存储失败 backend={} stored={}", storage.backend(), stored, e);
            throw BizException.businessRule("图片保存失败，请稍后重试或联系客服");
        }

        String url = baseUrl(request) + "/uploads/" + stored;
        // 不记原文件名：它是不可信输入，进日志只会污染检索
        log.info("用户 {} 上传图片 {}（{} 字节，后端 {}）", userId, stored, file.getSize(), storage.backend());
        return ApiResponse.ok(Map.of("url", url));
    }

    /**
     * 从远程地址导入一张图片（2026-10-18）：下载 → 校验 → 落同一个存储 → 返回本站 URL。
     *
     * <p><b>为什么要有这个端点</b>：评价表单此前只接受「图片直链」，而直链会带来三个问题：
     * 外站图随时失效（评价变裂图）、外站防盗链、以及 http 页面里的混合内容。
     * 统一转存到自己的 COS 后，评价展示用的就是本站地址，和本地上传的图完全同源同生命周期。
     *
     * <p>安全边界见 {@link RemoteImageImport}（协议 / 公网地址 / 文件头三道闸门，
     * 不跟随重定向，连接 5s、读取 10s、体积上限与普通上传一致）。
     */
    @PostMapping("/api/uploads/from-url")
    public ApiResponse<Map<String, String>> uploadFromUrl(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) throws IOException {
        Long userId = UserContext.requireUserId();
        URI uri = RemoteImageImport.validateUrl(body == null ? null : body.get("url"));

        HttpRequest remote = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "duoduo-review-image-importer/1.0")
                .header("Accept", "image/*")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(remote, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.businessRule("图片下载被中断，请重试");
        } catch (IOException e) {
            log.warn("远程图片下载失败 url={}", uri, e);
            throw BizException.businessRule("图片地址无法访问，请检查后重试");
        }

        if (response.statusCode() != 200) {
            // 3xx 也走这里：不跟随重定向是刻意的（见 httpClient 注释），给用户可理解的提示
            throw BizException.validation("图片地址返回 " + response.statusCode() + "，无法导入");
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!RemoteImageImport.isAllowedContentType(contentType)) {
            throw BizException.validation("该地址不是 jpg / png / webp / gif 图片");
        }
        long limitMb = maxSizeMb > 0 ? maxSizeMb : 20;
        long limit = limitMb * 1024L * 1024L;
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = readAtMost(in, limit);
        }
        if (bytes == null) {
            throw BizException.validation("图片不能超过 " + limitMb + "MB");
        }
        if (!RemoteImageImport.looksLikeImage(bytes)) {
            // Content-Type 可以撒谎，文件头不会：拒绝「声称是图片的错误页 / HTML」
            throw BizException.validation("该地址返回的内容不是有效图片");
        }

        String ext = RemoteImageImport.extOf(contentType);
        String stored = UUID.randomUUID() + "." + ext;
        try {
            storage.put(ObjectStorage.PREFIX_PUBLIC + stored, bytes, contentType);
        } catch (BizException e) {
            log.error("远程图片写入存储失败 backend={} stored={}", storage.backend(), stored, e);
            throw BizException.businessRule("图片保存失败，请稍后重试或联系客服");
        }

        String url = baseUrl(request) + "/uploads/" + stored;
        log.info("用户 {} 从 {} 导入图片 {}（{} 字节，后端 {}）",
                userId, uri.getHost(), stored, bytes.length, storage.backend());
        return ApiResponse.ok(Map.of("url", url));
    }

    /** 读取上限内的字节；超限返回 null（不抛 IO 异常，便于给出「超过 xMB」的准确提示）。 */
    private byte[] readAtMost(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 公开读：图片标签直出。文件名不是服务端生成的格式一律 404，不探测底层存储。 */
    @GetMapping("/uploads/{filename}")
    public ResponseEntity<byte[]> serve(@PathVariable String filename) {
        if (filename == null || !SERVABLE.matcher(filename).matches()) {
            return ResponseEntity.notFound().build();
        }
        byte[] content = storage.get(ObjectStorage.PREFIX_PUBLIC + filename);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }
        String ext = extOf(filename);
        MediaType mediaType = MediaType.parseMediaType(ALLOWED.getOrDefault(ext, "application/octet-stream"));
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic())
                .body(content);
    }

    /** 取小写扩展名（不含点）；无扩展名返回 null */
    private String extOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 拼图片的对外可访问 base（http(s)://host，不带尾斜杠）。
     *
     * <p>优先级：显式配置（生产配 CDN / 独立图床域名）&gt; 网关注入的 X-Forwarded-*
     * （Spring Cloud Gateway 默认携带，即「用户实际访问的网关地址」）&gt; 请求自身 Host。
     * 落到 Host 的场景是本地直连服务端口调试，URL 指向服务自身，同样能访问。
     */
    private String baseUrl(HttpServletRequest request) {
        if (!publicBaseUrl.isEmpty()) {
            return publicBaseUrl.replaceAll("/+$", "");
        }
        String host = request.getHeader("X-Forwarded-Host");
        String proto = request.getHeader("X-Forwarded-Proto");
        if (host == null || host.isBlank()) {
            // 直连服务端口：Host 即服务自身
            host = request.getHeader("Host");
            proto = request.getScheme();
        }
        if (host == null || host.isBlank()) {
            URI uri = URI.create(request.getRequestURL().toString());
            host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            proto = uri.getScheme();
        }
        // X-Forwarded-Host 可能带端口（localhost:8080），proto 缺省按 http
        String scheme = proto == null || proto.isBlank() ? "http" : proto;
        return scheme + "://" + host;
    }
}

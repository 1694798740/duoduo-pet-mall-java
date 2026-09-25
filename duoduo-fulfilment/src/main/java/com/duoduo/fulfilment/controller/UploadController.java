package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * <p>存储是本地盘（对象存储接入前的过渡态）：容器里挂卷即可持久，丢了也只丢图不丢单。
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

    private final Path storageDir;
    private final int maxSizeMb;
    private final String publicBaseUrl;

    public UploadController(
            @Value("${duoduo.uploads.dir:./data/uploads}") String dir,
            @Value("${duoduo.uploads.max-size-mb:5}") int maxSizeMb,
            @Value("${duoduo.uploads.public-base-url:}") String publicBaseUrl) {
        this.storageDir = Paths.get(dir).toAbsolutePath().normalize();
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
        Files.createDirectories(storageDir);
        Path target = storageDir.resolve(stored).normalize();
        // 双保险：resolve + normalize 后必须还在存储目录里（未来有人改文件名生成逻辑时兜底）
        if (!target.startsWith(storageDir)) {
            throw BizException.validation("非法的存储路径");
        }
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 落盘失败（典型：卷属主不对 / 磁盘满）不该以 500 蒙面，给出可行动的提示并留 ERROR 现场由运维看容器日志
            log.error("图片落盘失败 dir={} stored={}", storageDir, stored, e);
            throw BizException.businessRule("图片保存失败，请稍后重试或联系客服");
        }

        String url = baseUrl(request) + "/uploads/" + stored;
        // 不记原文件名：它是不可信输入，进日志只会污染检索
        log.info("用户 {} 上传图片 {}（{} 字节）", userId, stored, file.getSize());
        return ApiResponse.ok(Map.of("url", url));
    }

    /** 公开读：图片标签直出。文件名不是服务端生成的格式一律 404，不探测文件系统。 */
    @GetMapping("/uploads/{filename}")
    public ResponseEntity<FileSystemResource> serve(@PathVariable String filename) {
        if (filename == null || !SERVABLE.matcher(filename).matches()) {
            return ResponseEntity.notFound().build();
        }
        Path target = storageDir.resolve(filename).normalize();
        if (!target.startsWith(storageDir) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        String ext = extOf(filename);
        MediaType mediaType = MediaType.parseMediaType(ALLOWED.getOrDefault(ext, "application/octet-stream"));
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic())
                .body(new FileSystemResource(target));
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

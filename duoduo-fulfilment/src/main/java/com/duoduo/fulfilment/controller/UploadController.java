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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
            "gif", "image/gif",
            // B 批（2026-10-21）：客服/私信支持视频消息。只收 mp4：小程序 <video> 与浏览器
            // 兼容性最好，转码能力（mov/webm）不在本轮范围内。
            "mp4", "video/mp4",
            // 端侧压缩的产物：浏览器 MediaRecorder 在部分环境只产 webm
            "webm", "video/webm",
            // 语音消息（2026-10-24）：小程序 RecorderManager 默认 aac；web 录音可能产
            // audio/mp4（扩展名 mp4 已在白名单）或 audio/webm（扩展名 webm 已在白名单）
            "aac", "audio/aac",
            "mp3", "audio/mpeg");

    /** GET 只接受服务端自己生成的文件名（UUID + 白名单扩展名），路径穿越在正则这一层就死掉 */
    private static final Pattern SERVABLE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png|webp|gif|mp4|webm|aac|mp3)$");

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    /** 视频 MIME（B 批）：只收 mp4，与小程序的 chooseMedia / 浏览器 <video> 对齐 */
    private static final Set<String> VIDEO_CONTENT_TYPES = Set.of("video/mp4", "video/webm");

    /** 语音 MIME（2026-10-24）：web 录音与小程序录音的产物 */
    private static final Set<String> AUDIO_CONTENT_TYPES = Set.of(
            "audio/aac", "audio/mpeg", "audio/mp4", "audio/webm", "audio/mp3");

    private final ObjectStorage storage;
    private final int maxSizeMb;
    private final int maxVideoSizeMb;
    private final String publicBaseUrl;

    /**
     * 拼在图片 URL 后面的处理参数（数据万象 imageMogr2），形如
     * {@code ?imageMogr2/auto-orient/thumbnail/1600x1600>/format/webp/quality/82/strip}。
     *
     * <p><b>为什么做成"空即关闭"的配置，而不是写死</b>：
     * <ul>
     *   <li>本地盘模式（{@code duoduo.storage.type=local}）没有图片处理能力，
     *       带参数只会产生一条永远走不到的特殊分支；</li>
     *   <li>参数调错了（比如把商品图压糊）要能一行环境变量回滚，
     *       而不是回滚一次发布 —— 图片是用户能直接看见的东西，必须留后路。</li>
     * </ul>
     * 默认空串，行为与改造前逐字节一致。
     */
    private final String imageSuffix;

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
            @Value("${duoduo.uploads.max-video-size-mb:20}") int maxVideoSizeMb,
            @Value("${duoduo.uploads.public-base-url:}") String publicBaseUrl,
            @Value("${duoduo.uploads.image-suffix:}") String imageSuffix) {
        this.storage = storage;
        this.maxSizeMb = maxSizeMb;
        this.maxVideoSizeMb = maxVideoSizeMb;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        this.imageSuffix = imageSuffix == null ? "" : imageSuffix.trim();
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
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        boolean video = VIDEO_CONTENT_TYPES.contains(contentType);
        boolean audio = AUDIO_CONTENT_TYPES.contains(contentType);
        // 视频**不按大小限制**（第七十九轮修订）：时长由端侧校验、体积由端侧压缩，
        // 服务端再卡 MB 只会让「压缩失败回退原视频」这条兜底路径必然失败。
        // 图片仍保留体积上限（图片没有端侧压缩管线，放任大图直接打满存储）。
        // 音频（2026-10-24）：语音 ≤60s、端侧录制，体积天然小；与视频同口径不卡大小，
        // 否则「60 秒录音被 5MB 限制拒绝」这种边缘 case 只会制造无效报错。
        if (!video && !audio && maxSizeMb > 0 && file.getSize() > maxSizeMb * 1024L * 1024L) {
            throw BizException.validation("图片不能超过 " + maxSizeMb + "MB");
        }
        if (!video && !audio && !IMAGE_CONTENT_TYPES.contains(contentType)) {
            throw BizException.validation("只支持 jpg / png / webp / gif 图片、mp4 视频或语音");
        }
        String ext = extOf(file.getOriginalFilename());
        if (ext == null || !ALLOWED.containsKey(ext)) {
            throw BizException.validation("只支持 jpg / png / webp / gif 图片、mp4 视频或语音");
        }
        // 扩展名与 MIME 必须一致：只改后缀的 exe/网页不能借 mp4 之名落盘
        boolean extVideo = "mp4".equals(ext) || "webm".equals(ext);
        boolean extAudio = "aac".equals(ext) || "mp3".equals(ext) || extVideo;
        if (audio ? !extAudio : (video != extVideo)) {
            throw BizException.validation("文件类型与扩展名不一致");
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
            // 存储层（含 COS）的失败消息已经是对用户可行动的（如"账户欠费被停用，请充值"），
            // **原样抛出**，不要覆盖 —— 早期这里统一盖成"请稍后重试"，把"欠费停服"
            // 这种重试一百次也不会好的问题说成偶发故障，排障方向直接被带偏。
            log.error("图片写入存储失败 backend={} stored={}", storage.backend(), stored, e);
            throw e;
        }

        String url = publicUrl(request, stored, ext);
        // 不记原文件名：它是不可信输入，进日志只会污染检索
        log.info("用户 {} 上传{} {}（{} 字节，后端 {}）", userId, video ? "视频" : "图片",
                stored, file.getSize(), storage.backend());
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
            // 同上：存储层给的消息已可行动（欠费 / 权限 / 桶名错各有各的话），原样抛出
            log.error("远程图片写入存储失败 backend={} stored={}", storage.backend(), stored, e);
            throw e;
        }

        String url = publicUrl(request, stored, ext);
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

    /**
     * 公开读：图片 / 视频直出。文件名不是服务端生成的格式一律 404，不探测底层存储。
     *
     * <p><b>视频必须支持 Range（206）</b>：浏览器与小程序播放器拖动进度、
     * 甚至 Safari 起播都会先发 {@code Range: bytes=0-1}；只回 200 全量字节时，
     * 表现是「能下载但播不了 / 拖不动」，而接口看起来一切正常。
     * 本地存储返回的是完整 byte[]，这里按 Range 切片；COS 场景可直接 302 到直链（COS 原生支持）。
     */
    @GetMapping("/uploads/{filename}")
    public ResponseEntity<byte[]> serve(@PathVariable String filename,
                                        @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        if (filename == null || !SERVABLE.matcher(filename).matches()) {
            return ResponseEntity.notFound().build();
        }
        byte[] content = storage.get(ObjectStorage.PREFIX_PUBLIC + filename);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }
        String ext = extOf(filename);
        MediaType mediaType = MediaType.parseMediaType(ALLOWED.getOrDefault(ext, "application/octet-stream"));
        boolean video = "mp4".equals(ext) || "webm".equals(ext);

        if (video && range != null && range.startsWith("bytes=")) {
            long[] window = parseRange(range, content.length);
            if (window == null) {
                return ResponseEntity.status(416)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + content.length)
                        .build();
            }
            int start = (int) window[0];
            int end = (int) window[1];
            byte[] slice = java.util.Arrays.copyOfRange(content, start, end + 1);
            return ResponseEntity.status(206)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + content.length)
                    .contentLength(slice.length)
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic())
                    .body(slice);
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic());
        if (video) {
            builder = builder.header(HttpHeaders.ACCEPT_RANGES, "bytes");
        }
        return builder.body(content);
    }

    /**
     * 解析单段 Range：{@code bytes=start-end} / {@code bytes=start-} / {@code bytes=-suffix}。
     * 返回 [start, end]（含端点）；越界返回 null（由调用方回 416）。
     */
    private long[] parseRange(String header, long length) {
        try {
            String spec = header.substring("bytes=".length()).split(",")[0].trim();
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? length - 1 : Long.parseLong(right);
            }
            if (start < 0 || start >= length) {
                return null;
            }
            end = Math.min(end, length - 1);
            return end < start ? null : new long[]{start, end};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 取小写扩展名（不含点）；无扩展名返回 null */
    private String extOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 对外图片 URL = base + {@code /uploads/<uuid>.<ext>} + 可选处理参数。
     *
     * <p><b>GIF 刻意不加参数</b>：{@code format/webp} 会把动图压成一张静态图，
     * 用户上传的表情包/动图会突然不动 —— 这种"只是变小了"的功能性回归最容易
     * 在验收时被漏掉。动图体积大是业务事实，不为省几十 KB 把它变成静图。
     *
     * <p>参数只影响**返回给前端的地址**，不改桶内对象，也不改本服务
     * {@code GET /uploads/{file}} 的读取契约：本地盘模式下该端点仍按原路径可读
     * （query 部分不进 {@code @PathVariable}，被 Spring 自然忽略）。
     */
    private String publicUrl(HttpServletRequest request, String stored, String ext) {
        String base = baseUrl(request) + "/uploads/" + stored;
        // 视频绝不能带 imageMogr2 参数：那不是视频处理接口，带上会直接 404/403。
        // 同理，本地盘也没有图片处理能力：只有 COS 后端才追加该参数。
        // （否则本地形态下 URL 会带一个没人处理的 query，nginx 甚至会因 `>` 直接 400）
        boolean cosBackend = "cos".equalsIgnoreCase(storage.backend());
        if (!cosBackend || imageSuffix.isEmpty() || "gif".equals(ext)
                || "mp4".equals(ext) || "webm".equals(ext)) {
            return base;
        }
        return base + imageSuffix;
    }

    /**
     * 内部接口：按 URL 批量删除上传对象（2026-10-23，供社交域的会话过期清理调用）。
     *
     * <h2>为什么删对象这件事必须留在本域</h2>
     * 存储选型（local / COS）、桶、公共域名都在这里。让别的域自己配一套凭据去删，
     * 一旦只改一处配置，就会出现「删的是另一个桶 / 本地空目录」这种静默失败。
     * 调用方只表达「这些 URL 可以删」，怎么删由本域决定。
     *
     * <h2>安全边界</h2>
     * <ul>
     *   <li>只处理路径里含 {@code /uploads/} 的 URL：资质材料（{@code materials/}）、
     *       贴纸（{@code /stickers/*.svg}）这类静态资源一律跳过；</li>
     *   <li>对象不存在视为已删除（{@code ObjectStorage.delete} 幂等），因此可重复调用；</li>
     *   <li>接口挂 {@code /api/uploads/internal/} 前缀，由 {@code InternalTokenFilter} 校验服务间签名，
     *       网关不暴露（网关只转发 {@code /api/uploads}，不带 internal 段）。</li>
     * </ul>
     */
    @PostMapping("/api/uploads/internal/delete")
    public ApiResponse<Integer> internalDelete(@RequestBody(required = false) List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return ApiResponse.ok(0);
        }
        int deleted = 0;
        for (String url : urls) {
            String key = publicKeyOf(url);
            if (key == null) {
                continue;
            }
            try {
                if (storage.exists(key)) {
                    storage.delete(key);
                    deleted++;
                }
            } catch (Exception e) {
                // 单个对象失败不影响其余：调用方据「返回条数 < 请求条数」决定是否重试整批
                log.warn("删除上传对象失败 key={}", key, e);
            }
        }
        return ApiResponse.ok(deleted);
    }

    /**
     * 对外 URL → 桶内对象 key（{@code uploads/<uuid>.<ext>}）。
     *
     * <p>必须按 {@code /uploads/} 定位而不是取最后一段：图片 URL 带 {@code imageMogr2} 查询串，
     * 用整串找 key 会带着参数去删，必然删不掉（而且看起来"成功"）。
     * 不是本域上传目录的地址返回 {@code null}，调用方按跳过处理。
     */
    private String publicKeyOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String noQuery = url.trim();
        int q = noQuery.indexOf('?');
        if (q >= 0) {
            noQuery = noQuery.substring(0, q);
        }
        int idx = noQuery.indexOf("/uploads/");
        if (idx < 0) {
            return null;
        }
        String key = noQuery.substring(idx + 1); // 去掉前导 '/'
        if (key.length() <= ObjectStorage.PREFIX_PUBLIC.length() || key.contains("..")) {
            return null;
        }
        return key;
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

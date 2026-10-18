package com.duoduo.fulfilment.support;

import com.duoduo.common.exception.BizException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * 远程图片导入的校验判据（2026-10-18）。
 *
 * <h2>为什么不能「用户给个 URL 就直接下载」</h2>
 * 这个接口是服务端替用户发起请求 —— 天然是一个 SSRF 入口：
 * 若允许 {@code http://127.0.0.1:8083/actuator/env}，就等于把内网接口暴露给了任何登录用户。
 * 所以三道闸门一起上：
 * <ol>
 *   <li><b>协议白名单</b>：只允许 http / https（file、gopher、jar 一律拒绝）；</li>
 *   <li><b>地址必须落在公网</b>：解析出的每个 IP 都不能是回环 / 私有 / 链路本地 / 组播；</li>
 *   <li><b>内容必须是真图片</b>：Content-Type 在图片白名单内，且文件头能对上（防「改后缀的 HTML」）。</li>
 * </ol>
 *
 * <p><b>已知残余风险（诚实写在这里）</b>：DNS 重绑定 —— 校验时解析到公网 IP，
 * 下载时再解析一次可能变成内网地址。彻底堵死需要「解析一次后直连 IP」，那会破坏 HTTPS 的
 * SNI/证书校验。当前缓解：不跟随重定向（3xx 直接失败）+ 5 秒连接超时 + 10 秒读超时 + 体积上限。
 * 若将来该服务直接暴露公网，应改为走「隔离的下载代理」而不是继续加正则。
 */
public final class RemoteImageImport {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private RemoteImageImport() {
    }

    /** 校验 URL 形状与目标地址，不通过直接抛 400（文案面向用户，可直接展示）。 */
    public static URI validateUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.validation("请填写图片地址");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw BizException.validation("图片地址格式不正确");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw BizException.validation("只支持 http / https 图片地址");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw BizException.validation("图片地址缺少主机名");
        }
        if (uri.getUserInfo() != null) {
            // user:pass@host 这种写法常被用来混淆真实主机，直接拒绝
            throw BizException.validation("图片地址不能包含用户名或密码");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw BizException.validation("图片地址无法解析");
        }
        if (addresses.length == 0) {
            throw BizException.validation("图片地址无法解析");
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw BizException.validation("该地址不允许访问：只支持公网图片地址");
            }
        }
        return uri;
    }

    /** 回环 / 私有 / 链路本地 / 组播 / 通配地址一律视为内网。 */
    private static boolean isBlocked(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }

    /** Content-Type 归一化后是否在图片白名单内（去掉 ;charset= 之类的参数）。 */
    public static boolean isAllowedContentType(String contentType) {
        return contentType != null && ALLOWED_TYPES.contains(normalizeType(contentType));
    }

    /** 由 Content-Type 推扩展名；不在白名单返回 null。 */
    public static String extOf(String contentType) {
        String type = normalizeType(contentType);
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> null;
        };
    }

    /**
     * 文件头校验：只认四种图片的真实魔数。
     *
     * <p>为什么 Content-Type 之外还要看文件头：远程服务器可以声称 image/png 却返回一段 HTML
     * （错误页 / 钓鱼页），存进 COS 后再以图片路径公开，等于把任意内容托管到自己的域名下。
     */
    public static boolean looksLikeImage(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return true;
        }
        // GIF: GIF87a / GIF89a
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return true;
        }
        // WEBP: RIFF....WEBP
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static String normalizeType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String type = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return type.trim().toLowerCase(Locale.ROOT);
    }
}

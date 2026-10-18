package com.duoduo.fulfilment.support;

import com.duoduo.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 远程图片导入判据单测（2026-10-18）。
 *
 * <p>这层是 SSRF 的唯一闸门：任何一条漏掉，登录用户就能让服务端去请求
 * {@code http://127.0.0.1:8083/...} 或云厂商元数据地址。
 * 所以断言必须写「内网字面量一律拒绝」，而不是只测 happy path。
 */
class RemoteImageImportTest {

    private static void rejects(String url, String why) {
        assertThatThrownBy(() -> RemoteImageImport.validateUrl(url))
                .as(why)
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("非 http(s) 协议一律拒绝（file / gopher / jar）")
    void 拒绝非http协议() {
        rejects("file:///etc/passwd", "file 协议");
        rejects("gopher://127.0.0.1:70/_x", "gopher 协议");
        rejects("jar:http://example.com/a.jar!/x", "jar 协议");
    }

    @Test
    @DisplayName("回环 / 私有 / 链路本地地址一律拒绝（含 IPv6 回环）")
    void 拒绝内网地址() {
        rejects("http://127.0.0.1/a.png", "回环");
        rejects("http://10.1.2.3/a.png", "10/8");
        rejects("http://172.16.0.9/a.png", "172.16/12");
        rejects("http://192.168.1.1/a.png", "192.168/16");
        rejects("http://169.254.169.254/latest/meta-data", "云元数据（链路本地）");
        rejects("http://[::1]/a.png", "IPv6 回环");
    }

    @Test
    @DisplayName("带用户名密码的地址拒绝（常用于混淆真实主机）")
    void 拒绝带userinfo的地址() {
        rejects("http://user:pass@127.0.0.1/a.png", "userinfo");
    }

    @Test
    @DisplayName("空地址 / 非法地址给出可读错误")
    void 空与非法地址() {
        rejects("", "空串");
        rejects("   ", "全空格");
        rejects("not a url", "非法 URI");
    }

    @Test
    @DisplayName("Content-Type 白名单只认四种图片（允许带 charset 参数）")
    void 内容类型白名单() {
        assertThat(RemoteImageImport.isAllowedContentType("image/png")).isTrue();
        assertThat(RemoteImageImport.isAllowedContentType("image/jpeg; charset=binary")).isTrue();
        assertThat(RemoteImageImport.isAllowedContentType("image/webp")).isTrue();
        assertThat(RemoteImageImport.isAllowedContentType("image/gif")).isTrue();
        assertThat(RemoteImageImport.isAllowedContentType("text/html")).isFalse();
        assertThat(RemoteImageImport.isAllowedContentType(null)).isFalse();
        assertThat(RemoteImageImport.extOf("image/jpeg")).isEqualTo("jpg");
        assertThat(RemoteImageImport.extOf("text/html")).isNull();
    }

    @Test
    @DisplayName("文件头校验：真图片放行，HTML 伪装成图片必须拒绝")
    void 文件头校验() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] gif = new byte[]{'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};
        byte[] html = "<!DOCTYPE html><html><body>error</body></html>".getBytes();

        assertThat(RemoteImageImport.looksLikeImage(png)).isTrue();
        assertThat(RemoteImageImport.looksLikeImage(jpeg)).isTrue();
        assertThat(RemoteImageImport.looksLikeImage(gif)).isTrue();
        assertThat(RemoteImageImport.looksLikeImage(html)).isFalse();
        assertThat(RemoteImageImport.looksLikeImage(new byte[]{1, 2, 3})).isFalse();
        assertThat(RemoteImageImport.looksLikeImage(null)).isFalse();
    }
}

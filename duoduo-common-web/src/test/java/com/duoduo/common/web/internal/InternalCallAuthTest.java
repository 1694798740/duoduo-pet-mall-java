package com.duoduo.common.web.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 服务间调用凭据单测（第五十八轮 · P0-3）。
 *
 * <h2>钉住的是「换掉明文共享密钥」之后不能回退的三条性质</h2>
 * <ol>
 *   <li><b>密钥不出现在链路上</b>：请求头里不能有根密钥（否则日志一行泄露 = 全站内部接口钥匙）。</li>
 *   <li><b>签名绑定身份与请求</b>：换调用方名 / 换方法都必须失效
 *       —— 否则一条被观察到的请求头可以被挪用到别的身份或别的 HTTP 方法上。
 *       （路径<b>刻意不参与</b>签名，理由见 {@link #路径不参与签名_换路径不影响校验结果()}。）</li>
 *   <li><b>一次性</b>：同一个请求头用两次判重放；时间窗外的签名判过期。</li>
 * </ol>
 *
 * <h2>反向验证（新增断言有效性的证明）</h2>
 * 故意把 {@code claimNonce} 改成恒真，「同一个请求头用两次 → 重放」这条立刻转红；
 * 故意把派生密钥的 {@code caller} 去掉，身份绑定那两条立刻转红。
 * ——「通过」的检查必须能在改坏之后报错，否则是空转规则。
 */
class InternalCallAuthTest {

    private static final String ROOT = "duoduo-local-dev-internal-token";
    private static final String METHOD = "POST";
    private static final String PATH = "/api/orders/internal/sweep";

    private static InternalCallAuth auth(String caller) {
        return new InternalCallAuth(ROOT, caller, 300L, Map.of());
    }

    @Test
    void 签发的凭据能通过校验() {
        InternalCallAuth a = auth("duoduo-trade");
        String header = a.sign(METHOD);
        assertThat(a.verify(header, METHOD).ok()).isTrue();
    }

    @Test
    void 请求头里不含密钥本身() {
        // 这是本次改造的核心：泄露一行日志不再等于交出全站内部接口的钥匙
        String header = auth("duoduo-trade").sign(METHOD);
        assertThat(header).doesNotContain(ROOT);
    }

    @Test
    void 换调用方身份后签名失效_身份绑定() {
        InternalCallAuth trade = auth("duoduo-trade");
        String header = trade.sign(METHOD);
        // 把身份段换成别的服务名（其余原样不动）
        String[] parts = header.split("\\.", 5);
        String forged = parts[0] + "." + "duoduo-fulfilment" + "." + parts[2] + "." + parts[3] + "." + parts[4];

        assertThat(auth("duoduo-product").verify(forged, METHOD).ok()).isFalse();
    }

    @Test
    void 换方法后签名失效() {
        InternalCallAuth a = auth("duoduo-trade");
        String header = a.sign(METHOD);

        InternalCallAuth other = auth("duoduo-product");
        assertThat(other.verify(header, "GET").ok()).isFalse();
        assertThat(other.verify(header, METHOD).ok()).isTrue();
    }

    /**
     * 路径**不参与**签名，这条断言把「刻意如此」钉死，防止后来者当漏补。
     *
     * <p>原因（R59 实测）：Feign 的 {@code template.path()} 取不到 {@code @FeignClient(path=...)} 前缀，
     * 签进去就是全站 403。安全性没下降是因为 nonce 是一次性的 ——
     * 一条头被用到第二个接口上，第二次校验就判重放。
     */
    @Test
    void 路径不参与签名_换路径不影响校验结果() {
        InternalCallAuth a = auth("duoduo-trade");
        // 同一次签发的头，只在「方法」上做比对：换 URI 不影响结论（各自另签一次，避免 nonce 重放干扰）
        assertThat(a.verify(a.sign(METHOD), METHOD).ok()).isTrue();
        assertThat(auth("duoduo-product").verify(a.sign(METHOD), METHOD).ok()).isTrue();
    }

    @Test
    void 同一个请求头用第二次判重放() {
        InternalCallAuth a = auth("duoduo-trade");
        String header = a.sign(METHOD);

        assertThat(a.verify(header, METHOD).ok()).isTrue();
        // nonce 是一次性的：重放同一条请求头必须拒绝（否则抓到一次就能无限重放）
        assertThat(a.verify(header, METHOD).reason()).isEqualTo("replayed");
    }

    @Test
    void 时间窗外的签名判过期() {
        InternalCallAuth a = auth("duoduo-trade");
        String header = a.sign(METHOD);
        String[] parts = header.split("\\.", 5);

        long past = Instant.now().getEpochSecond() - 3_600L;
        String stale = parts[0] + "." + parts[1] + "." + past + "." + parts[3] + "." + parts[4];
        assertThat(a.verify(stale, METHOD).reason()).isEqualTo("expired");

        long future = Instant.now().getEpochSecond() + 3_600L;
        String skewed = parts[0] + "." + parts[1] + "." + future + "." + parts[3] + "." + parts[4];
        assertThat(a.verify(skewed, METHOD).reason()).isEqualTo("expired");
    }

    @Test
    void 老式明文共享密钥一律拒绝() {
        InternalCallAuth a = auth("duoduo-trade");
        // 改造前脚本 / 服务直接把根密钥原样放在头里；现在必须拒绝，否则改造等于没做
        assertThat(a.verify(ROOT, METHOD).ok()).isFalse();
        assertThat(a.verify("wrong-token-should-be-rejected", METHOD).reason()).isEqualTo("format");
        assertThat(a.verify(null, METHOD).reason()).isEqualTo("missing");
        assertThat(a.verify("", METHOD).reason()).isEqualTo("missing");
    }

    @Test
    void 根密钥为空时不放行_也不让两边都空变成通过() {
        InternalCallAuth a = new InternalCallAuth("", "duoduo-trade", 300L, Map.of());
        long now = Instant.now().getEpochSecond();
        assertThat(a.verify("dd1.duoduo-trade." + now + ".abc.def", METHOD).reason())
                .isEqualTo("no-secret");
        // 签发侧必须响亮失败，而不是发出一个「空密钥签名」让对端默默通过
        assertThatThrownBy(() -> a.sign(METHOD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 每个调用方派生出各自的密钥() {
        InternalCallAuth a = auth("duoduo-trade");
        // 「换个身份签名就失效」由签名串里的 caller 字段兜住，钉不住派生本身；
        // 这条才是在钉「拿到 trade 的密钥也冒充不了 fulfilment」。
        assertThat(a.secretForCaller("duoduo-trade"))
                .isNotEqualTo(a.secretForCaller("duoduo-fulfilment"))
                .isEqualTo(a.secretForCaller("DUODUO-TRADE"));
        // 显式配置的优先级高于派生（见下一条），这里保证默认路径下派生结果不为空
        assertThat(a.secretForCaller("duoduo-trade")).isNotEmpty();
    }

    @Test
    void 显式指定了调用方密钥时_该调用方用显式密钥() {
        Map<String, String> explicit = Map.of("duoduo-trade", "trade-only-secret");
        InternalCallAuth signer = new InternalCallAuth(ROOT, "duoduo-trade", 300L, explicit);
        String header = signer.sign(METHOD);

        // 根密钥不同、但显式表一致 → 仍能校验通过（可单独轮换某一个调用方）
        InternalCallAuth verifier = new InternalCallAuth("another-root", "duoduo-product", 300L, explicit);
        assertThat(verifier.verify(header, METHOD).ok()).isTrue();

        // 没配显式表的对端按根密钥派生 → 与显式密钥不一致，必须拒绝
        assertThat(auth("duoduo-product").verify(header, METHOD).reason()).isEqualTo("bad-signature");
    }

    @Test
    void 调用方身份归一为小写_大小写不同不会变成两把密钥() {
        // Spring 应用名本就是小写，这里归一只是消除「同一服务两种写法」的隐患：
        // 派生密钥按小写取，身份段也按小写签，两边永远是同一个值。
        InternalCallAuth upper = new InternalCallAuth(ROOT, "DUODUO-TRADE", 300L, Map.of());
        String header = upper.sign(METHOD);

        assertThat(header).startsWith("dd1.duoduo-trade.");
        assertThat(upper.caller()).isEqualTo("duoduo-trade");
        assertThat(auth("duoduo-product").verify(header, METHOD).ok()).isTrue();
    }

    @Test
    void 调用方密钥配置解析() {
        assertThat(InternalCallAuth.parseCallerSecrets(null)).isEmpty();
        assertThat(InternalCallAuth.parseCallerSecrets("  ")).isEmpty();
        assertThat(InternalCallAuth.parseCallerSecrets("duoduo-trade=abc, duoduo-fulfilment=def"))
                .containsEntry("duoduo-trade", "abc")
                .containsEntry("duoduo-fulfilment", "def")
                .hasSize(2);
        // 残缺项（缺值 / 无等号）直接跳过，不留半截密钥
        assertThat(InternalCallAuth.parseCallerSecrets("duoduo-trade=,broken,duoduo-ai=ok"))
                .containsExactly(Map.entry("duoduo-ai", "ok"));
    }
}

package com.duoduo.common.web.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **打真实 COS 的**联调测试（S9）。
 *
 * <h2>为什么必须有这一层</h2>
 * {@link CosSignerTest} 只能证明"算法照文档算对了"，**证不了服务端是否接受这个签名**；
 * {@link LocalFsObjectStorageTest} 证的是本地实现。两条都不覆盖真正的风险：
 * 密钥对不对、地域对不对、桶名对不对、策略是不是真的够/不过宽、公有读桶是不是真能匿名读。
 * 这些只有打一次真接口才知道。
 *
 * <h2>默认不跑（这是刻意的）</h2>
 * 用 {@code @EnabledIfEnvironmentVariable(named = "COS_LIVE_TEST", matches = "true")} 门控：
 * 常规 {@code mvn test} 与 CI 里**一律跳过**（既不依赖外网、也不会往真实桶里写东西），
 * 只有显式设了环境变量才执行。运行方式见方案 §7，参数从 `.env` 注入。
 *
 * <h2>夹具纪律（照抄冒烟脚本的三条）</h2>
 * ① 现场建：每次用带时间戳的随机 key；② 跑完自毁：{@link #cleanup()} 无条件删除本次创建的对象；
 * ③ 建不起 / 删不掉都**显式报红**——不静默跳过（"跑不起来就跳过"等于没有这条检查）。
 *
 * <h2>它验证的正是方案 §7 里最关键的几条</h2>
 * <ul>
 *   <li>B-5 公开桶：上传后**匿名 GET 必须 200**（证明"公有读私有写"配置生效、URL 形态正确）；</li>
 *   <li>B-6 私有桶：**匿名 GET 必须被拒**（这是本项目最重要的一条安全判据）；</li>
 *   <li>A-2 最小权限反向验证：用这把密钥**列桶必须被拒**（策略里没授 {@code cos:GetBucket}）；</li>
 *   <li>密钥写错一位 → 上传应明确报错，而不是静默成功。</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "COS_LIVE_TEST", matches = "true",
        disabledReason = "需要真实 COS 凭据；显式设 COS_LIVE_TEST=true 才执行")
class CosObjectStorageLiveTest {

    private static final String REGION = env("COS_REGION", "ap-guangzhou");
    private static final String SECRET_ID = env("COS_SECRET_ID", "");
    private static final String SECRET_KEY = env("COS_SECRET_KEY", "");
    private static final String BUCKET_PUBLIC = env("COS_BUCKET_PUBLIC", "");
    private static final String BUCKET_PRIVATE = env("COS_BUCKET_PRIVATE", "");

    private final String stamp = Long.toString(System.currentTimeMillis(), 36);
    private final CosObjectStorage publicStorage =
            new CosObjectStorage("public", BUCKET_PUBLIC, REGION, SECRET_ID, SECRET_KEY);
    private final CosObjectStorage privateStorage =
            new CosObjectStorage("private", BUCKET_PRIVATE, REGION, SECRET_ID, SECRET_KEY);

    private static String key(String bucketRole, String ext) {
        // 前缀刻意用 cos-live-test/ —— 一眼看出是我方测试产物，且**不带** uploads/ 前缀
        // （避免与真实业务对象混在一起；这里验的是存储层本身）
        return "cos-live-test/" + bucketRole + "-" + System.nanoTime() + ext;
    }

    private final String publicKey = key("public", ".txt");
    private final String privateKey = key("private", ".txt");

    @AfterEach
    void cleanup() {
        // ★ 跑完自毁：删不掉就抛（"删不掉"与"删干净"必须能分辨，这是夹具纪律第 2、3 条）
        publicStorage.delete(publicKey);
        privateStorage.delete(privateKey);
        assertThat(publicStorage.exists(publicKey))
                .as("夹具自毁失败：公开桶仍残留 " + publicKey).isFalse();
        assertThat(privateStorage.exists(privateKey))
                .as("夹具自毁失败：私有桶仍残留 " + privateKey).isFalse();
    }

    @Test
    @DisplayName("存储层读回：put → exists → get 内容一致 → delete 幂等")
    void roundTripOnPublicBucket() {
        byte[] body = "duoduo cos round trip".getBytes(StandardCharsets.UTF_8);

        publicStorage.put(publicKey, body, "text/plain");

        assertThat(publicStorage.exists(publicKey)).as("写入后 exists 应为 true").isTrue();
        assertThat(publicStorage.get(publicKey)).as("读回内容应逐字节一致").isEqualTo(body);
        assertThat(publicStorage.get("cos-live-test/definitely-absent-" + stamp))
                .as("不存在的对象应返回 null 而不是抛异常").isNull();

        publicStorage.delete(publicKey);
        publicStorage.delete(publicKey); // 幂等：第二次删不报错
        assertThat(publicStorage.exists(publicKey)).isFalse();
    }

    @Test
    @DisplayName("★ B-5 公开桶：匿名 GET 必须 200（公有读私有写配置生效）")
    void publicBucketServesAnonymously() throws Exception {
        byte[] body = "public-image-bytes".getBytes(StandardCharsets.UTF_8);
        publicStorage.put(publicKey, body, "text/plain");

        HttpResponse<byte[]> anon = anonymousGet(publicUrl(publicKey));

        assertThat(anon.statusCode())
                .as("公开桶的对象必须能匿名读到（<image> 发不出 Authorization 头，靠的就是这条）")
                .isEqualTo(200);
        assertThat(anon.body()).isEqualTo(body);
    }

    @Test
    @DisplayName("★★ B-6 私有桶：匿名 GET 必须被拒（资质材料绝不能匿名可读）")
    void privateBucketRefusesAnonymous() throws Exception {
        privateStorage.put(privateKey, "merchant-license".getBytes(StandardCharsets.UTF_8), "text/plain");

        // 有密钥能读 —— 证明对象确实写进去了、这批断言不是"因为没东西所以被拒"
        assertThat(privateStorage.get(privateKey)).isNotNull();

        HttpResponse<byte[]> anon = anonymousGet(privateUrl(privateKey));

        assertThat(anon.statusCode())
                .as("私有桶对象被匿名读到了 —— 这是数据事故，必须立刻检查桶的访问权限")
                .isIn(401, 403);
    }

    @Test
    @DisplayName("★ A-2 最小权限反向验证：这把密钥**列桶必须被拒**（策略里没有 GetBucket）")
    void cannotListBucket() throws Exception {
        // 「没有任何断言 = 没有证据」：先确认不带密钥时也拿不到列表（说明这个端点确实受鉴权保护），
        // 再确认带上这把密钥同样拿不到 —— 后者才是"策略里没授 GetBucket"的证据。
        // 若哪天有人给策略误加了 ListBucket/GetService，这条会变红，正是我们要的告警方向。
        assertThat(anonymousGet("https://" + publicStorage.host() + "/").statusCode())
                .as("匿名列桶应当是拒绝（确认该端点确实受鉴权保护）").isIn(401, 403);

        HttpResponse<String> withKey = get("https://" + publicStorage.host() + "/");
        assertThat(withKey.statusCode())
                .as("持密钥列桶竟然成功了 —— CAM 策略里被加上了 GetBucket/GetService，请收紧")
                .isIn(401, 403);
    }

    @Test
    @DisplayName("密钥写错一位：上传必须明确失败（不许静默成功）")
    void wrongSecretFailsLoudly() {
        CosObjectStorage broken = new CosObjectStorage("public", BUCKET_PUBLIC, REGION,
                SECRET_ID, SECRET_KEY.replace('a', 'b').replace('1', '2'));

        assertThat(catchThrowableOfPut(broken, publicKey))
                .as("密钥错误必须抛异常（COS 会回 SignatureDoesNotMatch），不能静默写入")
                .isInstanceOf(RuntimeException.class);
    }

    // ---------- 辅助 ----------

    private static RuntimeException catchThrowableOfPut(CosObjectStorage storage, String key) {
        try {
            storage.put(key, "x".getBytes(StandardCharsets.UTF_8), "text/plain");
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private String publicUrl(String k) {
        return "https://" + publicStorage.host() + "/" + CosSigner.uriEncode(k, false);
    }

    private String privateUrl(String k) {
        return "https://" + privateStorage.host() + "/" + CosSigner.uriEncode(k, false);
    }

    /** 完全不带 Authorization 的匿名请求 —— 模拟浏览器 / 小程序直接访问对象地址。 */
    private static HttpResponse<byte[]> anonymousGet(String url) throws Exception {
        return client().send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 带签名的 GET（复用被测的签名器），用于"持密钥做越权动作"的反向验证。 */
    private HttpResponse<String> get(String url) throws Exception {
        String host = URI.create(url).getHost();
        long now = java.time.Instant.now().getEpochSecond();
        String auth = new CosSigner(SECRET_ID, SECRET_KEY)
                .sign("get", "/", host, now - 60, now + 900);
        return client().send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", auth)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}

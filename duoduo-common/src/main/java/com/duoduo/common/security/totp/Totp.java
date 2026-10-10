package com.duoduo.common.security.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP（基于时间的一次性口令）—— 平台后台两步验证的算法实现（E21）。
 *
 * <p>这类代码只做一件事：把「共享密钥 + 当前时间」映射成 6 位数字。
 * 本类**刻意保持无状态、不接触数据库**：时间步的"用过就不能再用"（防重放）
 * 需要持久化，那属于服务层的职责（{@code TwoFactorService}），放在这里会让算法
 * 与存储耦合，单测也就跑不动了。
 *
 * <p><b>参数口径（与认证器 App 的默认值一致，改任何一个都会让已绑定的用户全部失效）</b>：
 * <ul>
 *   <li>算法 HmacSHA1 —— 不是"因为安全"，而是因为 Google Authenticator / 1Password /
 *       微软认证器等主流 App 的默认且最广泛兼容的就是它；</li>
 *   <li>步长 30 秒、位数 6 位 —— 同上，属事实标准。位数做成参数是为了能用 RFC 6238
 *       附录 B 的官方 8 位测试向量自证算法正确（见 {@code TotpTest}）；</li>
 *   <li>容差 ±1 步 —— 单独说明见 {@link #DEFAULT_WINDOW_STEPS}。</li>
 * </ul>
 *
 * <p><b>密钥长度</b>：{@link #randomSecret()} 生成 160 bit（20 字节）。
 * HMS-SHA1 的分组长度是 64 字节，密钥短于分组长度会先在内部补零 ——
 * 160 bit 是 RFC 4226 对 SHA1 的推荐值，也是各 App 的惯例。
 */
public final class Totp {

    /** 时间步长（秒） */
    public static final int DEFAULT_STEP_SECONDS = 30;
    /** 验证码位数 */
    public static final int DEFAULT_DIGITS = 6;

    /**
     * 校验时容忍的时间步数（前后各 1 步 = ±30 秒）。
     *
     * <p>为什么必须容忍：服务端与手机的时间不可能严格对齐，手机上的认证器 App
     * 也有自己的刷新节奏。不容忍的后果不是"更安全"，而是**用户在码刚跳变的那几秒
     * 反复失败**，进而被引导去怀疑"是不是密钥绑错了"。±1 步是业界通行取值；
     * 再放宽（±2 及以上）会让"码的有效窗口"长到可以暴力猜。
     */
    public static final int DEFAULT_WINDOW_STEPS = 1;

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** 10 的幂，避免每次调用 Math.pow 并做浮点→整型转换 */
    private static final int[] POW10 = {1, 10, 100, 1000, 10000, 100000, 1000000,
            10000000, 100000000, 1000000000};

    private Totp() {
    }

    /** 生成一个新的随机密钥（Base32 无填充，直接可填进认证器或 otpauth 链接） */
    public static String randomSecret() {
        byte[] bytes = new byte[20];
        SECURE_RANDOM.nextBytes(bytes);
        return Base32.encode(bytes);
    }

    /** 当前时刻对应的时间步序号 */
    public static long stepAt(long epochSecond, int stepSeconds) {
        if (stepSeconds <= 0) {
            throw new IllegalArgumentException("步长必须为正数");
        }
        return Math.floorDiv(epochSecond, stepSeconds);
    }

    /** 用默认口径（30 秒 / 6 位）生成指定时刻的验证码 */
    public static String generate(String base32Secret, long epochSecond) {
        return generate(base32Secret, epochSecond, DEFAULT_STEP_SECONDS, DEFAULT_DIGITS);
    }

    /** 生成指定时刻的验证码 */
    public static String generate(String base32Secret, long epochSecond, int stepSeconds, int digits) {
        return generateAtStep(stepAt(epochSecond, stepSeconds), decodeSecret(base32Secret), digits);
    }

    /**
     * 校验验证码，返回命中的时间步；不匹配返回 {@code -1}。
     *
     * <p><b>为什么返回时间步而不是布尔值</b>：调用方需要拿它做防重放 ——
     * 同一枚码在 30 秒内可以被重复提交无数次，若不记录"已经用掉的时间步"，
     * 攻击者只要截获一次（肩窥、日志泄露）就能在窗口内重放。把命中的步号交给调用方，
     * 由它和库里记录的"最近一次已用步号"比较，这个判断才有依据。
     *
     * <p>遍历顺序**从当前步开始向两侧扩散**（0, -1, +1, -2, +2 …）。
     * 同一枚码在数学上只会命中一个步号，所以顺序不影响结果 ——
     * 这里这样写是为了让"最可能的那个"排在前面，将来若加了"提前返回"的优化，
     * 行为仍然正确。
     *
     * @param base32Secret 共享密钥（Base32）
     * @param code         用户输入的验证码
     * @param epochSecond  当前 Unix 秒（由调用方传入，便于单测钉住时间）
     * @param stepSeconds  步长
     * @param digits       位数
     * @param windowSteps  前后容忍的步数；0 表示严格相等
     */
    public static long match(String base32Secret, String code, long epochSecond,
                             int stepSeconds, int digits, int windowSteps) {
        String normalized = normalizeCode(code, digits);
        if (normalized == null) {
            return -1L;
        }
        byte[] key = decodeSecret(base32Secret);
        long current = stepAt(epochSecond, stepSeconds);
        for (int distance = 0; distance <= Math.max(0, windowSteps); distance++) {
            // 同一步只算一次（distance=0 时两个方向会重复，靠这个判断跳过）
            for (int sign = -1; sign <= 1; sign++) {
                if (distance == 0 && sign < 0) {
                    continue;
                }
                long step = current + (long) distance * sign;
                if (step < 0) {
                    continue;
                }
                if (constantTimeEquals(generateAtStep(step, key, digits), normalized)) {
                    return step;
                }
            }
        }
        return -1L;
    }

    /**
     * 生成 {@code otpauth://} 链接（认证器 App 扫码用）。
     *
     * <p>形如 {@code otpauth://totp/发行方:账号?secret=XXX&issuer=发行方&algorithm=SHA1&digits=6&period=30}，
     * 由谷歌认证器提出的约定，各 App 都认。刻意显式写出 algorithm/digits/period：
     * 虽然它们就是默认值，但一旦将来改了默认口径，链接里写死的值能保证**已保存的二维码
     * 仍然可用**，不会因为服务端换默认值而让老用户扫码失败。
     */
    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DEFAULT_DIGITS + "&period=" + DEFAULT_STEP_SECONDS;
    }

    // ---------------- 内部实现 ----------------

    private static byte[] decodeSecret(String base32Secret) {
        if (base32Secret == null || base32Secret.isBlank()) {
            throw new IllegalArgumentException("TOTP 密钥为空");
        }
        return Base32.decode(base32Secret);
    }

    private static String generateAtStep(long step, byte[] key, int digits) {
        if (digits < 1 || digits > 9) {
            throw new IllegalArgumentException("位数必须在 1..9 之间");
        }
        byte[] mac = hmacSha1(key, counterBytes(step));
        // 动态截断（RFC 4226 §5.3）：取 HMAC 最后一个字节的低 4 位作偏移，
        // 从该偏移起取 4 个字节，最高位清零后就是 31 位整数。
        int offset = mac[mac.length - 1] & 0x0F;
        int binary = ((mac[offset] & 0x7F) << 24)
                | ((mac[offset + 1] & 0xFF) << 16)
                | ((mac[offset + 2] & 0xFF) << 8)
                | (mac[offset + 3] & 0xFF);
        int otp = binary % POW10[digits];
        return padLeft(otp, digits);
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException ex) {
            // HmacSHA1 是 JDK 必备算法，走到这里说明运行环境被裁剪过 —— 属于启动期就该暴露的问题
            throw new IllegalStateException("当前运行环境不支持 " + HMAC_ALGORITHM, ex);
        }
    }

    /** 时间步 → 8 字节大端表示（RFC 4226 规定的计数器编码） */
    private static byte[] counterBytes(long step) {
        byte[] out = new byte[8];
        long value = step;
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return out;
    }

    /**
     * 归一化用户输入：去空白（App 里常按 3+3 分组显示）并校验"必须是恰好 digits 位数字"。
     *
     * <p>不匹配就返回 null 直接判失败，而不是先算再比 —— 位数不对连算都不必算。
     * 同时这也拦住了"把恢复码当验证码填进来"这类误用：恢复码是字母数字混合，位数也不同。
     */
    private static String normalizeCode(String code, int digits) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim().replace(" ", "").replace("-", "");
        if (trimmed.length() != digits) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return null;
            }
        }
        return trimmed;
    }

    /** 恒定时间比较：避免"逐位比较提前返回"泄露正确前缀（对 6 位码意义有限，但零成本的习惯） */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }

    private static String padLeft(int value, int digits) {
        StringBuilder sb = new StringBuilder(String.valueOf(value));
        while (sb.length() < digits) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }

    /**
     * otpauth 链接里的百分号编码。
     *
     * <p>刻意不用 {@code URLEncoder}：它按 {@code application/x-www-form-urlencoded}
     * 把空格编成 {@code +}，而 otpauth 是 URI，空格应当是 {@code %20}。
     * 认证器 App 对 {@code +} 的处理并不一致，属"多数能认、个别认错"的坑。
     */
    private static String urlEncode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }
}

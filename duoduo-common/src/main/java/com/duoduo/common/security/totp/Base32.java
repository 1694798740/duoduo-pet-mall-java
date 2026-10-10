package com.duoduo.common.security.totp;

/**
 * RFC 4648 Base32 编解码（E21 · TOTP 密钥的表示形式）。
 *
 * <p><b>为什么自己写而不是引依赖</b>：本项目的 Maven 走离线模式（{@code -o}），
 * 新增第三方坐标需要先有本地仓库里的包，且在 CI 上会变成一个新的网络依赖。
 * 而 TOTP 需要的编解码一共 40 行、无状态、无配置，自研的维护成本低于引入一个库。
 * 另一个理由是**密钥的表示形式属于契约**：认证器 App（Google Authenticator / 1Password 等）
 * 只认 Base32，把这段逻辑收在自己手里，出问题时可读、可断点。
 *
 * <p><b>刻意不支持填充</b>：TOTP 场景下的密钥一律用**无填充**形式
 * （{@code otpauth://} 里 {@code secret=} 参数也是无填充的）。带 {@code =} 的密钥
 * 在认证器 App 里的兼容性并不一致，与其"多支持一种"再各处判断，不如只产出一种形态。
 * 解码侧则**容忍**填充字符 —— 因为人手输入时可能带上，
 * 拒绝它只会把一次能用的输入判成"密钥非法"。
 *
 * <p>大小写不敏感、忽略空白与连字符：认证器里显示的是大写分组，用户抄写时常带上空格。
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** 反查表：ASCII → 5 bit 值；-1 表示非法字符。用表而不是 indexOf，避免每次解码 O(32) 扫描 */
    private static final int[] LOOKUP = new int[128];

    static {
        java.util.Arrays.fill(LOOKUP, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            LOOKUP[ALPHABET.charAt(i)] = i;
            LOOKUP[Character.toLowerCase(ALPHABET.charAt(i))] = i;
        }
    }

    private Base32() {
    }

    /**
     * 编码为无填充的 Base32（大写）。
     *
     * <p>按 5 bit 一组切分，最后不足 5 bit 的部分**低位补零**后仍输出一个字符 ——
     * 这是 RFC 4648 的规定，补零不影响解码（多出来的位数在解码时按剩余位宽丢弃）。
     */
    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    /**
     * 解码 Base32。容忍填充字符、空白与连字符，大小写不敏感。
     *
     * @throws IllegalArgumentException 含非法字符时抛出（不静默丢弃 —— 密钥里混进一个
     *         手滑字符却"能跑"，表现是"认证码一直不对"，比直接报错难查得多）
     */
    public static byte[] decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("Base32 输入为 null");
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '=' || c == ' ' || c == '-' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            int value = c < 128 ? LOOKUP[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException("非法的 Base32 字符: '" + c + "'（位置 " + i + "）");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}

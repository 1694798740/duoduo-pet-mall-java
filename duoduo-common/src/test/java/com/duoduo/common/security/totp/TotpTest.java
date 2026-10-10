package com.duoduo.common.security.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E21 · TOTP 算法自证。
 *
 * <p>这里的核心断言**不依赖自己的实现自洽**：{@link #RFC6238官方测试向量_八位码()} 用的是
 * RFC 6238 附录 B 逐字给出的向量（含 secret 的 ASCII 原文与每个时刻的期望码）。
 * 只要那 6 组都过，就说明「Base32 编码 + HMAC-SHA1 + 动态截断」三段都写对了 ——
 * 认证器 App 里的结果自然一致。
 *
 * <p>其余用例覆盖的是**容易被"简化"掉、且简化后不报错**的地方：
 * 容差窗口、位数归一化、以及"用户输入带空格/分组符"这类真实输入形态。
 */
class TotpTest {

    /** RFC 6238 附录 B 的种子：ASCII 字符串 "12345678901234567890"（20 字节） */
    private static final String RFC_SECRET_ASCII = "12345678901234567890";
    /** 上面这串的 Base32 形式（无填充），认证器 App 里填的就是它 */
    private static final String RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    @DisplayName("Base32：RFC 6238 种子的编码结果与预期逐字一致（同时自证了编码器）")
    void Base32编码与预期一致() {
        assertEquals(RFC_SECRET_BASE32,
                Base32.encode(RFC_SECRET_ASCII.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("Base32：编解码往返无损（含非 5 字节整数倍的长度）")
    void Base32往返无损() {
        for (int length = 1; length <= 33; length++) {
            byte[] original = new byte[length];
            for (int i = 0; i < length; i++) {
                original[i] = (byte) (i * 7 + 3);
            }
            byte[] decoded = Base32.decode(Base32.encode(original));
            assertEquals(length, decoded.length, "长度=" + length + " 时往返后长度变了");
            for (int i = 0; i < length; i++) {
                assertEquals(original[i], decoded[i], "长度=" + length + " 第 " + i + " 字节不符");
            }
        }
    }

    @Test
    @DisplayName("Base32：容忍小写、空格、连字符与填充（用户手抄的真实形态）")
    void Base32容忍手工输入形态() {
        assertEquals(RFC_SECRET_BASE32, Base32.encode(Base32.decode("gezd gnbv-gy3t qojq gezd gnbv gy3t qojq")));
        assertEquals(RFC_SECRET_BASE32, Base32.encode(Base32.decode(RFC_SECRET_BASE32 + "====")));
    }

    @Test
    @DisplayName("Base32：非法字符必须响亮抛错，不静默丢弃（密钥里混进一个手滑字符最难查）")
    void Base32非法字符抛错() {
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("GEZD1NBV"));
    }

    @Test
    @DisplayName("★ RFC 6238 官方测试向量（8 位码）—— 算法正确性的权威依据")
    void RFC6238官方测试向量_八位码() {
        // 摘自 RFC 6238 附录 B（SHA1 行）。数字逐字照抄，不在此处做任何推导。
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < times.length; i++) {
            assertEquals(expected[i], Totp.generate(RFC_SECRET_BASE32, times[i], 30, 8),
                    "T=" + times[i] + " 的 8 位码不符");
        }
    }

    @Test
    @DisplayName("6 位码 = 同一时刻 8 位码的后 6 位（位数参数确实生效，而不是写死 6 位）")
    void 六位码取八位码后六位() {
        // 94287082 的后 6 位是 287082 —— 这是 RFC 6238 向量的直接推论
        assertEquals("287082", Totp.generate(RFC_SECRET_BASE32, 59L));
        assertEquals("94287082".substring(2), Totp.generate(RFC_SECRET_BASE32, 59L));
    }

    @Test
    @DisplayName("时间步：30 秒一个步，边界恰好落在整除点上")
    void 时间步边界() {
        assertEquals(0L, Totp.stepAt(0L, 30));
        assertEquals(0L, Totp.stepAt(29L, 30), "0..29 属于第 0 步");
        assertEquals(1L, Totp.stepAt(30L, 30), "30 是第 1 步的起点");
        assertEquals(1L, Totp.stepAt(59L, 30));
        assertEquals(2L, Totp.stepAt(60L, 30));
    }

    @Test
    @DisplayName("★ 校验：当前步命中，且返回的时间步可直接用于防重放")
    void 校验命中并返回步号() {
        String code = Totp.generate(RFC_SECRET_BASE32, 59L);
        long step = Totp.match(RFC_SECRET_BASE32, code, 59L, 30, 6, 1);
        assertEquals(Totp.stepAt(59L, 30), step, "命中时应返回该码所属的时间步");
    }

    @Test
    @DisplayName("★ 校验容差：±1 步（前后 30 秒）应通过，±2 步应拒绝")
    void 校验容差窗口() {
        String code = Totp.generate(RFC_SECRET_BASE32, 120L); // 属于步 4（覆盖 120..149）
        assertEquals(4L, Totp.match(RFC_SECRET_BASE32, code, 120L, 30, 6, 1), "同一步");
        assertEquals(4L, Totp.match(RFC_SECRET_BASE32, code, 149L, 30, 6, 1), "仍属同一步");
        assertEquals(4L, Totp.match(RFC_SECRET_BASE32, code, 150L, 30, 6, 1), "服务端已进下一步（+1 步）仍接受");
        assertEquals(4L, Totp.match(RFC_SECRET_BASE32, code, 90L, 30, 6, 1), "服务端还在上一步（-1 步）仍接受");
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, code, 180L, 30, 6, 1), "+2 步应拒绝");
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, code, 60L, 30, 6, 1), "-2 步应拒绝");
    }

    @Test
    @DisplayName("窗口步数设为 0 时退化为严格相等（不留任何时间宽容）")
    void 窗口为零时严格() {
        String code = Totp.generate(RFC_SECRET_BASE32, 120L);
        assertEquals(4L, Totp.match(RFC_SECRET_BASE32, code, 120L, 30, 6, 0));
        assertEquals(4L, Totp.match(RFC_SECRET_BASE32, code, 149L, 30, 6, 0), "同一步内仍接受");
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, code, 150L, 30, 6, 0), "跨步即拒绝");
    }

    @Test
    @DisplayName("校验：输入带空格/连字符能通过，位数或字符不对直接判失败")
    void 校验输入归一化() {
        String code = Totp.generate(RFC_SECRET_BASE32, 59L); // 287082
        assertEquals(1L, Totp.match(RFC_SECRET_BASE32, " 287 082 ", 59L, 30, 6, 0), "用户按 3+3 分组输入");
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, "28708", 59L, 30, 6, 0), "位数不足");
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, "2870829", 59L, 30, 6, 0), "位数过多");
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, "28708a", 59L, 30, 6, 0), "含非数字");
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, null, 59L, 30, 6, 0), "null");
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, "", 59L, 30, 6, 0), "空串");
    }

    @Test
    @DisplayName("校验：过期的码与写错的码都要拒绝")
    void 校验拒绝错误码() {
        // 主断言用「同一密钥、不同时间步」构造，不含任何概率成分：
        // 步 1 的码在步 4 提交（相隔 3 步、超出 ±1 容差）必须拒绝。
        String stepOne = Totp.generate(RFC_SECRET_BASE32, 59L);
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, stepOne, 149L, 30, 6, 1),
                "过期码（相隔 3 步）必须拒绝");
        // 近邻码：6 位码共 100 万种取值，这里断言具体的两个不命中
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, "287081", 59L, 30, 6, 0), "差一位");
        assertEquals(-1L, Totp.match(RFC_SECRET_BASE32, "287083", 59L, 30, 6, 0), "差一位（另一侧）");
    }

    @Test
    @DisplayName("密钥错误时同一时刻的码必然不同（否则说明密钥根本没参与运算）")
    void 不同密钥产出不同码() {
        String other = Totp.randomSecret();
        assertFalse(Totp.generate(RFC_SECRET_BASE32, 59L).equals(Totp.generate(other, 59L)));
    }

    @Test
    @DisplayName("随机密钥：160 bit、可解码、两次不同")
    void 随机密钥() {
        String a = Totp.randomSecret();
        String b = Totp.randomSecret();
        assertNotNull(a);
        assertEquals(32, a.length(), "20 字节应编成 32 个 Base32 字符");
        assertEquals(20, Base32.decode(a).length);
        assertFalse(a.equals(b));
    }

    @Test
    @DisplayName("otpauth 链接：格式与关键参数（认证器扫码认的就是这几个字段）")
    void otpauth链接() {
        String uri = Totp.otpauthUri("多多宠物商店", "admin@duoduo.test", RFC_SECRET_BASE32);
        assertTrue(uri.startsWith("otpauth://totp/"), uri);
        assertTrue(uri.contains("secret=" + RFC_SECRET_BASE32));
        assertTrue(uri.contains("issuer="));
        assertTrue(uri.contains("algorithm=SHA1"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
        // 中文发行方必须被百分号编码（空格用 %20 而不是 +，见 Totp.urlEncode 的注释）
        assertFalse(uri.contains("多多宠物商店:"), "中文未编码会让部分 App 解析失败");
    }

    @Test
    @DisplayName("空密钥：生成与校验都应显式报错，而不是算出一个看起来正常的码")
    void 空密钥报错() {
        assertThrows(IllegalArgumentException.class, () -> Totp.generate("", 59L));
        assertThrows(IllegalArgumentException.class, () -> Totp.match("  ", "123456", 59L, 30, 6, 1));
    }
}

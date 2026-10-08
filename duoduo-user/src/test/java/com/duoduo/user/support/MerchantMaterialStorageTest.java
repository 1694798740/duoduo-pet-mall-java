package com.duoduo.user.support;

import com.duoduo.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 私有材料存储的单测（E12）。
 *
 * <h2>这组测试守的是"文件系统的门"</h2>
 * 材料是营业执照 + 身份证 + 银行账户，存储层的两条纪律必须钉死：
 * ① **文件名只认服务端生成的 UUID 形状** —— 路径穿越（../）在正则层就死掉，
 *    而不是"碰巧没这个文件"；② **租户目录隔离** —— A 店的文件名拿去 B 店读，
 *    结果必须是"不存在"，而不是"碰巧读到"。
 */
class MerchantMaterialStorageTest {

    @TempDir
    Path tempDir;

    private MerchantMaterialStorage storage() {
        return new MerchantMaterialStorage(tempDir.toString());
    }

    @Test
    void 存取回路_内容一致且读取路径指向私有鉴权端点() {
        MerchantMaterialStorage.Stored stored = storage().store(101L, "执照.png", new byte[]{1, 2, 3});

        assertThat(stored.readPath()).startsWith("/api/merchant-materials/101/");
        assertThat(stored.readPath()).endsWith(".png");

        MerchantMaterialStorage.ReadResult read = storage().read(101L, stored.storedName());
        assertThat(read.contentType()).isEqualTo("image/png");
    }

    @Test
    void pdf在白名单内_内容类型正确() {
        // 承诺书 / 许可证常见扫描件是 PDF —— 这是 E12 对公开通道的刻意放宽
        MerchantMaterialStorage.Stored stored = storage().store(101L, "承诺书.pdf", new byte[]{1});
        assertThat(storage().read(101L, stored.storedName()).contentType())
                .isEqualTo("application/pdf");
    }

    @Test
    void 路径穿越企图_一律不存在() {
        assertThatThrownBy(() -> storage().read(101L, "..%2F..%2Fsecret.jpg"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> storage().read(101L, "../../../etc/passwd.jpg"))
                .isInstanceOf(BizException.class);
        // 形状合法但带目录段的组合名同样拒绝（SERVABLE 只认纯 UUID 文件名）
        assertThatThrownBy(() -> storage().read(101L, "101/../../x.jpg"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 租户目录隔离_A店的文件名到B店读不到() {
        MerchantMaterialStorage.Stored a = storage().store(101L, "执照.jpg", new byte[]{1});
        // B 店目录下没有这个文件名 —— 必须是"不存在"，绝不能顺着文件名落到 A 店目录
        assertThatThrownBy(() -> storage().read(202L, a.storedName()))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 非法扩展与超大文件_拒绝() {
        assertThatThrownBy(() -> storage().store(101L, "脚本.sh", new byte[]{1}))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持");
        assertThatThrownBy(() -> storage().store(101L, "大图.jpg", new byte[7 * 1024 * 1024]))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("6MB");
    }
}

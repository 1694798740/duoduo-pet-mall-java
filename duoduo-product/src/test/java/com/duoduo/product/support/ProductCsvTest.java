package com.duoduo.product.support;

import com.duoduo.product.dto.ProductDtos.KvItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-17 · 商品 CSV 解析与序列化。
 *
 * <p>这些用例守的是「用户文件里的真实形状」：Excel 的 BOM、标题里的逗号、
 * 双引号转义、空行、公式注入。任何一条错了，表现都是「导入后字段错位 / 整批失败」，
 * 而错误信息通常不会指向真正的原因。
 */
class ProductCsvTest {

    @Test
    void 解析带BOM_引号逗号与双引号转义() {
        String text = "\uFEFF分组键,商品标题\r\n"
                + "1,\"猫粮, 2kg\"\r\n"
                + "2,\"他说\"\"好\"\"\"\r\n";

        List<ProductCsv.Row> rows = ProductCsv.parse(text);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).cells()).containsExactly("分组键", "商品标题");
        assertThat(rows.get(0).lineNo()).isEqualTo(1);
        assertThat(rows.get(1).cells()).containsExactly("1", "猫粮, 2kg");
        assertThat(rows.get(2).cells()).containsExactly("2", "他说\"好\"");
    }

    @Test
    void 空行被跳过但行号保持物理行号() {
        List<ProductCsv.Row> rows = ProductCsv.parse("h1,h2\n\n1,a\n\n2,b\n");

        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).lineNo()).isEqualTo(3);
        assertThat(rows.get(2).lineNo()).isEqualTo(5);
    }

    @Test
    void 引号内换行不切成两行() {
        List<ProductCsv.Row> rows = ProductCsv.parse("a,\"第一行\n第二行\"\n");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).cells().get(1)).isEqualTo("第一行\n第二行");
    }

    @Test
    void 未闭合引号_明确报错() {
        assertThatThrownBy(() -> ProductCsv.parse("a,\"没有结束\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未闭合");
    }

    @Test
    void 引号出现在字段中间_明确报错() {
        assertThatThrownBy(() -> ProductCsv.parse("a,b\"c\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("引号");
    }

    @Test
    void 公式注入_导出加单引号且导入还原() {
        assertThat(ProductCsv.cell("=HYPERLINK(\"http://evil\")"))
                .startsWith("\"'=HYPERLINK");
        assertThat(ProductCsv.unguard("'=HYPERLINK")).isEqualTo("=HYPERLINK");
        // 正常以负号开头的文本也会被保护；还原后原样返回
        assertThat(ProductCsv.unguard(ProductCsv.cell("-10 元装"))).isEqualTo("-10 元装");
        // 普通文本不受影响
        assertThat(ProductCsv.unguard("猫粮")).isEqualTo("猫粮");
    }

    @Test
    void 规格参数_KV往返且冒号与竖线不丢() {
        List<KvItem> items = List.of(
                new KvItem("适用阶段", "幼年:成年"),
                new KvItem("备注", "a|b\\c"));

        String encoded = ProductCsv.formatKv(items);
        List<KvItem> decoded = ProductCsv.parseKv(encoded, "规格参数");

        assertThat(decoded).containsExactly(
                new KvItem("适用阶段", "幼年:成年"),
                new KvItem("备注", "a|b\\c"));
    }

    @Test
    void 规格参数_缺少冒号时明确报错() {
        assertThatThrownBy(() -> ProductCsv.parseKv("净含量2kg", "规格参数"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数名:参数值");
    }

    @Test
    void 售价解析_最多两位小数且拒绝科学计数法() {
        assertThat(ProductCsv.parsePriceCents("99")).isEqualTo(9900L);
        assertThat(ProductCsv.parsePriceCents("99.5")).isEqualTo(9950L);
        assertThat(ProductCsv.parsePriceCents("99.50")).isEqualTo(9950L);
        assertThat(ProductCsv.formatCents(9950L)).isEqualTo("99.50");
        assertThatThrownBy(() -> ProductCsv.parsePriceCents("1e3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductCsv.parsePriceCents("1.234"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 表头校验_允许末尾空白列但拒绝错列() {
        assertThat(ProductCsv.matchesHeader(ProductCsv.COLUMNS)).isTrue();
        assertThat(ProductCsv.matchesHeader(List.of("分组键", "商品标题"))).isFalse();
        assertThatThrownBy(() -> ProductCsv.pad(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9",
                "10", "11", "12", "13", "14", "15", "16", "17")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列数超过模板");
    }
}

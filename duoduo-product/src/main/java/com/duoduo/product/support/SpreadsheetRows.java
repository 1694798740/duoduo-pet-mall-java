package com.duoduo.product.support;

import com.duoduo.common.web.XlsxReader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入文件的统一入口：CSV 与 xlsx 走同一个「行 + 物理行号」模型（2026-10-28）。
 *
 * <h2>为什么先转成行，而不是让每个导入各写一套 xlsx 解析</h2>
 * 商品 / 价格库存 / 类目 / 品牌四个导入的校验、分组、逐行结果完全一样，
 * 差别只在「文件怎么变成行」。把这一步收在这里，xlsx 就不会出现
 * 「商品导入能用、类目导入还是只认 CSV」这种半套能力。
 *
 * <h2>行号口径</h2>
 * xlsx 的行号取 Excel 实际行号（空行不参与导入但行号不被压缩），
 * CSV 沿用 {@link ProductCsv#parse} 的物理行号 —— 两条路径的报错都能直接定位。
 */
public final class SpreadsheetRows {

    private SpreadsheetRows() {
    }

    /**
     * 解析上传内容。
     *
     * @param content  CSV 文本，或 xlsx 的 Base64
     * @param fileName 原始文件名（可空；空时按内容前缀判断是不是 xlsx）
     * @throws IllegalArgumentException 文件不可解析 / 内容为空
     */
    public static List<ProductCsv.Row> parse(String content, String fileName) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("导入内容为空");
        }
        if (!XlsxReader.looksLikeXlsx(content, fileName)) {
            return ProductCsv.parse(content);
        }
        List<XlsxReader.SheetRow> sheetRows = XlsxReader.read(XlsxReader.decodeBase64(content));
        List<ProductCsv.Row> rows = new ArrayList<>(sheetRows.size());
        for (XlsxReader.SheetRow row : sheetRows) {
            rows.add(new ProductCsv.Row(row.lineNo(), row.cells()));
        }
        return rows;
    }

    /**
     * 上传内容的实际字节数（xlsx 按解码后的原始文件算，不按 Base64 膨胀后的长度）。
     *
     * <p>前端已经按文件大小拦过一次；服务端再按解码后的大小算，
     * 是因为 Base64 会让 2MB 的文件变成 2.7MB，按字符串长度判会误伤正常文件。
     */
    public static int byteSize(String content, String fileName) {
        if (content == null) {
            return 0;
        }
        if (XlsxReader.looksLikeXlsx(content, fileName)) {
            return XlsxReader.decodeBase64(content).length;
        }
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}

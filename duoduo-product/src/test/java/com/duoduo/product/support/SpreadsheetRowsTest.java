package com.duoduo.product.support;

import com.duoduo.common.web.XlsxReader;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * xlsx 导入路径的单元测试（2026-10-28 · 商品 xlsx 模板）。
 *
 * <p>为什么在单元测试里造 xlsx，而不是只靠冒烟：
 * 冒烟要验证「上传一个带真实 ID 的工作簿 → 改价成功」，得在 PowerShell 里改 zip、
 * 替换 sharedStrings，脚本本身比被测代码还复杂；这里直接用 POI 造文件，
 * 覆盖的是同一段 {@link SpreadsheetRows#parse}。冒烟只负责「模板能下载、接口存在」。
 */
class SpreadsheetRowsTest {

    @Test
    void parsesXlsxAndKeepsPhysicalRowNumbers() throws Exception {
        byte[] workbook;
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("商品导入");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("分组键");
            header.createCell(1).setCellValue("商品标题");
            // 第 2 行故意留空：空行不导入，但第 3 行的报错行号必须仍是 3
            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue("G1");
            data.createCell(1).setCellValue("冻干鸡肉粒");
            book.write(out);
            workbook = out.toByteArray();
        }

        String base64 = Base64.getEncoder().encodeToString(workbook);
        List<ProductCsv.Row> rows = SpreadsheetRows.parse(base64, "products.xlsx");

        assertEquals(2, rows.size(), "表头 + 数据行；第 2 行空行应被跳过");
        assertEquals(1, rows.get(0).lineNo());
        assertEquals(List.of("分组键", "商品标题"), rows.get(0).cells());
        assertEquals(3, rows.get(1).lineNo(), "行号必须等于 Excel 里看到的行号");
        assertEquals("G1", rows.get(1).cells().get(0));
        assertEquals("冻干鸡肉粒", rows.get(1).cells().get(1));
    }

    @Test
    void fallsBackToContentSniffingWhenFileNameMissing() throws Exception {
        byte[] workbook;
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            book.createSheet("s").createRow(0).createCell(0).setCellValue("名称");
            book.write(out);
            workbook = out.toByteArray();
        }
        String base64 = Base64.getEncoder().encodeToString(workbook);

        assertTrue(XlsxReader.looksLikeXlsx(base64, null), "文件名缺失时按 Base64 前缀识别 xlsx");
        assertEquals(1, SpreadsheetRows.parse(base64, null).size());
        assertEquals(workbook.length, SpreadsheetRows.byteSize(base64, "a.xlsx"),
                "字节数按解码后的文件算，而不是 Base64 字符串长度");
    }

    @Test
    void csvStillGoesThroughThePlainTextPath() {
        String csv = "\uFEFF分组键,商品标题\r\nG1,冻干鸡肉粒\r\n";
        List<ProductCsv.Row> rows = SpreadsheetRows.parse(csv, "products.csv");
        assertEquals(2, rows.size());
        assertEquals("G1", rows.get(1).cells().get(0));
        assertFalse(XlsxReader.looksLikeXlsx(csv, "products.csv"));
    }

    @Test
    void rejectsGarbageInsteadOfSplittingItAsText() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SpreadsheetRows.parse("UEsDBabc-not-a-real-zip", "broken.xlsx"));
        assertTrue(error.getMessage().contains("Excel"), "错误提示要指向 Excel 文件本身：" + error.getMessage());
    }
}

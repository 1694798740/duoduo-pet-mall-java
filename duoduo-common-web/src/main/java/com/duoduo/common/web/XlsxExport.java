package com.duoduo.common.web;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * xlsx 模板 / 导出（2026-10-28 · 第三批 · 商品 xlsx 模板）。
 *
 * <h2>为什么不用 CSV 就够了</h2>
 * 商品 16 列里有富文本详情与图集，CSV 里是一整行带引号的文本，Excel 打开后
 * 双击单元格才能看到内容，改错一个引号整行就错位。xlsx 的单元格边界由文件结构保证，
 * 对「一次填几十行、每行都有长文本」的模板更合适。
 *
 * <h2>两件必须做对的事</h2>
 * <ul>
 *   <li><b>所有单元格按字符串写</b>：19 位雪花 ID 按数字写会被 Excel 转成科学计数法并丢精度，
 *       价格按数字写会丢两位小数；字符串写入是唯一不改变用户输入的方式。</li>
 *   <li><b>表头冻结 + 列宽</b>：导入模板动辄 500 行、16 列，不冻结表头滚两屏就不知道
 *       自己在填哪一列。</li>
 * </ul>
 *
 * <p>数据量上限由调用方控制；本类用 {@link SXSSFWorkbook} 按行写盘，
 * 内存里只保留一个滑动窗口（200 行），不会把整张表读进内存。
 */
public final class XlsxExport {

    static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Excel 工作表名上限 31 字符，且不允许这些字符（写超了 POI 会直接抛异常） */
    private static final int MAX_SHEET_NAME = 31;
    private static final String INVALID_SHEET_CHARS = "[]:*?/\\";
    private static final int MAX_COLUMN_WIDTH = 50;
    private static final int MIN_COLUMN_WIDTH = 10;

    private XlsxExport() {
    }

    /** 写 xlsx 并结束响应（无说明页）。 */
    public static void write(HttpServletResponse response,
                             String filename,
                             String sheetName,
                             List<String> headers,
                             List<List<String>> rows) throws IOException {
        write(response, filename, sheetName, headers, rows, List.of());
    }

    /**
     * 写 xlsx 并结束响应。
     *
     * @param filename   文件名（不含扩展名；服务端补 .xlsx 并做 RFC 5987 编码）
     * @param sheetName  数据页名称
     * @param headers    表头（第一行）
     * @param rows       数据行（按行写出，不要求可重放）
     * @param notes      可选「说明」页，一行一条；用于写「ID 列请保持文本格式」这类口径，
     *                   不放在数据页里 —— 数据页多一行说明就会让用户的导入报「表头不符」
     */
    public static void write(HttpServletResponse response,
                             String filename,
                             String sheetName,
                             List<String> headers,
                             List<List<String>> rows,
                             List<String> notes) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        try {
            CellStyle headerStyle = headerStyle(workbook);
            Sheet sheet = workbook.createSheet(safeSheetName(sheetName));
            fillSheet(sheet, headers, rows, headerStyle);
            if (notes != null && !notes.isEmpty()) {
                fillNotes(workbook.createSheet("说明"), notes);
            }

            String encoded = URLEncoder.encode(filename + ".xlsx", StandardCharsets.UTF_8)
                    .replace("+", "%20");
            response.setContentType(CONTENT_TYPE);
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"template.xlsx\"; filename*=UTF-8''" + encoded);
            // 模板里可能带示例商品（含图片地址），不让代理缓存
            response.setHeader("Cache-Control", "no-store");
            workbook.write(response.getOutputStream());
            response.flushBuffer();
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    private static void fillSheet(Sheet sheet, List<String> headers, List<List<String>> rows,
                                  CellStyle headerStyle) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
            // 按表头长度估列宽：中文按 2 个字符宽算，上限 50；不调 autoSizeColumn ——
            // 它要渲染字体，服务端无头环境下慢且结果不稳定
            int width = Math.min(MAX_COLUMN_WIDTH,
                    Math.max(MIN_COLUMN_WIDTH, displayWidth(headers.get(i)) + 4));
            sheet.setColumnWidth(i, width * 256);
        }
        // 冻结首行：滚动时表头始终可见
        sheet.createFreezePane(0, 1);

        int rowIndex = 1;
        for (List<String> row : rows) {
            Row target = sheet.createRow(rowIndex++);
            for (int i = 0; i < row.size(); i++) {
                target.createCell(i).setCellValue(row.get(i) == null ? "" : row.get(i));
            }
        }
    }

    private static void fillNotes(Sheet sheet, List<String> notes) {
        sheet.setColumnWidth(0, 100 * 256);
        for (int i = 0; i < notes.size(); i++) {
            sheet.createRow(i).createCell(0).setCellValue(notes.get(i));
        }
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static int displayWidth(String text) {
        if (text == null) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return width;
    }

    /** 工作表名必须去掉 Excel 禁止字符并截断；空名用「数据」兜底 */
    private static String safeSheetName(String raw) {
        String name = raw == null ? "" : raw.trim();
        for (char invalid : INVALID_SHEET_CHARS.toCharArray()) {
            name = name.replace(String.valueOf(invalid), "");
        }
        if (name.isEmpty()) {
            name = "数据";
        }
        return name.length() > MAX_SHEET_NAME ? name.substring(0, MAX_SHEET_NAME) : name;
    }
}

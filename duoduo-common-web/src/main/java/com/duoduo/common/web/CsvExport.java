package com.duoduo.common.web;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 流式导出（2026-10-28 · 全站导入导出第一批）。
 *
 * <p>只做三件事，且每件都必须做对：
 * <ol>
 *   <li><b>UTF-8 BOM</b>：没有 BOM 时 Excel 会按本地编码打开中文，表现为整列乱码；</li>
 *   <li><b>转义</b>：字段里的逗号、双引号、换行按 RFC 4180 处理，否则一行会被拆成多列；</li>
 *   <li><b>公式注入防护</b>：以 {@code = + - @} 开头的单元格前加单引号 ——
 *       运营导出的内容里可能有用户填的标题，直接打开 Excel 会执行公式。</li>
 * </ol>
 *
 * <p>数据量上限由调用方控制（同步导出建议 ≤ 5 万行）；本类按行写出，不把整表读进内存。
 */
public final class CsvExport {

    private static final char BOM = '\uFEFF';

    private CsvExport() {
    }

    /**
     * 写出 CSV 并结束响应。
     *
     * @param response HTTP 响应
     * @param filename 文件名（不含扩展名；服务端自动补 .csv 并做 RFC 5987 编码）
     * @param headers  表头
     * @param rows     数据行（可流式 Iterable）
     */
    public static void write(HttpServletResponse response,
                             String filename,
                             List<String> headers,
                             Iterable<List<String>> rows) throws IOException {
        String encoded = URLEncoder.encode(filename + ".csv", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"export.csv\"; filename*=UTF-8''" + encoded);
        // 禁止代理缓存导出结果（含买家信息 / 资金数据）
        response.setHeader("Cache-Control", "no-store");

        PrintWriter writer = response.getWriter();
        writer.write(BOM);
        writeRow(writer, headers);
        for (List<String> row : rows) {
            writeRow(writer, row);
        }
        writer.flush();
    }

    private static void writeRow(PrintWriter writer, List<String> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escape(row.get(i)));
        }
        writer.write("\r\n");
    }

    /** RFC 4180 转义 + 公式注入防护（对每个单元格生效） */
    public static String escape(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                value = "'" + value;
            }
        }
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** 金额（分）→ 元字符串，固定两位；导出给财务看的是元，不是分 */
    public static String yuan(long cents) {
        return String.format("%.2f", cents / 100.0);
    }

    /** ISO 时间 → 北京时间字符串（导出给运营看，不保留 Z） */
    public static String beijingTime(java.time.LocalDateTime utc) {
        if (utc == null) {
            return "";
        }
        return utc.plusHours(8).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * ISO-8601 UTC 字符串（如 {@code 2026-10-27T05:00:00Z}）→ 北京时间。
     *
     * <p>视图层的时间一律是字符串（跨服务序列化口径统一），导出时不必再反序列化成
     * {@code LocalDateTime}；解析失败时原样返回，避免导出因一条脏时间整份失败。
     */
    public static String beijingTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            return java.time.Instant.parse(iso)
                    .atOffset(java.time.ZoneOffset.ofHours(8))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (java.time.format.DateTimeParseException ex) {
            return iso;
        }
    }

    /** 文件名时间戳（北京时间 {@code yyyyMMdd-HHmm}）：同名导出不会在下载目录里互相覆盖 */
    public static String stamp() {
        return java.time.LocalDateTime.now(java.time.ZoneOffset.ofHours(8))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
    }
}

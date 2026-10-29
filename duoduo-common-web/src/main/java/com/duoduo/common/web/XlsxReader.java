package com.duoduo.common.web;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 读取用户上传的 xlsx（2026-10-28 · 第三批 · 商品 xlsx 模板）。
 *
 * <h2>只读第一个工作表，且只取值不执行文件里的宏</h2>
 * 导入模板只有一个数据页；多页文件让用户猜「哪页算数」不如直接约定第一页。
 * 公式单元格取缓存值（{@link FormulaEvaluator}），不执行外部链接。
 *
 * <h2>行号必须等于 Excel 里的行号</h2>
 * 错误提示是「第 5 行：类目ID不能为空」—— 用户要能直接在第 5 行找到问题。
 * 因此空行被跳过、但行号保留（{@link SheetRow#lineNo()} = {@code rowNum + 1}）。
 *
 * <h2>大数字的坑</h2>
 * Excel 的数字精度只有 15 位，19 位雪花 ID 一旦被存成数字就永久丢精度。
 * 模板的 ID 列按文本写出，但用户从别处粘贴数字时 Excel 仍会转成数字。
 * 这里不替用户「修」—— 读出来交给业务层按「必须是数字」报错，
 * 总比静默截断成一个不存在的 ID 好。
 */
public final class XlsxReader {

    /** 上传文件上限；与业务层 2MB 校验同量级，这里只是防 zip 炸弹的最后一道 */
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    /** xlsx 的 zip 头 base64 一定以这几个字符开头（PK\x03\x04） */
    private static final String ZIP_BASE64_PREFIX = "UEsDB";

    private XlsxReader() {
    }

    /** 一行数据：行号从 1 开始，与 Excel 左侧行号一致 */
    public record SheetRow(int lineNo, List<String> cells) {
    }

    /**
     * 是否像 xlsx 内容。
     *
     * <p>优先按文件名判断（用户传了 {@code .xlsx} 就是 xlsx）；文件名缺失时按内容前缀兜底，
     * 这样老前端只传 {@code content} 也能走对分支。
     */
    public static boolean looksLikeXlsx(String content, String fileName) {
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) {
            return true;
        }
        if (name.endsWith(".csv")) {
            return false;
        }
        return looksLikeXlsx(content);
    }

    public static boolean looksLikeXlsx(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.startsWith(ZIP_BASE64_PREFIX);
    }

    /** Base64 → 原始字节；不是合法 Base64 时给出可展示的原因 */
    public static byte[] decodeBase64(String content) {
        String source = content == null ? "" : content.trim();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Excel 内容为空");
        }
        try {
            return Base64.getMimeDecoder().decode(source);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Excel 内容不是有效的 Base64，请重新选择文件");
        }
    }

    public static List<SheetRow> read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Excel 文件为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Excel 文件过大（上限 5MB），请拆分后导入");
        }
        Workbook workbook;
        try {
            workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("无法解析 Excel 文件（请确认为未加密、未损坏的 .xlsx）");
        }
        try (workbook) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel 里没有工作表");
            }
            return readFirstSheet(workbook);
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取 Excel 失败，请重新导出文件后重试");
        } catch (RuntimeException ex) {
            if (ex instanceof IllegalArgumentException) {
                // 上面「没有工作表」以及业务侧主动抛的提示原样传出
                throw ex;
            }
            throw new IllegalArgumentException("读取 Excel 失败，请重新导出文件后重试");
        }
    }

    private static List<SheetRow> readFirstSheet(Workbook workbook) {
        Sheet sheet = workbook.getSheetAt(0);
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        List<SheetRow> rows = new ArrayList<>();
        for (Row row : sheet) {
            int lastCell = row.getLastCellNum();
            if (lastCell <= 0) {
                continue;
            }
            List<String> cells = new ArrayList<>(lastCell);
            boolean blank = true;
            for (int index = 0; index < lastCell; index++) {
                Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
                if (value != null && !value.isBlank()) {
                    blank = false;
                }
                cells.add(value == null ? "" : value);
            }
            if (blank) {
                // 空行不参与导入，但行号仍按 Excel 实际行号计 —— 用户照着报错行号能找到位置
                continue;
            }
            rows.add(new SheetRow(row.getRowNum() + 1, List.copyOf(cells)));
        }
        return rows;
    }
}

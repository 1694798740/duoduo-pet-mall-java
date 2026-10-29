package com.duoduo.product.support;

import com.duoduo.product.dto.ProductDtos.KvItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 商家端商品 CSV 模板的解析与序列化（A-17）。
 *
 * <h2>为什么自己写 RFC 4180 解析，而不是用 {@link Csv}</h2>
 * {@code Csv.split} 是按逗号切库内 CSV 列的小工具，不处理引号、转义、换行 ——
 * 用它解析用户上传的文件，商品标题里出现一个英文逗号就会整行错位，
 * 而且错位后的表现是「字段值跑到别的列」，用户看不出原因。
 *
 * <h2>两件必须做的事</h2>
 * <ul>
 *   <li><b>UTF-8 BOM</b>：Excel 保存 CSV 默认带 BOM，不去掉会让表头第一列变成
 *       {@code \uFEFF分组键}，匹配表头时整批失败；</li>
 *   <li><b>公式注入防护</b>：以 {@code = + - @} 开头的单元格在 Excel 里会被当公式执行。
 *       导出时前置单引号，导入时再还原 —— 不防的话，一个标题为
 *       {@code =HYPERLINK(...)} 的商品就能让运营打开导出文件时触发外链。</li>
 * </ul>
 *
 * <h2>多值字段的编码</h2>
 * 图集用 {@code |} 分隔；规格参数 / 包装规格用 {@code 名:值} 表示一条，条间用 {@code |}。
 * 值里若出现 {@code |} 或 {@code :}，写入时反斜杠转义（{@code \|} / {@code \:}），
 * 解析时还原 —— 这样「适用阶段:全年龄」这类正常值不会被误切。
 */
public final class ProductCsv {

    /** 模板列（顺序即契约；改动必须同步前端说明与冒烟） */
    public static final List<String> COLUMNS = List.of(
            "分组键", "商品标题", "副标题", "类目ID", "品牌ID", "主图", "图集",
            "商品介绍", "商品详情", "富文本详情", "规格参数", "包装规格",
            "规格名", "售价", "库存", "规格图");

    public static final String HEADER = String.join(",", COLUMNS);

    /**
     * 模板的示例行（CSV 与 xlsx 模板共用同一份）。
     *
     * <p>类目 ID 故意写 1：模板不能替用户猜类目，否则「导入失败：类目不存在」
     * 会被当成模板坏了。用户导入前必须删除本行。
     */
    public static final List<String> TEMPLATE_EXAMPLE = List.of(
            "示例1", "示例商品（导入前请删除本行）", "示例副标题", "1", "",
            "/img/products/sample.svg", "", "示例商品介绍", "示例商品详情", "",
            "适用阶段:全年龄", "包装形式:袋装", "2kg", "99.00", "100", "");

    /** xlsx 模板「说明」页的文案（CSV 模板没有说明页，规则写在前端弹窗里） */
    public static final List<String> TEMPLATE_NOTES = List.of(
            "1. 一个商品有多个规格时，同一商品的所有规格行填同一个「分组键」。",
            "2. 「类目ID」必须换成自己类目树里的真实 ID（示例里的 1 通常不存在）。",
            "3. ID 列请保持文本格式：Excel 会把 19 位数字转成科学计数法并丢精度。",
            "4. 图集用 | 分隔；规格参数 / 包装规格用 名:值 表示一条，条间用 | 分隔。",
            "5. 单次最多 500 行 / 100 个商品，文件最大 2MB；示例行导入前请删除。");

    public static final int COL_GROUP = 0;
    public static final int COL_TITLE = 1;
    public static final int COL_SUBTITLE = 2;
    public static final int COL_CATEGORY = 3;
    public static final int COL_BRAND = 4;
    public static final int COL_MAIN_IMAGE = 5;
    public static final int COL_IMAGES = 6;
    public static final int COL_INTRO = 7;
    public static final int COL_DETAIL = 8;
    public static final int COL_DETAIL_HTML = 9;
    public static final int COL_SPEC_PARAMS = 10;
    public static final int COL_PACKAGING = 11;
    public static final int COL_SPEC_NAME = 12;
    public static final int COL_PRICE = 13;
    public static final int COL_STOCK = 14;
    public static final int COL_SKU_IMAGE = 15;

    public static final int COLUMN_COUNT = COLUMNS.size();

    private static final char BOM = '\uFEFF';

    private ProductCsv() {
    }

    /** 一行 CSV：{@code lineNo} 是文件里的物理行号（含被跳过的空行），错误提示直接引用它 */
    public record Row(int lineNo, List<String> cells) {
    }

    /**
     * 解析 CSV 文本。支持 BOM、CRLF / LF、引号包裹、双引号转义与引号内换行。
     *
     * <p>空行（所有单元格都是空白）被跳过，但行号计数不跳过 ——
     * 这样用户在 Excel 里看到的行号与报错行号一致。
     *
     * @throws IllegalArgumentException 引号未闭合 / 引号出现在字段中间
     */
    public static List<Row> parse(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("CSV 内容为空");
        }
        String source = text.charAt(0) == BOM ? text.substring(1) : text;
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int line = 1;
        int recordLine = 1;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
                continue;
            }
            if (c == '"') {
                if (cell.length() > 0) {
                    throw new IllegalArgumentException("第 " + line + " 行的引号必须出现在字段开头");
                }
                inQuotes = true;
                continue;
            }
            if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
                continue;
            }
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                addRow(rows, recordLine, cells);
                cells.clear();
                cell.setLength(0);
                line++;
                recordLine = line;
                continue;
            }
            cell.append(c);
        }
        if (inQuotes) {
            throw new IllegalArgumentException("CSV 存在未闭合的引号（从第 " + recordLine + " 行开始）");
        }
        if (!cells.isEmpty() || cell.length() > 0) {
            cells.add(cell.toString());
            addRow(rows, recordLine, cells);
        }
        return rows;
    }

    private static void addRow(List<Row> rows, int lineNo, List<String> cells) {
        boolean blank = cells.stream().allMatch(value -> value == null || value.isBlank());
        if (!blank) {
            rows.add(new Row(lineNo, List.copyOf(cells)));
        }
    }

    /** 表头是否与模板一致（允许末尾多出空白列）。 */
    public static boolean matchesHeader(List<String> cells) {
        if (cells == null) {
            return false;
        }
        List<String> padded = pad(cells);
        for (int i = 0; i < COLUMN_COUNT; i++) {
            if (!COLUMNS.get(i).equals(padded.get(i).trim())) {
                return false;
            }
        }
        for (int i = COLUMN_COUNT; i < cells.size(); i++) {
            if (cells.get(i) != null && !cells.get(i).isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** 补齐到模板列数；超过列数视为格式错误。 */
    public static List<String> pad(List<String> cells) {
        if (cells.size() > COLUMN_COUNT) {
            throw new IllegalArgumentException("列数超过模板（模板 " + COLUMN_COUNT + " 列，本行 " + cells.size() + " 列）");
        }
        List<String> padded = new ArrayList<>(cells);
        while (padded.size() < COLUMN_COUNT) {
            padded.add("");
        }
        return padded;
    }

    /** 写一个单元格：公式防护 + 必要时的引号转义。 */
    public static String cell(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.isEmpty() && isFormulaLead(value.charAt(0))) {
            value = "'" + value;
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    /** 写一整行（不含换行符）。 */
    public static String row(List<String> cells) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        return out.toString();
    }

    /** 还原导出时的公式防护（只对「单引号 + 危险字符」组合生效）。 */
    public static String unguard(String raw) {
        if (raw != null && raw.length() >= 2 && raw.charAt(0) == '\''
                && isFormulaLead(raw.charAt(1))) {
            return raw.substring(1);
        }
        return raw;
    }

    private static boolean isFormulaLead(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }

    /**
     * 解析「元」价格为整数分。
     *
     * <p>只接受普通十进制（最多两位小数）：{@code 1e3} 这种科学计数法在 Excel 里
     * 看起来像数字，但用户其实想表达的不是它，静默接受只会让价格变成意外的 1000 元。
     */
    public static Long parsePriceCents(String raw) {
        String text = trimToNull(raw);
        if (text == null) {
            return null;
        }
        if (!text.matches("\\d{1,7}(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("售价格式不正确（应为元，最多两位小数）：" + text);
        }
        return new BigDecimal(text).movePointRight(2).longValueExact();
    }

    /** 整数分 → 元（保留两位小数，便于 Excel 直接求和）。 */
    public static String formatCents(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    /** {@code 名:值|名:值} → KV 列表；空串返回 null（与 ProductContent.normalizeKv 的「没有」语义一致）。 */
    public static List<KvItem> parseKv(String raw, String label) {
        String text = trimToNull(raw);
        if (text == null) {
            return null;
        }
        List<KvItem> items = new ArrayList<>();
        for (String token : splitEscaped(text, '|')) {
            String entry = token.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int index = indexOfUnescaped(entry, ':');
            if (index <= 0) {
                throw new IllegalArgumentException(label + "格式应为「参数名:参数值」，实际为：" + entry);
            }
            String name = unescape(entry.substring(0, index)).trim();
            String value = unescape(entry.substring(index + 1)).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(label + "存在空的参数名");
            }
            items.add(new KvItem(name, value));
        }
        return items.isEmpty() ? null : items;
    }

    /** KV 列表 → {@code 名:值|名:值}。 */
    public static String formatKv(List<KvItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        List<String> tokens = new ArrayList<>(items.size());
        for (KvItem item : items) {
            if (item == null) {
                continue;
            }
            tokens.add(escape(item.name()) + ":" + escape(item.value()));
        }
        return String.join("|", tokens);
    }

    /** 图集：{@code |} 分隔；空 URL 被丢弃。 */
    public static List<String> parseImages(String raw) {
        String text = trimToNull(raw);
        if (text == null) {
            return List.of();
        }
        List<String> images = new ArrayList<>();
        for (String part : splitEscaped(text, '|')) {
            String url = unescape(part).trim();
            if (!url.isEmpty() && !images.contains(url)) {
                images.add(url);
            }
        }
        return images;
    }

    /** 图集 → {@code |} 分隔。 */
    public static String formatImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(images.size());
        for (String image : images) {
            if (image != null && !image.isBlank()) {
                parts.add(escape(image.trim()));
            }
        }
        return String.join("|", parts);
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        return text.isEmpty() ? null : text;
    }

    private static String escape(String raw) {
        String value = raw == null ? "" : raw;
        return value.replace("\\", "\\\\").replace("|", "\\|").replace(":", "\\:");
    }

    private static String unescape(String raw) {
        if (raw == null || raw.indexOf('\\') < 0) {
            return raw == null ? "" : raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                out.append(raw.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static List<String> splitEscaped(String raw, char separator) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                current.append(c).append(raw.charAt(++i));
                continue;
            }
            if (c == separator) {
                out.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        out.add(current.toString());
        return out;
    }

    private static int indexOfUnescaped(String raw, char target) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                i++;
                continue;
            }
            if (c == target) {
                return i;
            }
        }
        return -1;
    }
}

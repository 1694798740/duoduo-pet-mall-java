package com.duoduo.product.service;

import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.SellerProductCreateRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductCsvView;
import com.duoduo.product.dto.ProductDtos.SellerProductImportItemView;
import com.duoduo.product.dto.ProductDtos.SellerProductImportRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductImportResultView;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.dto.ProductDtos.SellerSkuInput;
import com.duoduo.product.dto.ProductDtos.SellerSkuPriceStockRequest;
import com.duoduo.product.dto.ProductDtos.SkuPriceStockInput;
import com.duoduo.product.support.ProductCsv;
import com.duoduo.product.support.SpreadsheetRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家端商品 CSV 导入（A-17）。
 *
 * <h2>为什么单独一个 Service，而不是塞进 {@link SellerProductService}</h2>
 * 导入是「解析文件 + 逐组调用新建」，而新建本身带 {@code @Transactional}。
 * 如果导入循环写在 SellerProductService 内部，{@code this.create(...)} 是自调用，
 * 不经过 Spring 代理 —— 事务不会生效，一个分组插入 SPU 成功、插入 SKU 失败时
 * 会留下一件没有规格的残缺商品。放在另一个 Bean 里调用，create 才会真正走代理，
 * 每个分组各自一个事务：一组失败只回滚这一组，不影响已成功的组。
 *
 * <h2>部分成功是正常结果</h2>
 * 导入 50 个商品，其中 3 个类目 ID 写错 —— 不应该让另外 47 个也白导。
 * 因此返回逐组结果，HTTP 永远是 200，成败看 {@code success}。
 * 只有「文件本身不可解析 / 表头不对 / 超行数」才整体 4xx。
 */
@Service
public class SellerProductCsvService {

    private static final Logger log = LoggerFactory.getLogger(SellerProductCsvService.class);

    /** 文件上限：2MB 纯文本已经能装下数万行；再大说明用户把图片二进制塞进来了 */
    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;
    /** 单次最多 500 个规格行（不是商品数）：够日常批量上新，又不至于让同步接口跑成批处理 */
    private static final int MAX_DATA_ROWS = 500;
    /** 单次最多 100 个商品分组 */
    private static final int MAX_GROUPS = 100;

    private final SellerProductService sellerProductService;

    public SellerProductCsvService(SellerProductService sellerProductService) {
        this.sellerProductService = sellerProductService;
    }

    public SellerProductCsvView exportCsv() {
        return sellerProductService.exportCsv();
    }

    public SellerProductCsvView importTemplate() {
        return sellerProductService.importTemplate();
    }

    /**
     * 导入商品文件（CSV 文本或 xlsx Base64）。非卖家在解析之前就被拦下（403），
     * 避免「文件格式正确却整批失败」的误导。
     */
    public SellerProductImportResultView importCsv(SellerProductImportRequest request) {
        sellerProductService.requireSellerAccess();
        String content = request == null ? null : request.content();
        String fileName = request == null ? null : request.fileName();
        if (content == null || content.isBlank()) {
            throw BizException.validation("导入内容为空，请选择 CSV 或 xlsx 文件");
        }

        List<ProductCsv.Row> rows;
        try {
            if (SpreadsheetRows.byteSize(content, fileName) > MAX_CONTENT_BYTES) {
                throw BizException.validation("文件过大（上限 2MB），请拆分后导入");
            }
            rows = SpreadsheetRows.parse(content, fileName);
        } catch (IllegalArgumentException ex) {
            throw BizException.validation(ex.getMessage());
        }
        if (rows.isEmpty()) {
            throw BizException.validation("文件内容为空");
        }
        if (!ProductCsv.matchesHeader(rows.get(0).cells())) {
            throw BizException.validation("文件表头与模板不一致，请先下载导入模板（"
                    + ProductCsv.COLUMN_COUNT + " 列，首列为「分组键」）");
        }

        List<ProductCsv.Row> dataRows = rows.subList(1, rows.size());
        if (dataRows.isEmpty()) {
            throw BizException.validation("文件只有表头，没有商品数据");
        }
        if (dataRows.size() > MAX_DATA_ROWS) {
            throw BizException.validation("单次最多导入 " + MAX_DATA_ROWS + " 行（当前 " + dataRows.size() + " 行）");
        }

        // 分组键 → 该商品的规格行；LinkedHashMap 保留文件里的商品顺序，结果与用户看到的一致
        Map<String, List<ProductCsv.Row>> groups = new LinkedHashMap<>();
        for (ProductCsv.Row row : dataRows) {
            List<String> cells = ProductCsv.pad(row.cells());
            String key = text(cells, ProductCsv.COL_GROUP);
            String groupKey = key.isEmpty() ? "__blank_" + row.lineNo() : key;
            groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(row);
        }
        if (groups.size() > MAX_GROUPS) {
            throw BizException.validation("单次最多导入 " + MAX_GROUPS + " 个商品（当前 " + groups.size() + " 个）");
        }

        List<SellerProductImportItemView> items = new ArrayList<>(groups.size());
        int success = 0;
        for (Map.Entry<String, List<ProductCsv.Row>> entry : groups.entrySet()) {
            List<ProductCsv.Row> groupRows = entry.getValue();
            ProductCsv.Row first = groupRows.get(0);
            List<String> firstCells = ProductCsv.pad(first.cells());
            String title = text(firstCells, ProductCsv.COL_TITLE);
            try {
                SellerProductCreateRequest createRequest = toCreateRequest(groupRows);
                SellerProductView created = sellerProductService.create(createRequest);
                success++;
                items.add(new SellerProductImportItemView(first.lineNo(),
                        text(firstCells, ProductCsv.COL_GROUP), true, created.id(), title, null));
            } catch (BizException ex) {
                items.add(new SellerProductImportItemView(first.lineNo(),
                        text(firstCells, ProductCsv.COL_GROUP), false, null, title, ex.getMessage()));
            } catch (IllegalArgumentException ex) {
                items.add(new SellerProductImportItemView(first.lineNo(),
                        text(firstCells, ProductCsv.COL_GROUP), false, null, title, ex.getMessage()));
            } catch (Exception ex) {
                log.warn("CSV 导入分组失败（首行 {}）：{}", first.lineNo(), ex.toString());
                items.add(new SellerProductImportItemView(first.lineNo(),
                        text(firstCells, ProductCsv.COL_GROUP), false, null, title,
                        "导入失败，请检查该商品内容后重试"));
            }
        }
        log.info("CSV 导入完成：分组 {}，成功 {}，失败 {}", groups.size(), success, groups.size() - success);
        return new SellerProductImportResultView(groups.size(), success, groups.size() - success, items);
    }

    // ---------------- 价格 / 库存导入（第三批 · 第 7 项） ----------------

    /** 价格库存模板列（顺序即契约；改动必须同步前端说明与冒烟） */
    public static final List<String> PRICE_STOCK_COLUMNS = List.of("商品ID", "规格ID", "价格(元)", "库存");

    /** xlsx 模板「说明」页：价格库存模板没有示例行，规则只能写在说明里 */
    public static final List<String> PRICE_STOCK_NOTES = List.of(
            "1. 每行一个规格：商品ID + 规格ID 必须是自己店铺里真实存在的 ID。",
            "2. 价格填元、最多两位小数；库存填非负整数。",
            "3. 价格和库存至少填一项，留空表示不改该列。",
            "4. 单次最多 500 行，文件最大 2MB。",
            "5. ID 列请保持文本格式：Excel 会把 19 位数字转成科学计数法并丢精度。");
    private static final int PRICE_STOCK_COLUMN_COUNT = PRICE_STOCK_COLUMNS.size();
    /** 与商品导入同口径：每次最多 500 行；更大的批量应走异步任务 */
    private static final int MAX_PRICE_STOCK_ROWS = 500;

    /**
     * 价格库存模板：只有表头，没有示例行。
     *
     * <p>商品导入模板可以带示例行（内容都是自己编的），价格库存模板不行 ——
     * 示例行必须写真实的 spuId/skuId 才可能成功，随便填一个只会让用户
     * 把「第 2 行失败」当成模板坏了。列名本身已经说明了单位与含义。
     */
    public SellerProductCsvView priceStockTemplate() {
        sellerProductService.requirePriceStockAccess();
        String content = "\uFEFF" + String.join(",", PRICE_STOCK_COLUMNS) + "\r\n";
        return new SellerProductCsvView("price-stock-import-template.csv", content, 0, 0);
    }

    /**
     * 导入价格 / 库存：逐行调用 {@link SellerProductService#updateSkuPriceStock}。
     *
     * <p><b>为什么逐行调用而不是自己写 UPDATE</b>：改价是一条带规则的写路径 ——
     * 归属、店员权限、价格上限、库存非负、运营代改第三方商品的工单门禁，
     * 以及改完后重算 {@code t_spu.min_price/max_price}。导入只是「另一个入口」，
     * 不是「另一套规则」；在任何一条上分叉，都会出现「页面改不了、表格能改进去」。
     *
     * <p>逐行而不是按商品合并：一行 = 一个规格的一次改动，逐行调用才能给出
     * 「第几行错、为什么错」的精确结果；部分成功不回滚（与商品导入一致）。
     *
     * <p>价格为元、最多两位小数；价格与库存至少填一项，留空表示不改该项。
     */
    public BatchDtos.Result importPriceStock(SellerProductImportRequest request) {
        sellerProductService.requirePriceStockAccess();
        String content = request == null ? null : request.content();
        String fileName = request == null ? null : request.fileName();
        if (content == null || content.isBlank()) {
            throw BizException.validation("导入内容为空，请选择 CSV 或 xlsx 文件");
        }

        List<ProductCsv.Row> rows;
        try {
            if (SpreadsheetRows.byteSize(content, fileName) > MAX_CONTENT_BYTES) {
                throw BizException.validation("文件过大（上限 2MB），请拆分后导入");
            }
            rows = SpreadsheetRows.parse(content, fileName);
        } catch (IllegalArgumentException ex) {
            throw BizException.validation(ex.getMessage());
        }
        if (rows.isEmpty()) {
            throw BizException.validation("文件内容为空");
        }
        if (!matchesPriceStockHeader(rows.get(0).cells())) {
            throw BizException.validation("文件表头与模板不一致，请先下载导入模板（"
                    + PRICE_STOCK_COLUMN_COUNT + " 列：" + String.join(" / ", PRICE_STOCK_COLUMNS) + "）");
        }
        List<ProductCsv.Row> dataRows = rows.subList(1, rows.size());
        if (dataRows.isEmpty()) {
            throw BizException.validation("文件只有表头，没有可导入的数据");
        }
        if (dataRows.size() > MAX_PRICE_STOCK_ROWS) {
            throw BizException.validation("单次最多导入 " + MAX_PRICE_STOCK_ROWS + " 行（当前 " + dataRows.size() + " 行）");
        }

        List<BatchDtos.Item> items = new ArrayList<>(dataRows.size());
        int success = 0;
        for (ProductCsv.Row row : dataRows) {
            String lineId = String.valueOf(row.lineNo());
            try {
                PriceStockRow parsed = priceStockRow(row);
                sellerProductService.updateSkuPriceStock(parsed.spuId(),
                        new SellerSkuPriceStockRequest(List.of(
                                new SkuPriceStockInput(parsed.skuId(), parsed.priceCents(), parsed.stock()))));
                success++;
                items.add(new BatchDtos.Item(lineId, true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(lineId, false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (IllegalArgumentException ex) {
                items.add(new BatchDtos.Item(lineId, false, "VALIDATION_ERROR", ex.getMessage()));
            } catch (Exception ex) {
                log.warn("价格库存导入第 {} 行失败：{}", row.lineNo(), ex.toString());
                items.add(new BatchDtos.Item(lineId, false, "INTERNAL_ERROR", "导入失败，请核对该行后重试"));
            }
        }
        log.info("价格库存导入完成：{} 行，成功 {}，失败 {}", dataRows.size(), success, dataRows.size() - success);
        return new BatchDtos.Result(dataRows.size(), success, dataRows.size() - success, items, List.of());
    }

    /** 解析后的一行：价格与库存都可为 null（空 = 不改该项），但不会同时为 null。 */
    private record PriceStockRow(Long spuId, Long skuId, Long priceCents, Integer stock) {
    }

    /** 价格库存表头校验：允许末尾多出空白列，与商品导入同一口径。 */
    private boolean matchesPriceStockHeader(List<String> cells) {
        if (cells == null) {
            return false;
        }
        for (int i = 0; i < PRICE_STOCK_COLUMN_COUNT; i++) {
            String value = i < cells.size() ? cells.get(i) : "";
            if (!PRICE_STOCK_COLUMNS.get(i).equals(value == null ? "" : value.trim())) {
                return false;
            }
        }
        for (int i = PRICE_STOCK_COLUMN_COUNT; i < cells.size(); i++) {
            if (cells.get(i) != null && !cells.get(i).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private PriceStockRow priceStockRow(ProductCsv.Row row) {
        List<String> cells = row.cells();
        int lineNo = row.lineNo();
        if (cells.size() > PRICE_STOCK_COLUMN_COUNT) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：列数超过模板（应为 "
                    + PRICE_STOCK_COLUMN_COUNT + " 列，本行 " + cells.size() + " 列）");
        }
        Long spuId = parseId(text(cells, 0), "商品ID", lineNo);
        Long skuId = parseId(text(cells, 1), "规格ID", lineNo);
        String priceText = text(cells, 2);
        String stockText = text(cells, 3);
        if (priceText.isEmpty() && stockText.isEmpty()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：价格和库存至少填写一项（不修改的列留空）");
        }
        Long priceCents = null;
        if (!priceText.isEmpty()) {
            try {
                priceCents = ProductCsv.parsePriceCents(priceText);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("第 " + lineNo + " 行：" + ex.getMessage());
            }
        }
        Integer stock = null;
        if (!stockText.isEmpty()) {
            try {
                stock = Integer.valueOf(stockText);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("第 " + lineNo + " 行：库存必须是整数（" + stockText + "）");
            }
            if (stock < 0) {
                throw new IllegalArgumentException("第 " + lineNo + " 行：库存不能为负数");
            }
        }
        return new PriceStockRow(spuId, skuId, priceCents, stock);
    }

    private static Long parseId(String raw, String field, int lineNo) {
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：" + field + "不能为空");
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：" + field + "必须是数字（" + raw + "）");
        }
        if (value <= 0) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：" + field + "必须是正整数（" + raw + "）");
        }
        return value;
    }

    /**
     * 把同一分组键下的多行规格聚合成一个新建请求。
     *
     * <p>同一分组键的商品级字段必须完全一致：不一致时**整组失败**而不是「取第一行、忽略其余」。
     * 后者会让用户以为修改生效了（例如他在第二行改了标题），实际写进去的是第一行的旧标题。
     */
    private SellerProductCreateRequest toCreateRequest(List<ProductCsv.Row> rows) {
        ProductCsv.Row first = rows.get(0);
        SpuFields expected = spuFields(ProductCsv.pad(first.cells()), first.lineNo());
        List<SellerSkuInput> skus = new ArrayList<>(rows.size());
        for (ProductCsv.Row row : rows) {
            List<String> cells = ProductCsv.pad(row.cells());
            SpuFields current = spuFields(cells, row.lineNo());
            if (!expected.equals(current)) {
                throw new IllegalArgumentException("第 " + row.lineNo() + " 行的商品字段与分组首行（第 "
                        + first.lineNo() + " 行）不一致；同一分组键下的标题/类目/图片等必须相同");
            }
            skus.add(skuOf(cells, row.lineNo()));
        }
        return new SellerProductCreateRequest(
                expected.title(),
                emptyToNull(expected.subtitle()),
                expected.categoryId(),
                expected.brandId(),
                emptyToNull(expected.mainImage()),
                ProductCsv.parseImages(expected.images()),
                emptyToNull(expected.detail()),
                emptyToNull(expected.intro()),
                emptyToNull(expected.detailHtml()),
                ProductCsv.parseKv(expected.specParams(), "规格参数"),
                ProductCsv.parseKv(expected.packaging(), "包装规格"),
                skus);
    }

    /** 商品级字段签名（用于同组一致性判定）；文本已去首尾空白，空串与 null 统一成 "" */
    private record SpuFields(
            String title,
            String subtitle,
            Long categoryId,
            Long brandId,
            String mainImage,
            String images,
            String intro,
            String detail,
            String detailHtml,
            String specParams,
            String packaging
    ) {
    }

    private SpuFields spuFields(List<String> cells, int lineNo) {
        String group = text(cells, ProductCsv.COL_GROUP);
        if (group.isEmpty()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：分组键不能为空（同一商品的规格行填同一个分组键）");
        }
        String categoryId = text(cells, ProductCsv.COL_CATEGORY);
        if (categoryId.isEmpty()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：类目ID不能为空");
        }
        String brandId = text(cells, ProductCsv.COL_BRAND);
        return new SpuFields(
                text(cells, ProductCsv.COL_TITLE),
                text(cells, ProductCsv.COL_SUBTITLE),
                parseLong(categoryId, "类目ID", lineNo),
                brandId.isEmpty() ? null : parseLong(brandId, "品牌ID", lineNo),
                text(cells, ProductCsv.COL_MAIN_IMAGE),
                text(cells, ProductCsv.COL_IMAGES),
                text(cells, ProductCsv.COL_INTRO),
                text(cells, ProductCsv.COL_DETAIL),
                text(cells, ProductCsv.COL_DETAIL_HTML),
                text(cells, ProductCsv.COL_SPEC_PARAMS),
                text(cells, ProductCsv.COL_PACKAGING));
    }

    private SellerSkuInput skuOf(List<String> cells, int lineNo) {
        String price = text(cells, ProductCsv.COL_PRICE);
        if (price.isEmpty()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：售价不能为空");
        }
        Long cents;
        try {
            cents = ProductCsv.parsePriceCents(price);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：" + ex.getMessage());
        }
        String stockText = text(cells, ProductCsv.COL_STOCK);
        int stock;
        if (stockText.isEmpty()) {
            stock = 0;
        } else {
            try {
                stock = Integer.parseInt(stockText);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("第 " + lineNo + " 行：库存必须是整数");
            }
        }
        return new SellerSkuInput(
                emptyToNull(text(cells, ProductCsv.COL_SPEC_NAME)),
                cents,
                stock,
                emptyToNull(text(cells, ProductCsv.COL_SKU_IMAGE)));
    }

    private static String text(List<String> cells, int index) {
        String raw = index < cells.size() ? cells.get(index) : null;
        String value = ProductCsv.unguard(raw);
        return value == null ? "" : value.trim();
    }

    private static Long parseLong(String raw, String field, int lineNo) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("第 " + lineNo + " 行：" + field + "必须是数字（" + raw + "）");
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}

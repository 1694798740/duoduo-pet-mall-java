package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.BrandSaveRequest;
import com.duoduo.product.dto.ProductDtos.BrandView;
import com.duoduo.product.dto.ProductDtos.CatalogCsvView;
import com.duoduo.product.dto.ProductDtos.CatalogImportPlanItemView;
import com.duoduo.product.dto.ProductDtos.CatalogImportPlanView;
import com.duoduo.product.dto.ProductDtos.CatalogImportRequest;
import com.duoduo.product.dto.ProductDtos.CategoryNode;
import com.duoduo.product.dto.ProductDtos.CategorySaveRequest;
import com.duoduo.product.entity.CategoryEntity;
import com.duoduo.product.mapper.CategoryMapper;
import com.duoduo.product.support.ProductCsv;
import com.duoduo.product.support.SpreadsheetRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运营侧 · 类目 / 品牌 CSV 批量导入（第三批 · 第 8 项）。
 *
 * <h2>为什么必须先 dry-run</h2>
 * 类目 / 品牌是商品的公共主数据：写错一条「主粮」会波及上千件商品，
 * 而且改回来要逐件重新挂类目。商品导入错了顶多删掉几件商品；
 * 主数据导入错了是一次全局事故。因此接口拆成两步：
 * {@code POST .../import/dry-run} 只算「会发生什么」，{@code POST .../import} 才落库。
 *
 * <h2>匹配口径（不是按 ID，而是按业务键）</h2>
 * CSV 里没有主键列 —— 运营手里只有「名字」，没有雪花 ID。因此：
 * <ul>
 *   <li>类目：同一「父类目 + 名称」视为已存在 → 更新（排序号 / 图标），否则新建；</li>
 *   <li>品牌：同名视为已存在 → 更新（国家 / Logo / 故事），否则新建。</li>
 * </ul>
 * 更新时**空单元格 = 不改**：CSV 里留空是「这列没填」，不应该把已有的品牌故事清空。
 *
 * <h2>部分成功是正常结果</h2>
 * 与商品 / 价格库存导入一致：逐行独立处理，一行失败不影响其它行，HTTP 永远 200；
 * 只有文件级问题（空文件 / 表头不符 / 超行数 / 超 2MB）才整体 4xx。
 */
@Service
public class CatalogCsvService {

    private static final Logger log = LoggerFactory.getLogger(CatalogCsvService.class);

    /** 类目模板列（顺序即契约；改动必须同步前端说明与冒烟） */
    public static final List<String> CATEGORY_COLUMNS = List.of("名称", "父类目", "排序号", "图标");
    /** 品牌模板列 */
    public static final List<String> BRAND_COLUMNS = List.of("名称", "国家/地区", "Logo地址", "品牌故事");

    /** 与商品导入同口径：2MB 纯文本足够数百行；再大说明用户把图片二进制塞进来了 */
    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;
    /** 主数据一次导入 500 行已远超日常需要；更大的批量应先拆文件 */
    private static final int MAX_ROWS = 500;

    private static final int MAX_CATEGORY_NAME = 50;
    private static final int MAX_CATEGORY_ICON = 200;
    private static final int MAX_BRAND_NAME = 50;
    private static final int MAX_BRAND_COUNTRY = 30;
    private static final int MAX_BRAND_LOGO = 300;
    private static final int MAX_BRAND_STORY = 1000;

    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_SKIP = "SKIP";
    private static final String ACTION_ERROR = "ERROR";

    private final CategoryService categoryService;
    private final CategoryMapper categoryMapper;

    public CatalogCsvService(CategoryService categoryService, CategoryMapper categoryMapper) {
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
    }

    // ---------------- 模板 ----------------

    public CatalogCsvView categoryTemplate() {
        return new CatalogCsvView("category-import-template.csv",
                "\uFEFF" + String.join(",", CATEGORY_COLUMNS) + "\r\n");
    }

    public CatalogCsvView brandTemplate() {
        return new CatalogCsvView("brand-import-template.csv",
                "\uFEFF" + String.join(",", BRAND_COLUMNS) + "\r\n");
    }

    // ---------------- 类目：预演 / 导入 ----------------

    /**
     * 类目导入预演：逐行给出 CREATE / UPDATE / SKIP / ERROR，不写库。
     *
     * <p>文件在预演阶段走完全部校验（父类目存在、两级限制、长度、排序号格式），
     * 所以预演里没有 ERROR 行，正式导入也不会出现「转圈半天才报错」。
     */
    public CatalogImportPlanView checkCategoryImport(CatalogImportRequest request) {
        List<ProductCsv.Row> rows = parseRows(request == null ? null : request.content(),
                request == null ? null : request.fileName(), CATEGORY_COLUMNS, "类目");
        CategoryContext context = loadCategoryContext();
        List<CatalogImportPlanItemView> items = new ArrayList<>(rows.size());
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        for (ProductCsv.Row row : rows) {
            CatalogImportPlanItemView item;
            try {
                item = planCategoryRow(row, context, false);
            } catch (BizException ex) {
                item = errorItem(row, ex.getMessage());
            } catch (IllegalArgumentException ex) {
                item = errorItem(row, ex.getMessage());
            }
            items.add(item);
            switch (item.action()) {
                case ACTION_CREATE -> created++;
                case ACTION_UPDATE -> updated++;
                case ACTION_SKIP -> skipped++;
                default -> failed++;
            }
        }
        return new CatalogImportPlanView(rows.size(), created, updated, skipped, failed, items);
    }

    public BatchDtos.Result importCategories(CatalogImportRequest request) {
        List<ProductCsv.Row> rows = parseRows(request == null ? null : request.content(),
                request == null ? null : request.fileName(), CATEGORY_COLUMNS, "类目");
        CategoryContext context = loadCategoryContext();
        List<BatchDtos.Item> items = new ArrayList<>(rows.size());
        int success = 0;
        for (ProductCsv.Row row : rows) {
            String lineId = String.valueOf(row.lineNo());
            try {
                CatalogImportPlanItemView planned = planCategoryRow(row, context, true);
                success++;
                items.add(new BatchDtos.Item(lineId, true, null, planned.message()));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(lineId, false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (IllegalArgumentException ex) {
                items.add(new BatchDtos.Item(lineId, false, "VALIDATION_ERROR", ex.getMessage()));
            } catch (Exception ex) {
                log.warn("类目导入第 {} 行失败：{}", row.lineNo(), ex.toString());
                items.add(new BatchDtos.Item(lineId, false, "INTERNAL_ERROR", "导入失败，请检查该行内容后重试"));
            }
        }
        log.info("类目导入完成：共 {} 行，成功 {} 行", rows.size(), success);
        return new BatchDtos.Result(rows.size(), success, rows.size() - success, items, null);
    }

    /**
     * 单行决策（dry-run 与正式导入共用）。
     *
     * <p>{@code apply=false} 时只登记「本文件里将新建的类目」（id 为空），
     * 让后续行可以按名称引用它 —— 否则「先建父类目、再建子类目」这一份文件
     * 在预演里永远无法通过父类目校验，而正式导入却会成功，预演就失去意义。
     *
     * @throws IllegalArgumentException 行内容不合法（预演记 ERROR，正式导入记该行失败）
     */
    private CatalogImportPlanItemView planCategoryRow(ProductCsv.Row row, CategoryContext context, boolean apply) {
        CategoryRow parsed = categoryRow(row);
        ParentRef parent = resolveParent(parsed.parentRaw(), context);
        CategoryEntry existing = context.byPath.get(pathKey(parent.key(), parsed.name()));
        if (existing != null) {
            if (existing.lineNo() > 0) {
                return new CatalogImportPlanItemView(row.lineNo(), ACTION_SKIP, parsed.name(), null,
                        "本文件第 " + existing.lineNo() + " 行已创建同名类目，这里跳过");
            }
            String icon = parsed.icon().isEmpty() ? null : parsed.icon();
            boolean changed = (parsed.sortNo() != null && !parsed.sortNo().equals(existing.sortNo()))
                    || (icon != null && !icon.equals(existing.icon()));
            if (!changed) {
                return new CatalogImportPlanItemView(row.lineNo(), ACTION_SKIP, parsed.name(),
                        String.valueOf(existing.id()), "已存在且无变化，跳过");
            }
            if (apply) {
                categoryService.updateCategory(existing.id(),
                        new CategorySaveRequest(parsed.name(), parent.id(), parsed.sortNo(), icon));
            }
            return new CatalogImportPlanItemView(row.lineNo(), ACTION_UPDATE, parsed.name(),
                    String.valueOf(existing.id()), "更新已有类目 #" + existing.id());
        }

        int sortNo = parsed.sortNo() == null ? 0 : parsed.sortNo();
        String icon = parsed.icon().isEmpty() ? null : parsed.icon();
        if (apply) {
            CategoryNode created = categoryService.createCategory(
                    new CategorySaveRequest(parsed.name(), parent.id(), sortNo, icon));
            context.register(created.id(), created.name(), parent.key(), created.depth(), sortNo, icon);
            return new CatalogImportPlanItemView(row.lineNo(), ACTION_CREATE, parsed.name(),
                    String.valueOf(created.id()), "已新建类目 #" + created.id());
        }
        // 预演：登记一个「将新建」的占位，id 为空；子类目行可以按名称引用它
        // 深度：挂在一级类目下才是 2；父类目是「顶级」时自己就是 1 级
        int newDepth = "0".equals(parent.key()) ? 1 : 2;
        context.registerPending(parsed.name(), parent.key(), newDepth, sortNo, icon, row.lineNo());
        return new CatalogImportPlanItemView(row.lineNo(), ACTION_CREATE, parsed.name(), null,
                "将新建类目（父类目：" + parent.label() + "）");
    }

    private CategoryRow categoryRow(ProductCsv.Row row) {
        List<String> cells = row.cells();
        String name = cell(cells, 0);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("类目名称不能为空");
        }
        if (name.length() > MAX_CATEGORY_NAME) {
            throw new IllegalArgumentException("类目名称不能超过 " + MAX_CATEGORY_NAME + " 字");
        }
        String sortRaw = cell(cells, 2);
        Integer sortNo = null;
        if (!sortRaw.isEmpty()) {
            if (!sortRaw.matches("\\d{1,9}")) {
                throw new IllegalArgumentException("排序号必须是非负整数：" + sortRaw);
            }
            sortNo = Integer.parseInt(sortRaw);
        }
        String icon = cell(cells, 3);
        if (icon.length() > MAX_CATEGORY_ICON) {
            throw new IllegalArgumentException("图标不能超过 " + MAX_CATEGORY_ICON + " 字");
        }
        return new CategoryRow(row.lineNo(), name, cell(cells, 1), sortNo, icon);
    }

    /**
     * 解析「父类目」列：空 / 0 = 顶级；纯数字 = 父类目 ID；其余 = 父类目名称。
     *
     * <p>为什么同时支持名称：运营从 Excel 抄过来的是「主粮 / 零食」，
     * 不是 18 位雪花 ID；只收 ID 等于逼人去后台一个个查。
     * 名称只能指向**顶级类目**（两级树），并且本文件新建的父类目行必须写在前面。
     */
    private ParentRef resolveParent(String raw, CategoryContext context) {
        if (raw.isEmpty() || "0".equals(raw) || "顶级".equals(raw) || "（顶级）".equals(raw)) {
            return new ParentRef(0L, "0", "（顶级）");
        }
        if (raw.matches("\\d+")) {
            long id;
            try {
                id = Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("父类目 ID 格式不正确：" + raw);
            }
            CategoryEntry entry = context.byId.get(id);
            if (entry == null) {
                throw new IllegalArgumentException("父类目 ID " + id + " 不存在");
            }
            if (entry.depth() != 1) {
                throw new IllegalArgumentException("父类目「" + entry.name() + "」是二级类目，只支持两级类目");
            }
            return new ParentRef(entry.id(), String.valueOf(entry.id()), entry.name());
        }
        CategoryEntry entry = context.byPath.get(pathKey("0", raw));
        if (entry == null) {
            throw new IllegalArgumentException("父类目「" + raw + "」不存在；若在本文件新建父类目，请把父类目的行写在子类目之前");
        }
        if (entry.depth() != 1) {
            throw new IllegalArgumentException("父类目「" + entry.name() + "」是二级类目，只支持两级类目");
        }
        String childKey = entry.id() != null ? String.valueOf(entry.id()) : "new:" + entry.lineNo();
        return new ParentRef(entry.id(), childKey, entry.name());
    }

    private CategoryContext loadCategoryContext() {
        List<CategoryEntity> all = categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                .orderByAsc(CategoryEntity::getDepth)
                .orderByAsc(CategoryEntity::getSortNo));
        CategoryContext context = new CategoryContext();
        for (CategoryEntity entity : all) {
            long parentId = entity.getParentId() == null ? 0L : entity.getParentId();
            CategoryEntry entry = new CategoryEntry(entity.getId(), entity.getName(),
                    parentId == 0L ? "0" : String.valueOf(parentId),
                    entity.getDepth() == null ? 1 : entity.getDepth(),
                    entity.getSortNo(), entity.getIcon(), 0);
            context.byId.put(entity.getId(), entry);
            String key = pathKey(entry.parentKey(), entry.name());
            if (!context.byPath.containsKey(key)) {
                context.byPath.put(key, entry);
            }
        }
        return context;
    }

    // ---------------- 品牌：预演 / 导入 ----------------

    public CatalogImportPlanView checkBrandImport(CatalogImportRequest request) {
        List<ProductCsv.Row> rows = parseRows(request == null ? null : request.content(),
                request == null ? null : request.fileName(), BRAND_COLUMNS, "品牌");
        BrandContext context = loadBrandContext();
        List<CatalogImportPlanItemView> items = new ArrayList<>(rows.size());
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        for (ProductCsv.Row row : rows) {
            CatalogImportPlanItemView item;
            try {
                item = planBrandRow(row, context, false);
            } catch (BizException ex) {
                item = errorItem(row, ex.getMessage());
            } catch (IllegalArgumentException ex) {
                item = errorItem(row, ex.getMessage());
            }
            items.add(item);
            switch (item.action()) {
                case ACTION_CREATE -> created++;
                case ACTION_UPDATE -> updated++;
                case ACTION_SKIP -> skipped++;
                default -> failed++;
            }
        }
        return new CatalogImportPlanView(rows.size(), created, updated, skipped, failed, items);
    }

    public BatchDtos.Result importBrands(CatalogImportRequest request) {
        List<ProductCsv.Row> rows = parseRows(request == null ? null : request.content(),
                request == null ? null : request.fileName(), BRAND_COLUMNS, "品牌");
        BrandContext context = loadBrandContext();
        List<BatchDtos.Item> items = new ArrayList<>(rows.size());
        int success = 0;
        for (ProductCsv.Row row : rows) {
            String lineId = String.valueOf(row.lineNo());
            try {
                CatalogImportPlanItemView planned = planBrandRow(row, context, true);
                success++;
                items.add(new BatchDtos.Item(lineId, true, null, planned.message()));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(lineId, false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (IllegalArgumentException ex) {
                items.add(new BatchDtos.Item(lineId, false, "VALIDATION_ERROR", ex.getMessage()));
            } catch (Exception ex) {
                log.warn("品牌导入第 {} 行失败：{}", row.lineNo(), ex.toString());
                items.add(new BatchDtos.Item(lineId, false, "INTERNAL_ERROR", "导入失败，请检查该行内容后重试"));
            }
        }
        log.info("品牌导入完成：共 {} 行，成功 {} 行", rows.size(), success);
        return new BatchDtos.Result(rows.size(), success, rows.size() - success, items, null);
    }

    private CatalogImportPlanItemView planBrandRow(ProductCsv.Row row, BrandContext context, boolean apply) {
        BrandRow parsed = brandRow(row);
        BrandEntry existing = context.byName.get(normalize(parsed.name()));
        if (existing != null) {
            if (existing.lineNo() > 0) {
                return new CatalogImportPlanItemView(row.lineNo(), ACTION_SKIP, parsed.name(), null,
                        "本文件第 " + existing.lineNo() + " 行已创建同名品牌，这里跳过");
            }
            boolean changed = changed(parsed.country(), existing.country())
                    || changed(parsed.logo(), existing.logo())
                    || changed(parsed.story(), existing.story());
            if (!changed) {
                return new CatalogImportPlanItemView(row.lineNo(), ACTION_SKIP, parsed.name(),
                        String.valueOf(existing.id()), "已存在且无变化，跳过");
            }
            if (apply) {
                categoryService.updateBrand(existing.id(), new BrandSaveRequest(parsed.name(),
                        blankToNull(parsed.logo()), blankToNull(parsed.country()), blankToNull(parsed.story())));
            }
            return new CatalogImportPlanItemView(row.lineNo(), ACTION_UPDATE, parsed.name(),
                    String.valueOf(existing.id()), "更新已有品牌 #" + existing.id());
        }

        if (apply) {
            BrandView created = categoryService.createBrand(new BrandSaveRequest(parsed.name(),
                    blankToNull(parsed.logo()), blankToNull(parsed.country()), blankToNull(parsed.story())));
            context.byName.put(normalize(created.name()), new BrandEntry(created.id(), created.name(),
                    created.country(), created.logo(), created.story(), row.lineNo()));
            return new CatalogImportPlanItemView(row.lineNo(), ACTION_CREATE, parsed.name(),
                    String.valueOf(created.id()), "已新建品牌 #" + created.id());
        }
        context.byName.put(normalize(parsed.name()), new BrandEntry(null, parsed.name(),
                blankToNull(parsed.country()), blankToNull(parsed.logo()), blankToNull(parsed.story()), row.lineNo()));
        return new CatalogImportPlanItemView(row.lineNo(), ACTION_CREATE, parsed.name(), null, "将新建品牌");
    }

    private BrandRow brandRow(ProductCsv.Row row) {
        List<String> cells = row.cells();
        String name = cell(cells, 0);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("品牌名称不能为空");
        }
        if (name.length() > MAX_BRAND_NAME) {
            throw new IllegalArgumentException("品牌名称不能超过 " + MAX_BRAND_NAME + " 字");
        }
        String country = cell(cells, 1);
        if (country.length() > MAX_BRAND_COUNTRY) {
            throw new IllegalArgumentException("国家 / 地区不能超过 " + MAX_BRAND_COUNTRY + " 字");
        }
        String logo = cell(cells, 2);
        if (logo.length() > MAX_BRAND_LOGO) {
            throw new IllegalArgumentException("Logo 地址不能超过 " + MAX_BRAND_LOGO + " 字");
        }
        String story = cell(cells, 3);
        if (story.length() > MAX_BRAND_STORY) {
            throw new IllegalArgumentException("品牌故事不能超过 " + MAX_BRAND_STORY + " 字");
        }
        return new BrandRow(row.lineNo(), name, country, logo, story);
    }

    private BrandContext loadBrandContext() {
        BrandContext context = new BrandContext();
        for (BrandView view : categoryService.adminBrands()) {
            context.byName.putIfAbsent(normalize(view.name()),
                    new BrandEntry(view.id(), view.name(), view.country(), view.logo(), view.story(), 0));
        }
        return context;
    }

    // ---------------- 解析 / 校验 ----------------

    /**
     * 解析并做文件级校验（空 / 超 2MB / 表头不符 / 只有表头 / 超行数）。
     *
     * <p>CSV 与 xlsx 都先经 {@link SpreadsheetRows#parse} 变成「行 + 物理行号」；
     * 文件级校验只认行模型，因此类目 / 品牌在两种格式下的报错文案与行号完全一致。
     */
    private List<ProductCsv.Row> parseRows(String content, String fileName, List<String> columns, String label) {
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
        if (!matchesHeader(rows.get(0).cells(), columns)) {
            throw BizException.validation("文件表头与模板不一致，请先下载" + label + "导入模板（"
                    + columns.size() + " 列：" + String.join(" / ", columns) + "）");
        }
        List<ProductCsv.Row> dataRows = rows.subList(1, rows.size());
        if (dataRows.isEmpty()) {
            throw BizException.validation("文件只有表头，没有可导入的数据");
        }
        if (dataRows.size() > MAX_ROWS) {
            throw BizException.validation("单次最多导入 " + MAX_ROWS + " 行（当前 " + dataRows.size() + " 行）");
        }
        return dataRows;
    }

    private static boolean matchesHeader(List<String> cells, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            String value = i < cells.size() ? cells.get(i) : "";
            if (!columns.get(i).equals(value.trim())) {
                return false;
            }
        }
        for (int i = columns.size(); i < cells.size(); i++) {
            if (!cells.get(i).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String cell(List<String> cells, int index) {
        return index < cells.size() && cells.get(index) != null ? cells.get(index).trim() : "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 空单元格 = 不改：只有填了值才参与「是否有变化」的判断。 */
    private static boolean changed(String cell, String existing) {
        if (cell.isEmpty()) {
            return false;
        }
        return !cell.equals(existing == null ? "" : existing);
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String pathKey(String parentKey, String name) {
        return parentKey + '|' + normalize(name);
    }

    private CatalogImportPlanItemView errorItem(ProductCsv.Row row, String message) {
        String name = row.cells().isEmpty() ? "" : cell(row.cells(), 0);
        return new CatalogImportPlanItemView(row.lineNo(), ACTION_ERROR, name, null,
                message == null ? "该行内容不合法" : message);
    }

    // ---------------- 内部结构 ----------------

    private record CategoryRow(int lineNo, String name, String parentRaw, Integer sortNo, String icon) {
    }

    /** 解析后的父类目：{@code key} 是子类目匹配键的前缀（顶级为 {@code 0}，新建但尚无 ID 的为 {@code new:行号}）。 */
    private record ParentRef(Long id, String key, String label) {
    }

    /**
     * 已存在的类目（{@code lineNo = 0}）或本文件刚新建 / 将新建的类目（{@code lineNo > 0}）。
     * 后者的 id 在预演阶段为空 —— 它的作用只是让后续行按名称引用到这个父类目。
     */
    private record CategoryEntry(Long id, String name, String parentKey, int depth,
                                 Integer sortNo, String icon, int lineNo) {
    }

    private static final class CategoryContext {
        private final Map<Long, CategoryEntry> byId = new HashMap<>();
        private final Map<String, CategoryEntry> byPath = new HashMap<>();

        private void register(Long id, String name, String parentKey, int depth, Integer sortNo, String icon) {
            CategoryEntry entry = new CategoryEntry(id, name, parentKey, depth, sortNo, icon, 0);
            byId.put(id, entry);
            byPath.put(pathKey(parentKey, name), entry);
        }

        private void registerPending(String name, String parentKey, int depth,
                                     Integer sortNo, String icon, int lineNo) {
            CategoryEntry entry = new CategoryEntry(null, name, parentKey, depth, sortNo, icon, lineNo);
            byPath.put(pathKey(parentKey, name), entry);
        }
    }

    private record BrandRow(int lineNo, String name, String country, String logo, String story) {
    }

    private record BrandEntry(Long id, String name, String country, String logo, String story, int lineNo) {
    }

    private static final class BrandContext {
        private final Map<String, BrandEntry> byName = new HashMap<>();
    }
}

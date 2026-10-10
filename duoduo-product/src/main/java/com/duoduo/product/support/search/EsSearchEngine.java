package com.duoduo.product.support.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonData;
import com.duoduo.product.dto.ProductDtos.ListingQuery;
import com.duoduo.product.dto.ProductDtos.ProductQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.duoduo.common.tenant.TenantContext;
import org.springframework.util.StringUtils;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 检索查询引擎（A10）。
 *
 * <p>只负责「把查询条件翻译成 ES 查询并取回 <b>id 顺序页</b>」——
 * 卡片装配仍回 MySQL（selectBatchIds），<b>权威数据永远只有库里一份</b>：
 * ES 是加速层不是数据源，索引哪怕丢光也不损失任何事实。
 *
 * <p>筛选条件的<strong>全量平移</strong>（与 MySQL 版逐一对应）是硬要求：
 * 两套实现只允许差「谁先跑」，不允许差「谁能查出来」——否则开 ES 和关 ES
 * 看到两个商品世界，比慢更难排查。中文用 pet_text 分析器（standard 分词 → 中文逐字切分；
 * 同义词在**应用侧**替换，索引侧不挂 synonym filter，见 {@link SearchSynonyms}），
 * 纯 ASCII / 中文查询词额外拼一路 pinyin 字段匹配（验收①拼音容错）。
 * 召回与排序共同保证「精确命中排最前」，见 {@link #buildKeywordShould} 与 {@link #applyProductSort}。
 */
@Component
@ConditionalOnProperty(name = "duoduo.search.enabled", havingValue = "true")
public class EsSearchEngine {

    /**
     * ES 命中页：id 已按请求的排序排好，total 为过滤后的总数。
     *
     * @param looseFallback 本次结果是否来自「严格召回为零后的逐字兜底」——
     *                      调用方据此打日志/埋点，用于持续观察「哪些词搜不到精确结果」。
     */
    public record EsHits(List<Long> ids, long total, boolean looseFallback) {
    }

    private final ElasticsearchClient es;
    private final SearchSynonyms synonyms;

    public EsSearchEngine(ElasticsearchClient es, SearchSynonyms synonyms) {
        this.es = es;
        this.synonyms = synonyms;
    }

    /** 商品检索（筛选全量平移自 ProductService.search 的 MySQL 实现）。ES 异常向上抛，由调用方降级 */
    public EsHits searchProducts(ProductQuery q, List<Long> expandedCategoryIds, int pageNo, int pageSize) throws Exception {
        List<Object> filters = new ArrayList<>();
        filters.add(term("docType", SearchIndexer.TYPE_PRODUCT));
        filters.add(term("status", "ON_SALE"));   // 与 MySQL 的 status='ON_SALE' 同口径
        // E14：审核维度必须一起过滤 —— ES 是**独立于 MySQL 的第二个可见性出口**，
        // 只在 MySQL 侧加条件会让「开 ES 就漏、关 ES 才对」这种最难复现的形态出现
        // （检索命中未过审商品，详情页却 404）。两边条件必须逐字对应。
        filters.add(term("auditStatus", "APPROVED"));
        addTenantFilter(filters);
        if (expandedCategoryIds != null && !expandedCategoryIds.isEmpty()) {
            filters.add(termsLong("categoryId", expandedCategoryIds));
        }
        if (q.brandId() != null) {
            filters.add(term("brandId", q.brandId()));
        }
        if (StringUtils.hasText(q.supplyType())) {
            filters.add(term("supplyType", q.supplyType().trim()));
        }
        if (q.minPrice() != null) {
            filters.add(range("maxPrice", q.minPrice(), null));   // 区间相交语义与 MySQL 一致
        }
        if (q.maxPrice() != null) {
            filters.add(range("minPrice", null, q.maxPrice()));
        }
        if (StringUtils.hasText(q.species())) {
            filters.add(termsText("speciesList", List.of(q.species().trim())));
        }
        if (StringUtils.hasText(q.lifeStage())) {
            filters.add(termsText("lifeStageList", List.of(q.lifeStage().trim())));
        }
        if (q.inStockOnly()) {
            filters.add(term("inStock", true));
        }
        return searchStrictThenLoose(filters, q.keyword(),
                (pageNo - 1) * pageSize, pageSize, applyProductSort(q.sort()));
    }

    /** 闲置检索（筛选平移自 ListingService.search 的 MySQL 实现，可见性同 visibleWrapper） */
    public EsHits searchListings(ListingQuery q, int pageNo, int pageSize) throws Exception {
        List<Object> filters = new ArrayList<>();
        filters.add(term("docType", SearchIndexer.TYPE_LISTING));
        filters.add(term("status", "ON_SALE"));
        filters.add(term("auditStatus", "APPROVED"));
        addTenantFilter(filters);
        if (StringUtils.hasText(q.city())) {
            filters.add(term("city", q.city().trim()));
        }
        if (StringUtils.hasText(q.species())) {
            filters.add(term("species", q.species().trim()));
        }
        if (StringUtils.hasText(q.conditionLevel())) {
            filters.add(term("conditionLevel", q.conditionLevel().trim()));
        }
        if (q.minPrice() != null) {
            filters.add(range("minPrice", q.minPrice(), null));
        }
        if (q.maxPrice() != null) {
            filters.add(range("maxPrice", null, q.maxPrice()));
        }
        return searchStrictThenLoose(filters, q.keyword(),
                (pageNo - 1) * pageSize, pageSize, applyListingSort(q.sort()));
    }

    /**
     * 两段式检索：**先严格召回；只有严格召回一条都没有时，才放宽到逐字兜底**。
     *
     * <p>为什么不是「一次查询里把兜底也带上、靠 boost 排后面」：那样第一屏仍会混进
     * 噪音（搜「喂食器」会带回含「食」的零食/罐头）。用户对搜索的第一判断就是第一屏，
     * 宁可给它 1 条精确结果、也不要 4 条里只对 1 条。
     *
     * <p>代价是零结果时多一次往返 —— 只在「严格命中 0 条」这个低频分支发生，
     * 换来的是「零结果 → 给相关推荐」的兜底体验（否则用户什么都搜不到）。
     */
    private EsHits searchStrictThenLoose(List<Object> filters, String keyword, int from, int size,
                                         List<SortOptions> sorts) throws Exception {
        EsHits strict = run(buildRequest(filters, buildKeywordClauses(keyword, false), from, size, sorts));
        if (strict.total() > 0 || !StringUtils.hasText(keyword)) {
            return strict;
        }
        EsHits loose = run(buildRequest(filters, buildKeywordClauses(keyword, true), from, size, sorts));
        return new EsHits(loose.ids(), loose.total(), true);
    }

    /**
     * 关键词召回：**分层加权，精确优先**（修复「搜『喂食器』出零食/罐头」）。
     *
     * <p>为什么必须分层：{@code pet_text} 用 standard 分词器，中文是**逐字切分**
     * （「喂食器」→ 喂／食／器）。若直接对三个字做 {@code operator: or} 召回，
     * 任何含「食」的商品（零食、主食罐…）都会进来 —— 这正是线上看到的现象。
     * 因此按「匹配强度」分五档，各自给权重，由 ES 求和打分决定顺序：
     *
     * <ol>
     *   <li><b>标题整串短语</b>（boost 6）：{@code match_phrase} 要求「喂 食 器」相邻出现，
     *       语义等价 MySQL 的 {@code title LIKE '%喂食器%'} —— 最强信号；</li>
     *   <li><b>标题分词全命中</b>（boost 3）：三个字都在标题里（可能不相邻）；</li>
     *   <li><b>副标题/描述整串短语</b>（boost 2 / 1）：等价 MySQL 的 subtitle LIKE，
     *       描述命中是 A10 有意的额外召回（弱信号，分数低）；</li>
     *   <li><b>跨字段分词全命中</b>（boost 2）+ <b>拼音</b>（boost 1.5，走 titlePinyin ngram 字段）；</li>
     *   <li><b>逐字宽松兜底</b>（boost 0.1）：**只在严格召回为零时启用**
     *       （见 {@link #searchStrictThenLoose}），保证「搜不到」不比「搜不准」更糟。</li>
     * </ol>
     *
     * <p>同义词变体（{@link SearchSynonyms}）复用同一套分层，但权重减半 ——
     * 变体是「猜用户可能想搜的词」，可信度低于原文。
     *
     * <p>权重只有在<b>默认排序含 {@code _score}</b> 时才有意义，见 {@link #applyProductSort}。
     */
    private List<Object> buildKeywordClauses(String keyword, boolean includeLoose) {
        List<Object> clauses = new ArrayList<>();
        if (!StringUtils.hasText(keyword)) {
            return clauses;
        }
        // 多词查询（空格/逗号/顿号分隔）按 **AND** 处理：「猫粮 2kg」要求两个词都命中；
        // 单词内部仍是多分支 OR + 分层权重。此前多词会被当成一个整体去做逐字 OR，
        // 既容易召回噪音，也搜不准（如「冻干 鸡肉」只按"冻干 鸡肉"整串匹配）。
        String[] words = keyword.trim().split("[\\s,，、]+");
        for (String word : words) {
            String w = word.trim();
            if (w.isEmpty()) {
                continue;
            }
            List<Object> should = new ArrayList<>();
            addKeywordBranch(should, w, 1.0, includeLoose);
            for (String variant : synonyms.variants(w)) {
                addKeywordBranch(should, variant, 0.5, includeLoose);   // 变体权重减半
            }
            // 一个词 = 一个 bool(minimum_should_match=1) 子句；词与词之间由调用方以 must 串联
            clauses.add(Map.of("bool", Map.of("minimum_should_match", 1, "should", should)));
        }
        return clauses;
    }

    /** 单个查询串的召回分支：按「匹配强度」分档加权（weight 为整体缩放系数） */
    private static void addKeywordBranch(List<Object> should, String text, double weight, boolean includeLoose) {
        // ① 标题整串短语（最强）：等价 MySQL 的 title LIKE '%text%'
        should.add(phrase("title", text, 6.0 * weight));
        // ② 标题分词全命中：喂 AND 食 AND 器 都在标题里
        should.add(multiMatch(text, List.of("title"), "and", 3.0 * weight));
        // ③ 副标题整串短语（等价 MySQL 的 subtitle LIKE）
        should.add(phrase("subtitle", text, 2.0 * weight));
        // ④ 描述整串短语（A10 有意的额外召回，弱于标题/副标题）
        should.add(phrase("description", text, 1.0 * weight));
        // ⑤ 跨字段分词全命中
        should.add(multiMatch(text, List.of("title", "subtitle", "description"), "and", 2.0 * weight));
        // ⑥ 拼音路：中文转拼音、纯 ASCII 直通，两侧都 ngram 切分（见 SearchIndexer.ensureIndex）
        String py = SearchIndexer.pinyin(text);
        // 短于 2 字符的拼音切不出 gram（min_gram=2），该分支必然 0 命中，直接不生成
        if (py.length() >= 2) {
            should.add(match("titlePinyin", py, "and", 1.5 * weight));
        }
        // ⑦ 逐字宽松兜底：仅原文加（变体再加一层会把噪音放大两倍）。
        //    由 searchStrictThenLoose 控制在「严格召回为零」时才启用，避免第一屏混入噪音。
        if (includeLoose && weight >= 1.0) {
            should.add(multiMatch(text, List.of("title", "subtitle", "description"), "or", 0.1));
        }
    }

    private static Map<String, Object> phrase(String field, String text, double boost) {
        return Map.of("match_phrase", Map.of(field, matchOptions(text, boost)));
    }

    private static Map<String, Object> match(String field, String text, String operator, double boost) {
        Map<String, Object> opts = matchOptions(text, boost);
        opts.put("operator", operator);
        return Map.of("match", Map.of(field, opts));
    }

    private static Map<String, Object> multiMatch(String text, List<String> fields, String operator, double boost) {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("query", text);
        opts.put("fields", fields);
        opts.put("operator", operator);
        opts.put("boost", boost);
        return Map.of("multi_match", opts);
    }

    /** match / match_phrase 的公共选项（LinkedHashMap 以便继续追加 operator） */
    private static Map<String, Object> matchOptions(String text, double boost) {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("query", text);
        opts.put("boost", boost);
        return opts;
    }

    private SearchRequest.Builder buildRequest(List<Object> filters, List<Object> keywordClauses, int from, int size,
                                               List<SortOptions> sorts) {
        SearchRequest.Builder b = new SearchRequest.Builder()
                .index(SearchIndexer.INDEX)
                .from(from).size(size)
                .trackTotalHits(t -> t.enabled(true))
                .sort(sorts);
        b.query(q -> {
            if (keywordClauses.isEmpty()) {
                q.bool(bb -> bb.filter(queryList(filters)));
            } else {
                // 关键词子句之间是 must（AND）：多词的每个词都必须命中；
                // 单/多分支与权重在 buildKeywordClauses 里已经包好
                q.bool(bb -> bb.filter(queryList(filters)).must(queryList(keywordClauses)));
            }
            return q;
        });
        return b;
    }

    private EsHits run(SearchRequest.Builder b) throws Exception {
        var resp = es.search(b.build(), JsonData.class);
        List<Long> ids = new ArrayList<>();
        for (var hit : resp.hits().hits()) {
            Map<String, Object> src = hit.source() == null ? Map.of() : hit.source().to(Map.class);
            Object refId = src.get("refId");
            if (refId instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        long total = resp.hits().total() == null ? ids.size() : resp.hits().total().value();
        return new EsHits(ids, total, false);
    }

    /**
     * 排序：末位恒加 refId 降序保证稳定分页。
     *
     * <p><b>修复点</b>：默认排序（用户没指定 sort）改为
     * {@code _score desc → salesCount desc → refId desc}。
     * 之前只有 {@code salesCount desc}，而 ES 一旦显式 sort 就**不再按相关性排** ——
     * 搜「喂食器」时唯一精确命中「霍曼 自动喂食器」（销量最低）会被挤到最后一名，
     * 第一屏全是逐字召回的零食/罐头，用户看到的就是「结果与关键词不匹配」。
     *
     * <p>无关键词时（纯浏览）所有文档 {@code _score} 相同（filter-only 查询），
     * 排序自动退化为销量降序 —— 与旧行为一致，不影响导航页。
     *
     * <p>用户显式指定 price/sales/newest/rating 时**尊重其选择**（不加 _score）：
     * 召回已由分层权重收紧，不需要再靠排序纠偏。
     */
    private List<SortOptions> applyProductSort(String sort) {
        String value = (sort == null || sort.isBlank()) ? "default" : sort.trim();
        List<Map<String, Object>> sorts = new ArrayList<>();
        switch (value) {
            case "price_asc" -> sorts.add(fieldSort("minPrice", "asc"));
            case "price_desc" -> sorts.add(fieldSort("minPrice", "desc"));
            case "sales" -> sorts.add(fieldSort("salesCount", "desc"));
            case "rating" -> sorts.add(fieldSort("rating", "desc"));
            case "newest" -> sorts.add(fieldSort("createdAt", "desc"));
            // 兜底分支同时承担两种输入：未传 sort（"default"）与显式 sort=relevance
            // —— 两者语义相同（相关性优先、同分看销量），不需要两套代码。
            default -> {
                sorts.add(fieldSort("_score", "desc"));      // 相关性优先（关键词检索的默认直觉）
                sorts.add(fieldSort("salesCount", "desc"));  // 同分再按销量
            }
        }
        sorts.add(fieldSort("refId", "desc"));
        return toSortOptions(sorts);
    }

    private List<SortOptions> applyListingSort(String sort) {
        String value = (sort == null || sort.isBlank()) ? "default" : sort.trim();
        List<Map<String, Object>> sorts = new ArrayList<>();
        switch (value) {
            case "price_asc" -> sorts.add(fieldSort("minPrice", "asc"));
            case "price_desc" -> sorts.add(fieldSort("minPrice", "desc"));
            case "newest" -> sorts.add(fieldSort("createdAt", "desc"));
            default -> { /* default = id 降序，由稳定次序承担 */ }
        }
        sorts.add(fieldSort("refId", "desc"));
        return toSortOptions(sorts);
    }

    private static Map<String, Object> fieldSort(String field, String order) {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("order", order);
        if ("rating".equals(field)) {
            opts.put("missing", "_last");   // MySQL DESC 时 NULL 在末位，同语义
        }
        return Map.of(field, opts);
    }

    private static List<SortOptions> toSortOptions(List<Map<String, Object>> sorts) {
        List<SortOptions> out = new ArrayList<>();
        for (Map<String, Object> s : sorts) {
            var e = s.entrySet().iterator().next();
            String field = e.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> opts = (Map<String, Object>) e.getValue();
            SortOrder order = "asc".equals(opts.get("order")) ? SortOrder.Asc : SortOrder.Desc;
            out.add(SortOptions.of(so -> so.field(f -> {
                f.field(field).order(order);
                return f;
            })));
        }
        return out;
    }

    private static List<Query> queryList(List<Object> raw) {
        List<Query> out = new ArrayList<>();
        for (Object o : raw) {
            String json = JsonWriter.write(o);
            out.add(Query.of(q -> q.withJson(new StringReader(json))));
        }
        return out;
    }

    /**
     * E20 · 租户过滤：**只在请求声明了租户时**追加 {@code term(sellerId)}。
     *
     * <p>今天商家端并没有走 ES 的检索路径（「我的商品」走 MySQL 的
     * {@code SellerProductService.list}），所以这一条**在所有既有链路上都不生效** ——
     * 商城端买家请求没有租户上下文，行为与 E20 之前逐字一致。
     *
     * <p>它的价值是把"将来有人把商家端接到 ES 上"这个动作变成**安全的默认**：
     * 上下文会自动跟过来，不需要那个人记得写 {@code term("sellerId", …)}；
     * 而"忘了写"这类漏检在本项目的历史上从来不会报错，只会表现为"某条路径悄悄不过滤"。
     * 所以：<b>不要因为"现在用不上"就删掉它</b>——这是一道只有出事时才知道缺了的防线。
     */
    private static void addTenantFilter(List<Object> filters) {
        TenantContext.get().ifPresent(tenantId -> filters.add(term("sellerId", tenantId)));
    }

    private static Map<String, Object> term(String field, Object value) {
        return Map.of("term", Map.of(field, value));
    }

    private static Map<String, Object> termsLong(String field, List<Long> values) {
        return Map.of("terms", Map.of(field, values));
    }

    private static Map<String, Object> termsText(String field, List<String> values) {
        // keyword 字段区分大小写；物种/生命阶段在库中统一小写
        return Map.of("terms", Map.of(field, values.stream().map(String::toLowerCase).toList()));
    }

    private static Map<String, Object> range(String field, Long gte, Long lte) {
        Map<String, Object> range = new LinkedHashMap<>();
        if (gte != null) {
            range.put("gte", gte);
        }
        if (lte != null) {
            range.put("lte", lte);
        }
        return Map.of("range", Map.of(field, range));
    }

    /** 极简 JSON writer：仅覆盖本类构造的受控结构（Map/List/String/Number/Boolean），不处理任意输入 */
    private static final class JsonWriter {
        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(sb, o);
            return sb.toString();
        }

        private static void write(StringBuilder sb, Object o) {
            if (o == null) {
                sb.append("null");
            } else if (o instanceof String s) {
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else if (o instanceof Number || o instanceof Boolean) {
                sb.append(o);
            } else if (o instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof Iterable<?> it) {
                sb.append('[');
                boolean first = true;
                for (Object v : it) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, v);
                }
                sb.append(']');
            } else {
                sb.append('"').append(o).append('"');
            }
        }
    }
}

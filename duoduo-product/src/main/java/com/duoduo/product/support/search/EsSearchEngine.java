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
 * 看到两个商品世界，比慢更难排查。中文用 pet_text 分析器（standard 分词 + 内置同义词，
 * 验收①），纯 ASCII / 中文查询词额外拼一路 pinyin 字段匹配（验收①拼音容错）。
 */
@Component
@ConditionalOnProperty(name = "duoduo.search.enabled", havingValue = "true")
public class EsSearchEngine {

    /** ES 命中页：id 已按请求的排序排好，total 为过滤后的总数 */
    public record EsHits(List<Long> ids, long total) {
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
        return run(buildRequest(filters, buildKeywordShould(q.keyword()),
                (pageNo - 1) * pageSize, pageSize, applyProductSort(q.sort())));
    }

    /** 闲置检索（筛选平移自 ListingService.search 的 MySQL 实现，可见性同 visibleWrapper） */
    public EsHits searchListings(ListingQuery q, int pageNo, int pageSize) throws Exception {
        List<Object> filters = new ArrayList<>();
        filters.add(term("docType", SearchIndexer.TYPE_LISTING));
        filters.add(term("status", "ON_SALE"));
        filters.add(term("auditStatus", "APPROVED"));
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
        return run(buildRequest(filters, buildKeywordShould(q.keyword()),
                (pageNo - 1) * pageSize, pageSize, applyListingSort(q.sort())));
    }

    /**
     * 关键词召回：
     * <ol>
     *   <li><b>原文</b>走 pet_text（standard 分词 + 索引侧同义词）；</li>
     *   <li><b>同义词变体</b>（应用侧扩展，见 {@link SearchSynonyms}）—— 逐字分词下
     *       索引侧 synonym filter 覆盖不到多字词，只能在这里补；</li>
     *   <li><b>拼音</b>：中文转拼音、纯 ASCII 直通，走 ngram 字段；
     *       两侧都 ngram 切分，长拼音串才能命中（见 {@code SearchIndexer.ensureIndex}）。</li>
     * </ol>
     */
    private List<Object> buildKeywordShould(String keyword) {
        List<Object> should = new ArrayList<>();
        if (!StringUtils.hasText(keyword)) {
            return should;
        }
        String kw = keyword.trim();
        addKeywordBranch(should, kw);
        for (String variant : synonyms.variants(kw)) {
            addKeywordBranch(should, variant);
        }
        return should;
    }

    /** 单个查询串的召回分支：文本路 + 拼音路 */
    private static void addKeywordBranch(List<Object> should, String text) {
        should.add(Map.of("multi_match", Map.of("query", text,
                "fields", List.of("title", "subtitle", "description"), "operator", "or")));
        String py = SearchIndexer.pinyin(text);
        // 短于 2 字符的拼音切不出 gram（min_gram=2），该分支必然 0 命中，直接不生成
        if (py.length() >= 2) {
            should.add(Map.of("match", Map.of("titlePinyin", Map.of("query", py, "operator", "and"))));
        }
    }

    private SearchRequest.Builder buildRequest(List<Object> filters, List<Object> should, int from, int size,
                                               List<SortOptions> sorts) {
        SearchRequest.Builder b = new SearchRequest.Builder()
                .index(SearchIndexer.INDEX)
                .from(from).size(size)
                .trackTotalHits(t -> t.enabled(true))
                .sort(sorts);
        b.query(q -> {
            if (should.isEmpty()) {
                q.bool(bb -> bb.filter(queryList(filters)));
            } else {
                q.bool(bb -> bb.filter(queryList(filters))
                        .must(m -> m.bool(mb -> mb.minimumShouldMatch("1").should(queryList(should)))));
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
        return new EsHits(ids, total);
    }

    /** 与 MySQL applySort 同语义（price 用 minPrice，default = 销量降序），末位恒加 refId 降序保证稳定分页 */
    private List<SortOptions> applyProductSort(String sort) {
        String value = (sort == null || sort.isBlank()) ? "default" : sort.trim();
        List<Map<String, Object>> sorts = new ArrayList<>();
        switch (value) {
            case "price_asc" -> sorts.add(fieldSort("minPrice", "asc"));
            case "price_desc" -> sorts.add(fieldSort("minPrice", "desc"));
            case "sales" -> sorts.add(fieldSort("salesCount", "desc"));
            case "rating" -> sorts.add(fieldSort("rating", "desc"));
            case "newest" -> sorts.add(fieldSort("createdAt", "desc"));
            default -> sorts.add(fieldSort("salesCount", "desc"));
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

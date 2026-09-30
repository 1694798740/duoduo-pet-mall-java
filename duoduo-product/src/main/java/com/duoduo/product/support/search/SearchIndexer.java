package com.duoduo.product.support.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.json.JsonData;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.ListingMapper;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * ES 索引写入器（A10）。
 *
 * <p><b>索引/文档设计</b>：单索引 {@code duoduo-search}，{@code docType} 区分
 * PRODUCT / LISTING —— 两类文档共享一套分词与拼音设施，检索端点也只有两个，
 * 单索引少一套索引生命周期要管。
 *
 * <p><b>两个刻意的设计决定</b>：
 * <ul>
 *   <li><b>拼音应用侧生成</b>（pinyin4j，见 T-59：TinyPinyin 不在 Maven Central）：不用 analysis-pinyin 插件 ——
 *       插件意味着「官方镜像 + 启动时装插件」，装失败 ES 就起不来，
 *       把一个可选加速层变成新的运维面。应用侧生成后官方镜像开箱即用；</li>
 *   <li><b>SKU 属性冗余进 SPU 文档</b>（species / lifeStage / inStock）：
 *       检索要按这些条件过滤，ES 里做 join 是自找麻烦；
 *       冗余的一致性由对账扫描兜底（见 {@code SearchSyncJob}）。</li>
 * </ul>
 *
 * <p><b>失败语义</b>：所有写操作**不抛异常**（记日志返回 false）——
 * 索引写失败绝不影响 MySQL 主链路，漏掉的由补偿扫描追平。
 */
@Component
@ConditionalOnProperty(name = "duoduo.search.enabled", havingValue = "true")
public class SearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);
    public static final String INDEX = "duoduo-search";
    public static final String TYPE_PRODUCT = "PRODUCT";
    public static final String TYPE_LISTING = "LISTING";

    private final ElasticsearchClient es;
    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final ListingMapper listingMapper;
    private volatile boolean indexReady;

    public SearchIndexer(ElasticsearchClient es,
                         SpuMapper spuMapper,
                         SkuMapper skuMapper,
                         ListingMapper listingMapper) {
        this.es = es;
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.listingMapper = listingMapper;
    }

    /**
     * 建索引（幂等）。
     *
     * <p><b>为什么这里没有同义词分析器</b>：同义词<b>刻意不交给 ES</b>，改由应用侧替换
     * （见 {@link SearchSynonyms}）。实测结论（ES 8.11）：内置 standard 对中文逐字切分，
     * 同义词规则「猫咪,喵星人」在查询侧展开后生成的查询是 <b>相邻短语</b>
     * （{@code title:"猫 咪"}）而不是 or 分支 —— 查「猫咪」时要求标题里「猫」「咪」连续出现，
     * 结果连「猫粮」都命中不了，同义词反而把召回收紧了。索引侧同样不能挂：
     * 它会让索引里凭空多出同义 token，同义词的来源变得无法解释。
     *
     * <p><b>拼音字段的两个关键点</b>：
     * <ul>
     *   <li>查询串也必须走 ngram 切分（{@code pinyin_query}）：否则「youquanliang」这种
     *       超过 max_gram 的整串会被当成单个 token，与索引里的 2~10 字符 gram 永远对不上，
     *       拼音容错静默失效。配合查询侧 operator=and，
     *       「查询串的全部 gram 都命中」≈「标题拼音包含该子串」；</li>
     *   <li>{@code max_ngram_diff} 必须显式放开：ES 默认只允许 max-min ≤ 1，2~10 会直接建索引失败。</li>
     * </ul>
     */
    public synchronized void ensureIndex() {
        try {
            if (es.indices().exists(r -> r.index(INDEX)).value()) {
                indexReady = true;
                return;
            }
            // 分析器定义走 withJson（analysis 对象本身就是这段 JSON 的形态）；
            // 索引级设置（分片 / 副本 / max_ngram_diff）走类型化 API ——
            // `withJson` 的目标类型是 CreateIndexRequest，顶层字段是 settings / mappings，
            // 把 settings 的内容直接丢进去会报 `Unknown field 'index'`。
            String analysis = """
                    {
                      "analyzer": {
                        "pet_text": {"type": "custom", "tokenizer": "standard", "filter": ["lowercase"]},
                        "pinyin_ngram": {"type": "custom", "tokenizer": "ngram_tokenizer", "filter": ["lowercase"]},
                        "pinyin_query": {"type": "custom", "tokenizer": "ngram_tokenizer", "filter": ["lowercase"]}
                      },
                      "tokenizer": {"ngram_tokenizer": {"type": "ngram", "min_gram": 2, "max_gram": 10,
                                       "token_chars": ["letter", "digit"]}}
                    }
                    """;
            String mappings = """
                    {
                      "properties": {
                        "docType":       {"type": "keyword"},
                        "refId":         {"type": "long"},
                        "title":         {"type": "text", "analyzer": "pet_text"},
                        "subtitle":      {"type": "text", "analyzer": "pet_text"},
                        "description":   {"type": "text", "analyzer": "pet_text"},
                        "titlePinyin":   {"type": "text", "analyzer": "pinyin_ngram", "search_analyzer": "pinyin_query"},
                        "categoryId":    {"type": "long"},
                        "brandId":       {"type": "long"},
                        "supplyType":    {"type": "keyword"},
                        "status":        {"type": "keyword"},
                        "auditStatus":   {"type": "keyword"},
                        "speciesList":   {"type": "keyword"},
                        "lifeStageList": {"type": "keyword"},
                        "inStock":       {"type": "boolean"},
                        "minPrice":      {"type": "long"},
                        "maxPrice":      {"type": "long"},
                        "salesCount":    {"type": "integer"},
                        "rating":        {"type": "double"},
                        "city":          {"type": "keyword"},
                        "species":       {"type": "keyword"},
                        "conditionLevel":{"type": "keyword"},
                        "createdAt":     {"type": "long"},
                        "updatedAt":     {"type": "long"}
                      }
                    }
                    """;
            es.indices().create(r -> r.index(INDEX)
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0").maxNgramDiff(10)
                            .analysis(a -> a.withJson(new StringReader(analysis))))
                    .mappings(m -> m.withJson(new StringReader(mappings))));
            indexReady = true;
            log.info("ES 索引已创建 index={}（同义词由应用侧替换，不挂分析器）", INDEX);
        } catch (Exception ex) {
            indexReady = false;
            log.warn("ES 建索引失败（检索将降级 MySQL，补偿扫描稍后重试）: {}", ex.getMessage());
        }
    }

    public boolean isIndexReady() {
        return indexReady;
    }

    /**
     * 索引是否可用；不可用则再试一次建索引。
     *
     * <p><b>所有写操作都必须先过这一关</b>：直接写一个不存在的索引会触发 ES 的
     * {@code action.auto_create_index} 自动建索引 —— 用<b>动态推断的 mapping</b>
     * 建出一个没有 pet_text / pinyin 分析器的索引，而它此后 {@code exists=true}，
     * 建索引逻辑从此永远跳过，错误形态被固化、重建也修不回来。
     * 宁可这次写入失败（补偿扫描稍后会追平），也不留下一个形态错误的索引。
     */
    private synchronized boolean ready() {
        if (!indexReady) {
            ensureIndex();
        }
        return indexReady;
    }

    /** 索引单个商品（含 SKU 冗余属性）。失败返回 false，不抛出 —— 索引层绝不拖垮主链路 */
    public boolean indexProduct(Long spuId) {
        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null) {
            return deleteDoc(TYPE_PRODUCT, spuId);
        }
        List<SkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>().eq(SkuEntity::getSpuId, spuId));
        List<String> species = skus.stream().map(SkuEntity::getTargetSpecies).filter(s -> s != null && !s.isBlank()).toList();
        List<String> lifeStages = skus.stream().map(SkuEntity::getTargetLifeStage).filter(s -> s != null && !s.isBlank()).toList();
        boolean inStock = skus.stream()
                .anyMatch(s -> "ACTIVE".equals(s.getStatus()) && s.getStock() != null
                        && s.getStock() - (s.getFrozenStock() == null ? 0 : s.getFrozenStock()) > 0);

        Map<String, Object> doc = baseDoc(TYPE_PRODUCT, spu.getId(), spu.getUpdatedAt());
        doc.put("title", spu.getTitle());
        doc.put("titlePinyin", pinyin(spu.getTitle() + " " + nullToEmpty(spu.getSubtitle())));
        doc.put("subtitle", spu.getSubtitle());
        doc.put("categoryId", spu.getCategoryId());
        doc.put("brandId", spu.getBrandId());
        doc.put("supplyType", spu.getSupplyType());
        // 上下架状态进文档：查询侧与 MySQL 的 status='ON_SALE' 同口径过滤（验收③）
        doc.put("status", spu.getStatus());
        doc.put("speciesList", species);
        doc.put("lifeStageList", lifeStages);
        doc.put("inStock", inStock);
        doc.put("minPrice", spu.getMinPrice());
        doc.put("maxPrice", spu.getMaxPrice());
        doc.put("salesCount", spu.getSalesCount() == null ? 0 : spu.getSalesCount());
        doc.put("rating", spu.getRating() == null ? 0.0 : spu.getRating().doubleValue());
        return putDoc(TYPE_PRODUCT, spuId, doc);
    }

    /** 索引单个闲置 */
    public boolean indexListing(Long id) {
        ListingEntity l = listingMapper.selectById(id);
        // 软删 = 从检索里摘掉：删除后 status 被压成 OFF_SHELF，靠「status=ON_SALE」过滤
        // 其实已经查不到，但仍显式删文档 —— 否则索引里长期留着一条永远不会命中的脏数据，
        // 对账接口（missingInIndex / 文档计数）会把它算进总数，运营据此判断索引状态会失真。
        if (l == null || l.getDeletedAt() != null) {
            return deleteDoc(TYPE_LISTING, id);
        }
        Map<String, Object> doc = baseDoc(TYPE_LISTING, l.getId(), l.getUpdatedAt());
        doc.put("title", l.getTitle());
        doc.put("titlePinyin", pinyin(l.getTitle()));
        doc.put("description", l.getDescription());
        doc.put("minPrice", l.getPrice());
        doc.put("maxPrice", l.getPrice());
        doc.put("city", l.getCity());
        doc.put("species", l.getSpecies());
        doc.put("conditionLevel", l.getConditionLevel());
        doc.put("status", l.getStatus());
        doc.put("auditStatus", l.getAuditStatus());
        doc.put("createdAt", toMillis(l.getCreatedAt()));
        // 可见性进索引：只有「在售 + 审核通过」可被公开检索命中，其他状态保留文档但不参与查询
        doc.put("salesCount", 0);
        return putDoc(TYPE_LISTING, id, doc);
    }

    public boolean deleteDoc(String docType, Long refId) {
        try {
            if (!ready()) {
                return false;
            }
            es.delete(d -> d.index(INDEX).id(docKey(docType, refId)).refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
            return true;
        } catch (Exception ex) {
            log.warn("ES 删除文档失败 docType={} refId={}: {}", docType, refId, ex.getMessage());
            return false;
        }
    }

    /**
     * 刷索引，让写入**立刻**可被搜索到。
     *
     * <p>只在全量重建（{@code fullSync}）结束时调用：单条 {@code putDoc} 用
     * {@code Refresh.False}，靠 ES 默认的 1 秒周期刷新 —— 对增量补偿扫描是对的
     * （高频写入不该每条都强制 refresh），但全量重建不同：「重建完成」这句话
     * 必须等于「数据立即可见」，否则管理员点完重建、马上对账看到一堆 missingInIndex，
     * 会以为重建没生效。整个重建只刷一次，成本可以忽略。
     */
    public void refreshIndex() {
        try {
            if (!ready()) {
                return;
            }
            es.indices().refresh(r -> r.index(INDEX));
        } catch (Exception ex) {
            log.warn("ES 刷新索引失败: {}", ex.getMessage());
        }
    }

    /** 对账用：拉取某类型全部文档的 refId -> updatedAt（目录规模 ≤ 1 万，一页拉回） */
    public Map<Long, Long> listDocs(String docType) {
        Map<Long, Long> result = new LinkedHashMap<>();
        try {
            if (!ready()) {
                return result;
            }
            var resp = es.search(s -> s.index(INDEX).size(10000)
                            .query(q -> q.term(t -> t.field("docType").value(docType)))
                            .source(src -> src.filter(f -> f.includes("refId", "updatedAt"))),
                    JsonData.class);
            for (var hit : resp.hits().hits()) {
                Map<String, Object> src = hit.source() == null ? Map.of() : hit.source().to(Map.class);
                Object refId = src.get("refId");
                Object updatedAt = src.get("updatedAt");
                if (refId instanceof Number n) {
                    result.put(n.longValue(), updatedAt instanceof Number m ? m.longValue() : 0L);
                }
            }
        } catch (Exception ex) {
            log.warn("ES 对账拉取失败 docType={}: {}", docType, ex.getMessage());
        }
        return result;
    }

    public boolean bulkPut(List<Map<String, Object>> docs) {
        try {
            if (docs.isEmpty()) {
                return true;
            }
            if (!ready()) {
                return false;
            }
            BulkRequest.Builder br = new BulkRequest.Builder();
            for (Map<String, Object> doc : docs) {
                String docType = (String) doc.get("docType");
                Long refId = ((Number) doc.get("refId")).longValue();
                br.operations(op -> op.index(idx -> idx.index(INDEX).id(docKey(docType, refId)).document(doc)));
            }
            es.bulk(br.refresh(co.elastic.clients.elasticsearch._types.Refresh.False).build());
            return true;
        } catch (Exception ex) {
            log.warn("ES 批量写入失败: {}", ex.getMessage());
            return false;
        }
    }

    public boolean putDoc(String docType, Long refId, Map<String, Object> doc) {
        try {
            if (!ready()) {
                return false;
            }
            es.index(i -> i.index(INDEX).id(docKey(docType, refId)).document(doc).refresh(co.elastic.clients.elasticsearch._types.Refresh.False));
            return true;
        } catch (Exception ex) {
            log.warn("ES 索引写入失败 docType={} refId={}: {}", docType, refId, ex.getMessage());
            return false;
        }
    }

    private Map<String, Object> baseDoc(String type, Long refId, java.time.LocalDateTime updatedAt) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("docType", type);
        doc.put("refId", refId);
        doc.put("updatedAt", toMillis(updatedAt));
        return doc;
    }

    private static String docKey(String docType, Long refId) {
        return docType + ":" + refId;
    }

    private static Long toMillis(java.time.LocalDateTime t) {
        return t == null ? 0L : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 拼音串（仅保留字母数字，小写）：应用侧生成，免 ES 插件。
     *  pinyin4j：无音调输出。多音字取其字典里的首个读音（与 TinyPinyin 的「最常见读音」行为一致）。 */
    public static String pinyin(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        HanyuPinyinOutputFormat fmt = new HanyuPinyinOutputFormat();
        fmt.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        fmt.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        fmt.setVCharType(HanyuPinyinVCharType.WITH_V);
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            String[] py;
            try {
                // 非汉字返回 null（含全角标点等），此时落到「仅保留字母数字」分支
                py = PinyinHelper.toHanyuPinyinStringArray(c, fmt);
            } catch (net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination e) {
                // 该异常只在输出格式组合非法时抛出；本方法的三项设置是文档认可的合法组合，
                // 到不了这里 —— 但 checked 异常必须接住，兜底按「非汉字」处理
                py = null;
            }
            if (py != null && py.length > 0) {
                sb.append(py[0]);
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}

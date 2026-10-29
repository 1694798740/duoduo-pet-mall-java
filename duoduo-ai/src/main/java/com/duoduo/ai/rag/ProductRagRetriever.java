package com.duoduo.ai.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.duoduo.ai.client.AiCatalogClient;
import com.duoduo.ai.client.dto.AiProductCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品 RAG 检索（2026-10-28）。
 *
 * <p>ES 可用时：BM25 + kNN → RRF → Rerank；ES 不可用时自动退回商品域结构化搜索。
 * ES 只负责召回，价格/库存仍由 {@code AiCatalogClient#facts} 回源。
 */
@Service
public class ProductRagRetriever {

    private static final Logger log = LoggerFactory.getLogger(ProductRagRetriever.class);
    private static final String INDEX = "duoduo_spu_ai";
    private static final int RECALL = 30;
    private static final int RRF_K = 60;

    private final ElasticsearchClient es;
    private final EmbeddingClient embedding;
    private final RerankClient rerank;
    private final AiCatalogClient catalog;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String esUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public ProductRagRetriever(ElasticsearchClient es, EmbeddingClient embedding,
                               RerankClient rerank, AiCatalogClient catalog,
                               ObjectMapper mapper,
                               @Value("${duoduo.ai.rag-enabled:true}") boolean enabled,
                               @Value("${spring.elasticsearch.uris:http://127.0.0.1:9200}") String esUrl) {
        this.es = es;
        this.embedding = embedding;
        this.rerank = rerank;
        this.catalog = catalog;
        this.mapper = mapper;
        this.enabled = enabled;
        this.esUrl = esUrl;
    }

    public List<AiProductCard> retrieve(QueryPlan plan) {
        if (enabled) {
            try {
                List<AiProductCard> cards = hybrid(plan);
                if (!cards.isEmpty()) {
                    return cards;
                }
            } catch (Exception e) {
                log.warn("商品 RAG 不可用，退回结构化搜索: {}", e.getMessage());
            }
        }
        return fallback(plan);
    }

    private List<AiProductCard> hybrid(QueryPlan plan) throws Exception {
        List<Float> vector = embedding.embed(plan.queryText());
        Map<String, JsonNode> bm25 = bm25(plan);
        Map<String, JsonNode> knn = vector.isEmpty() ? Map.of() : knn(plan, vector);

        List<String> ids = RrfMerger.merge(new ArrayList<>(bm25.keySet()),
                new ArrayList<>(knn.keySet()), RRF_K, plan.topK() * 4);
        Map<String, JsonNode> sources = new LinkedHashMap<>();
        sources.putAll(bm25);
        sources.putAll(knn);

        List<String> docs = ids.stream().map(id -> {
            JsonNode s = sources.get(id);
            return s == null ? "" : (s.path("title").asText("") + " " + s.path("content").asText(""));
        }).toList();
        List<String> reranked = rerank.rerank(plan.queryText(), docs, plan.topK());

        List<AiProductCard> cards = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : reranked) {
            if (!seen.add(id)) {
                continue;
            }
            JsonNode source = sources.get(id);
            if (source == null) {
                continue;
            }
            AiProductCard card = mapper.treeToValue(source, AiProductCard.class);
            if (card.spuId() == null || card.title() == null || card.title().isBlank()) {
                continue;
            }
            if (excluded(card, plan.excludeAllergens()) || (plan.inStockOnly() && !card.inStock())) {
                continue;
            }
            cards.add(card);
            if (cards.size() >= plan.topK()) {
                break;
            }
        }
        return cards;
    }

    private Map<String, JsonNode> bm25(QueryPlan plan) throws Exception {
        SearchResponse<JsonNode> resp = es.search(s -> s.index(INDEX).size(RECALL)
                .query(q -> q.bool(b -> {
                    b.must(m -> m.multiMatch(mm -> mm.fields("title^3", "content")
                            .query(plan.queryText())));
                    if (plan.species() != null && !plan.species().isBlank()) {
                        b.filter(f -> f.term(t -> t.field("species").value(plan.species())));
                    }
                    if (plan.lifeStage() != null && !plan.lifeStage().isBlank()) {
                        b.filter(f -> f.term(t -> t.field("lifeStages").value(plan.lifeStage())));
                    }
                    if (plan.categoryId() != null) {
                        b.filter(f -> f.term(t -> t.field("categoryId").value(plan.categoryId())));
                    }
                    if (plan.budgetMaxCents() != null) {
                        b.filter(f -> f.range(r -> r.field("minPrice")
                                .lte(JsonData.of(plan.budgetMaxCents()))));
                    }
                    if (plan.budgetMinCents() != null) {
                        b.filter(f -> f.range(r -> r.field("maxPrice")
                                .gte(JsonData.of(plan.budgetMinCents()))));
                    }
                    if (plan.inStockOnly()) {
                        b.filter(f -> f.term(t -> t.field("inStock").value(true)));
                    }
                    return b;
                })), JsonNode.class);
        return sources(resp);
    }

    /**
     * kNN 走原生 REST：Java API Client 的版本对 knn 支持不一致，
     * 原生 JSON 在 8.x 各小版本上都稳定。
     */
    private Map<String, JsonNode> knn(QueryPlan plan, List<Float> vector) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("size", RECALL);
        ObjectNode knn = body.putObject("query").putObject("knn");
        knn.put("field", "embedding");
        knn.set("query_vector", mapper.valueToTree(vector));
        knn.put("k", RECALL);
        knn.put("num_candidates", 100);
        String base = esUrl.split(",")[0].trim();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/" + INDEX + "/_search"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("kNN 检索失败 status=" + resp.statusCode());
        }
        JsonNode hits = mapper.readTree(resp.body()).path("hits").path("hits");
        Map<String, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            if (source.isObject()) {
                map.put(source.path("spuId").asText(hit.path("_id").asText()), source);
            }
        }
        return map;
    }

    private Map<String, JsonNode> sources(SearchResponse<JsonNode> resp) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        for (Hit<JsonNode> hit : resp.hits().hits()) {
            JsonNode source = hit.source();
            if (source == null) {
                continue;
            }
            String id = source.path("spuId").asText(hit.id());
            map.put(id, source);
        }
        return map;
    }

    private List<AiProductCard> fallback(QueryPlan plan) {
        List<AiProductCard> cards = catalog.search(
                plan.queryText(), plan.species(), plan.lifeStage(), plan.categoryId(),
                plan.budgetMinCents(), plan.budgetMaxCents(),
                plan.excludeAllergens() == null || plan.excludeAllergens().isEmpty()
                        ? null : String.join(",", plan.excludeAllergens()),
                plan.inStockOnly(), plan.topK()).data();
        return cards == null ? List.of() : cards;
    }

    private static boolean excluded(AiProductCard card, List<String> excluded) {
        if (excluded == null || excluded.isEmpty() || card.allergens() == null) {
            return false;
        }
        for (String e : excluded) {
            if (card.allergens().contains(e)) {
                return true;
            }
        }
        return false;
    }
}

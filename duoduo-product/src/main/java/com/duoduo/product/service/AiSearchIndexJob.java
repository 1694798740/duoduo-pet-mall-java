package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuAiProfileEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuAiProfileMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品 RAG 索引同步（2026-10-28）。
 *
 * <p>把 {@code t_spu_ai_profile + t_spu + 库存} 同步到 ES {@code duoduo_spu_ai}。
 * 走原生 REST：Java API Client 各小版本对 knn / mapping 的接口差异较大，原生 JSON 更稳定。
 *
 * <p>ES 不可用时只记 WARN，不影响商品主链路；AI 域会自动退回结构化搜索。
 */
@Component
public class AiSearchIndexJob {

    private static final Logger log = LoggerFactory.getLogger(AiSearchIndexJob.class);
    private static final String INDEX = "duoduo_spu_ai";
    private static final int BATCH = 500;

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final SpuAiProfileMapper profileMapper;
    private final ObjectMapper mapper;
    private final String esUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public AiSearchIndexJob(SpuMapper spuMapper, SkuMapper skuMapper,
                            SpuAiProfileMapper profileMapper, ObjectMapper mapper,
                            @Value("${spring.elasticsearch.uris:http://127.0.0.1:9200}") String esUrl) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.profileMapper = profileMapper;
        this.mapper = mapper;
        this.esUrl = esUrl;
    }

    @Scheduled(initialDelayString = "${duoduo.ai.index-delay-ms:120000}",
            fixedDelayString = "${duoduo.ai.index-interval-ms:300000}")
    public void sync() {
        try {
            ensureIndex();
            List<SpuAiProfileEntity> profiles = profileMapper.selectList(
                    new LambdaQueryWrapper<SpuAiProfileEntity>()
                            .orderByDesc(SpuAiProfileEntity::getUpdatedAt)
                            .last("LIMIT " + BATCH));
            if (profiles.isEmpty()) {
                return;
            }
            List<Long> ids = profiles.stream().map(SpuAiProfileEntity::getSpuId).toList();
            Map<Long, SpuEntity> spus = new HashMap<>();
            for (SpuEntity spu : spuMapper.selectBatchIds(ids)) {
                spus.put(spu.getId(), spu);
            }
            Map<Long, Integer> stock = stockOf(ids);
            StringBuilder bulk = new StringBuilder();
            for (SpuAiProfileEntity p : profiles) {
                SpuEntity spu = spus.get(p.getSpuId());
                if (spu == null || "OFF_SHELF".equals(spu.getStatus())
                        || !"APPROVED".equals(spu.getAuditStatus())) {
                    continue;
                }
                ObjectNode doc = doc(spu, p, stock.getOrDefault(spu.getId(), 0) > 0);
                bulk.append("{\"index\":{\"_index\":\"").append(INDEX)
                        .append("\",\"_id\":\"").append(spu.getId()).append("\"}}\n");
                bulk.append(mapper.writeValueAsString(doc)).append('\n');
            }
            if (bulk.isEmpty()) {
                return;
            }
            post("/_bulk", bulk.toString(), "application/x-ndjson");
            log.info("AI 商品索引同步完成：{} 条", profiles.size());
        } catch (Exception e) {
            log.warn("AI 商品索引同步失败（ES 不可用时自动降级）: {}", e.getMessage());
        }
    }

    private Map<Long, Integer> stockOf(List<Long> spuIds) {
        Map<Long, Integer> stock = new HashMap<>();
        if (spuIds.isEmpty()) {
            return stock;
        }
        for (SkuEntity sku : skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .in(SkuEntity::getSpuId, spuIds))) {
            stock.merge(sku.getSpuId(), sku.getStock() == null ? 0 : sku.getStock(), Integer::sum);
        }
        return stock;
    }

    private ObjectNode doc(SpuEntity spu, SpuAiProfileEntity p, boolean inStock) {
        ObjectNode d = mapper.createObjectNode();
        d.put("spuId", String.valueOf(spu.getId()));
        d.put("title", spu.getTitle());
        d.put("content", (spu.getTitle() == null ? "" : spu.getTitle()) + " "
                + (spu.getSubtitle() == null ? "" : spu.getSubtitle()) + " "
                + (p.getContentSummary() == null ? "" : p.getContentSummary()));
        d.put("species", p.getSpecies());
        ArrayNode stages = d.putArray("lifeStages");
        split(p.getLifeStages()).forEach(stages::add);
        d.put("categoryId", String.valueOf(spu.getCategoryId()));
        d.put("brandId", spu.getBrandId() == null ? null : String.valueOf(spu.getBrandId()));
        d.put("minPrice", spu.getMinPrice() == null ? 0 : spu.getMinPrice());
        d.put("maxPrice", spu.getMaxPrice() == null ? 0 : spu.getMaxPrice());
        d.put("inStock", inStock);
        d.put("packageWeightG", p.getPackageWeightG());
        d.set("allergens", jsonArray(p.getAllergens()));
        d.set("functionTags", jsonArray(p.getFunctionTags()));
        return d;
    }

    private JsonNode jsonArray(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createArrayNode();
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node.isArray() ? node : mapper.createArrayNode();
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private void ensureIndex() throws Exception {
        String mapping = """
            {"mappings":{"properties":{
              "spuId":{"type":"keyword"},"title":{"type":"text","analyzer":"standard"},
              "content":{"type":"text","analyzer":"standard"},"species":{"type":"keyword"},
              "lifeStages":{"type":"keyword"},"categoryId":{"type":"keyword"},
              "brandId":{"type":"keyword"},"minPrice":{"type":"integer"},
              "maxPrice":{"type":"integer"},"inStock":{"type":"boolean"},
              "packageWeightG":{"type":"integer"},"allergens":{"type":"keyword"},
              "functionTags":{"type":"keyword"},
              "embedding":{"type":"dense_vector","dims":1024,"index":true,"similarity":"cosine"}
            }}}
            """;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/" + INDEX))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapping))
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 && resp.statusCode() != 400) {
            throw new IllegalStateException("创建索引失败 status=" + resp.statusCode());
        }
    }

    private void post(String path, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("ES 写入失败 status=" + resp.statusCode());
        }
    }

    private String base() {
        return esUrl.split(",")[0].trim();
    }
}

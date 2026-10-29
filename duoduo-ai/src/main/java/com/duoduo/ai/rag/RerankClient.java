package com.duoduo.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DashScope Rerank（gte-rerank-v2）。失败时退回 RRF 顺序，不阻断对话。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public RerankClient(ObjectMapper mapper,
                        @Value("${duoduo.ai.api-key:}") String apiKey,
                        @Value("${duoduo.ai.rerank-model:gte-rerank-v2}") String model) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    public List<String> rerank(String query, List<String> documents, int topN) {
        if (documents.isEmpty()) {
            return documents;
        }
        if (apiKey.isEmpty()) {
            return documents.subList(0, Math.min(topN, documents.size()));
        }
        try {
            var body = mapper.createObjectNode();
            body.put("model", model);
            var input = body.putObject("input");
            input.put("query", query);
            var docs = input.putArray("documents");
            documents.forEach(docs::add);
            body.putObject("parameters").put("top_n", topN).put("return_documents", false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return documents.subList(0, Math.min(topN, documents.size()));
            }
            JsonNode results = mapper.readTree(resp.body()).path("output").path("results");
            List<String> out = new ArrayList<>();
            for (JsonNode r : results) {
                int idx = r.path("index").asInt(-1);
                if (idx >= 0 && idx < documents.size()) {
                    out.add(documents.get(idx));
                }
            }
            return out.isEmpty() ? documents.subList(0, Math.min(topN, documents.size())) : out;
        } catch (Exception e) {
            log.warn("Rerank 异常，退回 RRF 顺序: {}", e.getMessage());
            return documents.subList(0, Math.min(topN, documents.size()));
        }
    }
}

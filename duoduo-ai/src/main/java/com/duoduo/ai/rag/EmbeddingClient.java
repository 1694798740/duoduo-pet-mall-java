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
 * DashScope Embedding 客户端（text-embedding-v3，1024 维）。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public EmbeddingClient(ObjectMapper mapper,
                           @Value("${duoduo.ai.api-key:}") String apiKey,
                           @Value("${duoduo.ai.embedding-model:text-embedding-v3}") String model) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    public List<Float> embed(String text) {
        if (apiKey.isEmpty() || text == null || text.isBlank()) {
            return List.of();
        }
        try {
            var body = mapper.createObjectNode();
            body.put("model", model);
            body.putObject("input").putArray("texts").add(text);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Embedding 失败 status={}", resp.statusCode());
                return List.of();
            }
            JsonNode emb = mapper.readTree(resp.body())
                    .path("output").path("embeddings").path(0).path("embedding");
            List<Float> out = new ArrayList<>();
            emb.forEach(n -> out.add((float) n.asDouble()));
            return out;
        } catch (Exception e) {
            log.warn("Embedding 异常: {}", e.getMessage());
            return List.of();
        }
    }
}

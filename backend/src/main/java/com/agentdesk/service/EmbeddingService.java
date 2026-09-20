package com.agentdesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 调 Agent 服务的 /v1/embed 做批量向量化（GLM embedding-3，维度对齐 pgvector vector(512)）。
 * 未配置 agent.embed-model 或调用失败时返回空结果——检索自动降级为纯 FTS；
 * 失败后 60s 熔断，避免每次检索都等待一次必败的 HTTP 调用。
 */
@Service
public class EmbeddingService {
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final String agentBaseUrl;
  private final String agentToken;
  private final String embedModel;
  private volatile long unavailableUntilNanos = 0;

  public EmbeddingService(ObjectMapper mapper,
                          @Value("${agent.base-url:http://localhost:8000}") String agentBaseUrl,
                          @Value("${agent.service-token:}") String agentToken,
                          @Value("${agent.embed-model:}") String embedModel) {
    this.mapper = mapper;
    this.agentBaseUrl = agentBaseUrl;
    this.agentToken = agentToken;
    this.embedModel = embedModel == null ? "" : embedModel.strip();
    this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
  }

  public boolean enabled() { return !embedModel.isEmpty(); }

  public String model() { return embedModel; }

  /** 批量向量化；不可用/失败返回空列表（调用方据此跳过向量化）。 */
  public List<float[]> embedBatch(List<String> texts) {
    if (!enabled() || texts == null || texts.isEmpty()) return List.of();
    if (System.nanoTime() < unavailableUntilNanos) return List.of();
    try {
      String payload = mapper.writeValueAsString(Map.of("texts", texts));
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(agentBaseUrl + "/v1/embed"))
          .timeout(Duration.ofSeconds(20))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8));
      if (!agentToken.isBlank()) request.header("X-Agent-Token", agentToken);
      HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
      if (response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
      JsonNode root = mapper.readTree(response.body());
      if (!root.path("ok").asBoolean(false) || !root.has("embeddings"))
        throw new IllegalStateException(root.path("failureReason").asText("agent 返回不可用"));
      List<float[]> out = new ArrayList<>();
      for (JsonNode arr : root.get("embeddings")) {
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) v[i] = (float) arr.get(i).asDouble();
        out.add(v);
      }
      unavailableUntilNanos = 0;
      return out;
    } catch (Exception e) {
      unavailableUntilNanos = System.nanoTime() + 60_000_000_000L;
      org.slf4j.LoggerFactory.getLogger(EmbeddingService.class).warn("embedding 调用失败，60s 内检索降级为纯 FTS：{}", e.getMessage());
      return List.of();
    }
  }

  public Optional<float[]> embedOne(String text) {
    List<float[]> result = embedBatch(List.of(text));
    return (result.isEmpty() || result.get(0) == null) ? Optional.empty() : Optional.of(result.get(0));
  }
}

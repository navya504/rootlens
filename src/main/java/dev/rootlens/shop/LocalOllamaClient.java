package dev.rootlens.shop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class LocalOllamaClient {
    private final RestClient rest;
    private final RestClient chatRest;
    private final String baseUrl;
    private final String embeddingModel;
    private final String chatModel;
    private final ObjectMapper objectMapper;
    private volatile Set<String> modelNames = Set.of();
    private volatile long modelsCheckedAt;
    private volatile String lastEmbeddingError = "";
    private final AtomicLong lastEmbeddingNanos = new AtomicLong();

    public LocalOllamaClient(
        @Value("$" + "{rootlens.ollama.base-url:http://localhost:11434}") String baseUrl,
        @Value("$" + "{rootlens.ollama.embedding-model:qwen3-embedding:0.6b}") String embeddingModel,
        @Value("$" + "{rootlens.ollama.chat-model:qwen3:4b}") String chatModel,
        ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(90));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        var chatHttp = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var chatFactory = new JdkClientHttpRequestFactory(chatHttp);
        // Local models can spend tens of seconds loading weights and constraining structured JSON output.
        chatFactory.setReadTimeout(Duration.ofSeconds(180));
        this.chatRest = RestClient.builder().baseUrl(baseUrl).requestFactory(chatFactory).build();
    }

    public String embeddingModel() { return embeddingModel; }
    public String chatModel() { return chatModel; }
    public long lastEmbeddingNanos() { return lastEmbeddingNanos.get(); }
    public String lastEmbeddingError() { return lastEmbeddingError; }

    public boolean available() { return !models().isEmpty(); }
    public boolean embeddingModelAvailable() { return models().stream().anyMatch(name -> matches(name, embeddingModel)); }
    public boolean chatModelAvailable() { return models().stream().anyMatch(name -> matches(name, chatModel)); }

    public synchronized Set<String> models() {
        if (System.currentTimeMillis() - modelsCheckedAt < 10_000) return modelNames;
        try {
            Map<?, ?> response = rest.get().uri("/api/tags").retrieve().body(Map.class);
            Object raw = response == null ? null : response.get("models");
            if (raw instanceof List<?> models) {
                modelNames = models.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .map(item -> String.valueOf(item.get("name"))).collect(java.util.stream.Collectors.toUnmodifiableSet());
            } else modelNames = Set.of();
        } catch (RestClientException exception) {
            modelNames = Set.of();
        }
        modelsCheckedAt = System.currentTimeMillis();
        return modelNames;
    }

    public List<double[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        if (!embeddingModelAvailable()) {
            lastEmbeddingError = models().isEmpty() ? "Ollama is not reachable at " + baseUrl() : "Embedding model is not installed: " + embeddingModel;
            return List.of();
        }
        long start = System.nanoTime();
        try {
            Map<?, ?> response = rest.post().uri("/api/embed")
                .body(Map.of("model", embeddingModel, "input", texts, "truncate", true))
                .retrieve().body(Map.class);
            Object raw = response == null ? null : response.get("embeddings");
            if (!(raw instanceof List<?> vectors)) {
                lastEmbeddingError = "Ollama returned no embeddings for " + embeddingModel;
                return List.of();
            }
            List<double[]> result = new ArrayList<>();
            for (Object vector : vectors) {
                if (!(vector instanceof List<?> values)) {
                    lastEmbeddingError = "Ollama returned an invalid embedding vector";
                    return List.of();
                }
                double[] parsed = new double[values.size()];
                for (int i = 0; i < values.size(); i++) parsed[i] = ((Number) values.get(i)).doubleValue();
                result.add(parsed);
            }
            if (result.size() != texts.size() || result.stream().anyMatch(vector -> vector.length == 0)) {
                lastEmbeddingError = "Ollama returned an incomplete embedding batch";
                return List.of();
            }
            lastEmbeddingError = "";
            return result;
        } catch (RestClientException | ClassCastException exception) {
            lastEmbeddingError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return List.of();
        } finally {
            lastEmbeddingNanos.set(System.nanoTime() - start);
        }
    }

    public ShopModels.GeneratedRca generateRca(String question, String investigationContext, List<ShopModels.CodeSource> evidence) {
        if (!chatModelAvailable()) throw new IllegalStateException("Ollama chat model is unavailable: " + chatModel);
        StringBuilder context = new StringBuilder();
        for (ShopModels.CodeSource source : evidence.stream().limit(2).toList()) {
            context.append("\nSOURCE ").append(source.path()).append(":").append(source.startLine()).append("-").append(source.endLine())
                .append(" method ").append(source.symbol()).append("\n").append(source.content()).append("\n");
        }
        if (context.length() > 3_500) context.setLength(3_500);
        String system = "You are RootLens, a senior software incident analyst. Generate every explanatory RCA field from the supplied order/defect context and retrieved source evidence; do not use canned defect-specific conclusions or external assumptions. "
            + "Return only one valid JSON object matching this schema: {\"answer\":string,\"rootCause\":string,\"whyItHappened\":string,\"remediation\":string,\"codeFix\":string,\"severity\":string,\"suspectedService\":string,\"telemetrySignal\":string,\"assessment\":string}. Use HIGH, MEDIUM, LOW, or INFO for severity; lowercase payments, pricing, inventory, checkout, or unknown for suspectedService; and EVIDENCE FOUND, INCONCLUSIVE, or NO ANOMALY OBSERVED for assessment. "
            + "Keep answer, rootCause, whyItHappened, remediation, telemetrySignal to at most 18 words each. Cite exact source path and lines in rootCause. Use only supplied telemetry. For EVIDENCE FOUND, codeFix must be only a concise compilable snippet in the source language, at most 4 lines; never prose, empty, or N/A. ROOT10 business rule: its storefront label says '10% discount', but the actual deduction is a fixed $10 capped at the subtotal. Do not interpret the label as a percentage calculation or recommend multiplying the subtotal by 0.10. If the retrieved implementation already matches this rule, do not call that implementation defective; identify only a separately evidenced mismatch. When a code snippet is warranted, show the fixed-$10 calculation, rounded to two decimals. This suggestion is illustrative and must not be applied automatically. If evidence is insufficient, set assessment INCONCLUSIVE, severity INFO, service unknown if needed, and codeFix empty. Never invent events, amounts, files, or line numbers.";
        try {
            Map<?, ?> response = chatRest.post().uri("/api/chat")
                .body(Map.of("model", chatModel, "stream", false, "think", false, "format", "json",
                    "options", Map.of("temperature", 0.1, "num_predict", 256),
                    "messages", List.of(Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", "Question: " + question + "\n\nInvestigation context:\n" + investigationContext + "\n\nRetrieved source evidence:\n" + context))))
                .retrieve().body(Map.class);
            Object message = response == null ? null : response.get("message");
            if (!(message instanceof Map<?, ?> map) || !(map.get("content") instanceof String text) || text.isBlank()) {
                throw new IllegalStateException("Ollama returned an empty RCA response.");
            }
            ShopModels.GeneratedRca result = objectMapper.readValue(stripJsonFence(text), ShopModels.GeneratedRca.class);
            if (blank(result.answer()) || blank(result.rootCause()) || blank(result.whyItHappened())
                || blank(result.remediation()) || blank(result.severity()) || blank(result.suspectedService())
                || blank(result.telemetrySignal()) || blank(result.assessment())) {
                throw new IllegalStateException("Ollama RCA response is missing required analysis fields.");
            }
            String severity = result.severity().trim().toUpperCase(Locale.ROOT);
            String service = result.suspectedService().trim().toLowerCase(Locale.ROOT);
            String assessment = result.assessment().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("HIGH", "MEDIUM", "LOW", "INFO").contains(severity)
                || !Set.of("payments", "pricing", "inventory", "checkout", "unknown").contains(service)
                || !Set.of("EVIDENCE FOUND", "INCONCLUSIVE", "NO ANOMALY OBSERVED").contains(assessment)) {
                throw new IllegalStateException("Ollama RCA response contains an invalid severity, service, or assessment value.");
            }
            String codeFix = stripCodeFence(result.codeFix());
            return new ShopModels.GeneratedRca(result.answer(), result.rootCause(), result.whyItHappened(), result.remediation(),
                codeFix, severity, service, result.telemetrySignal(), assessment);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Ollama RCA generation failed: " + safeMessage(exception), exception);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Ollama returned invalid structured RCA JSON.", exception);
        }
    }

    private String stripJsonFence(String value) {
        String result = value.trim();
        if (result.startsWith("```")) result = result.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        return result;
    }

    private String stripCodeFence(String value) {
        String result = value == null ? "" : value.trim();
        if (result.startsWith("```")) result = result.replaceFirst("^```(?:java|javascript|typescript|kotlin)?\\s*", "").replaceFirst("\\s*```$", "");
        return result.trim();
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private boolean matches(String installed, String configured) {
        return installed.equalsIgnoreCase(configured) || installed.toLowerCase().startsWith(configured.toLowerCase() + ":");
    }

    private String baseUrl() { return baseUrl; }
}

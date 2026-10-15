package dev.rootlens.shop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

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
    private volatile Set<String> modelNames = Set.of();
    private volatile long modelsCheckedAt;
    private volatile String lastEmbeddingError = "";
    private final AtomicLong lastEmbeddingNanos = new AtomicLong();

    public LocalOllamaClient(
        @Value("$" + "{rootlens.ollama.base-url:http://localhost:11434}") String baseUrl,
        @Value("$" + "{rootlens.ollama.embedding-model:qwen3-embedding:0.6b}") String embeddingModel,
        @Value("$" + "{rootlens.ollama.chat-model:qwen3:4b}") String chatModel) {
        this.baseUrl = baseUrl;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(90));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        var chatHttp = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var chatFactory = new JdkClientHttpRequestFactory(chatHttp);
        chatFactory.setReadTimeout(Duration.ofSeconds(30));
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

    public Optional<String> generate(String question, List<ShopModels.CodeSource> evidence) {
        if (!chatModelAvailable() || evidence.isEmpty()) return Optional.empty();
        StringBuilder context = new StringBuilder();
        for (ShopModels.CodeSource source : evidence) {
            context.append("\nSOURCE ").append(source.path()).append(":").append(source.startLine()).append("-").append(source.endLine())
                .append(" method ").append(source.symbol()).append("\n").append(source.content()).append("\n");
        }
        if (context.length() > 12_000) context.setLength(12_000);
        String system = "You are RootLens, a code RCA assistant. Answer only from the provided source evidence. "
            + "In a concise developer-focused response, explain the likely cause, why the code produces the observed behavior, and a concrete remediation. Cite exact source paths and line ranges. "
            + "If evidence is insufficient, say what is missing. Do not claim runtime telemetry unless it is included in the context.";
        try {
            Map<?, ?> response = chatRest.post().uri("/api/chat")
                .body(Map.of("model", chatModel, "stream", false, "think", false,
                    "messages", List.of(Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", "Question: " + question + "\n\nRetrieved source evidence:\n" + context))))
                .retrieve().body(Map.class);
            Object message = response == null ? null : response.get("message");
            if (message instanceof Map<?, ?> map && map.get("content") instanceof String text && !text.isBlank()) return Optional.of(text);
        } catch (RestClientException exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private boolean matches(String installed, String configured) {
        return installed.equalsIgnoreCase(configured) || installed.toLowerCase().startsWith(configured.toLowerCase() + ":");
    }

    private String baseUrl() { return baseUrl; }
}

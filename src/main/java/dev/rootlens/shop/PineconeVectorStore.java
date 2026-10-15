package dev.rootlens.shop;

import static dev.rootlens.shop.ShopModels.CodeSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Pinecone integrated-embedding adapter. Secrets are supplied only through environment config. */
@Service
public class PineconeVectorStore {
    private static final String API_VERSION = "2025-10";
    private final String apiKey;
    private final String namespace;
    private final String host;
    private final RestClient rest;
    private volatile String lastError = "";
    private volatile Map<String, Object> cachedStats;
    private volatile long statsLoadedAt;

    public PineconeVectorStore(
        @Value("${rootlens.pinecone.api-key:}") String apiKey,
        @Value("${rootlens.pinecone.host:}") String host,
        @Value("${rootlens.pinecone.namespace:rootlens-dev}") String namespace) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.host = normalizeHost(host);
        this.namespace = namespace;
        this.rest = this.host.isBlank() ? RestClient.create() : RestClient.builder()
            .baseUrl("https://" + this.host).defaultHeader("Api-Key", this.apiKey)
            .defaultHeader("X-Pinecone-Api-Version", API_VERSION).build();
    }

    public boolean configured() { return !apiKey.isBlank() && !host.isBlank() && !namespace.isBlank(); }
    public String namespace() { return namespace; }
    public String host() { return host; }
    public String lastError() { return lastError; }

    public boolean upsert(List<CodeSource> chunks) {
        if (!configured()) return false;
        try {
            for (int start = 0; start < chunks.size(); start += 50) {
                List<CodeSource> batch = chunks.subList(start, Math.min(start + 50, chunks.size()));
                String body = batch.stream().map(this::recordJson).collect(java.util.stream.Collectors.joining("\n"));
                rest.post().uri("/records/namespaces/{namespace}/upsert", namespace)
                    .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                    .body(body.getBytes(StandardCharsets.UTF_8)).retrieve().toBodilessEntity();
            }
            reconcileStaleIds(chunks.stream().map(this::id).collect(java.util.stream.Collectors.toSet()));
            lastError = "";
            cachedStats = null;
            return true;
        } catch (RestClientException exception) {
            lastError = safeMessage(exception);
            return false;
        }
    }

    public List<SemanticMatch> search(String question, int limit) {
        if (!configured()) return List.of();
        try {
            Map<String, Object> query = Map.of("inputs", Map.of("text", question), "top_k", limit);
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("query", query);
            request.put("fields", List.of("text", "path", "symbol", "startLine", "endLine", "content"));
            Map<?, ?> response = rest.post().uri("/records/namespaces/{namespace}/search", namespace)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Map.class);
            Object result = response == null ? null : response.get("result");
            Object hits = result instanceof Map<?, ?> map ? map.get("hits") : null;
            List<SemanticMatch> matches = new ArrayList<>();
            if (hits instanceof List<?> list) for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> hit)) continue;
                Object fieldsRaw = hit.get("fields");
                if (!(fieldsRaw instanceof Map<?, ?> fields)) continue;
                String path = string(fields.get("path"));
                String symbol = string(fields.get("symbol"));
                String content = string(fields.get("content"));
                if (path.isBlank() || symbol.isBlank() || content.isBlank()) continue;
                int startLine = integer(fields.get("startLine"));
                int endLine = integer(fields.get("endLine"));
                double score = hit.get("_score") instanceof Number number ? number.doubleValue() : 0;
                matches.add(new SemanticMatch(path, symbol, startLine, endLine, content, score));
            }
            lastError = "";
            return List.copyOf(matches);
        } catch (RestClientException exception) {
            lastError = safeMessage(exception);
            return List.of();
        }
    }

    public Optional<Map<String, Object>> stats() {
        if (!configured()) return Optional.empty();
        Map<String, Object> cached = cachedStats;
        if (cached != null && System.currentTimeMillis() - statsLoadedAt < 5_000) return Optional.of(cached);
        try {
            Map<String, Object> result = rest.post().uri("/describe_index_stats")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().body(Map.class);
            lastError = "";
            if (result != null) {
                cachedStats = result;
                statsLoadedAt = System.currentTimeMillis();
            }
            return Optional.ofNullable(result);
        } catch (RestClientException exception) {
            lastError = safeMessage(exception);
            return Optional.empty();
        }
    }

    public int dimensions() {
        return stats().map(value -> value.get("dimension") instanceof Number number ? number.intValue() : 1024).orElse(1024);
    }

    public long namespaceCount() {
        return stats().map(value -> namespaceCount(value, namespace)).orElse(0L);
    }

    public long namespaceCount(Map<String, Object> value) { return namespaceCount(value, namespace); }

    private static long namespaceCount(Map<?, ?> value, String namespace) {
        Object namespaces = value.get("namespaces");
        if (namespaces instanceof Map<?, ?> map) {
            Object details = map.get(namespace);
            if (details instanceof Map<?, ?> detail && detail.get("vectorCount") instanceof Number count) return count.longValue();
        }
        return 0L;
    }

    public int dimensions(Map<String, Object> value) {
        return value.get("dimension") instanceof Number number ? number.intValue() : 1024;
    }

    public Map<String, double[]> fetchValues(List<CodeSource> chunks) {
        if (!configured() || chunks.isEmpty()) return Map.of();
        Map<String, double[]> result = new LinkedHashMap<>();
        for (int start = 0; start < chunks.size(); start += 100) {
            List<CodeSource> batch = chunks.subList(start, Math.min(start + 100, chunks.size()));
            try {
                Map<?, ?> response = rest.get().uri(builder -> {
                    // Pinecone's fetch endpoint returns stored values by default. Unlike query,
                    // fetch does not accept an includeValues parameter.
                    builder.path("/vectors/fetch").queryParam("namespace", namespace);
                    batch.stream().map(this::id).forEach(id -> builder.queryParam("ids", id));
                    return builder.build();
                }).retrieve().body(Map.class);
                Object raw = response == null ? null : response.get("vectors");
                if (!(raw instanceof Map<?, ?> valuesById)) continue;
                for (CodeSource chunk : batch) {
                    Object vectorRecord = valuesById.get(id(chunk));
                    if (!(vectorRecord instanceof Map<?, ?> record) || !(record.get("values") instanceof List<?> values)) continue;
                    double[] parsed = new double[values.size()];
                    for (int i = 0; i < values.size(); i++) parsed[i] = ((Number) values.get(i)).doubleValue();
                    result.put(id(chunk), parsed);
                }
            } catch (RestClientException exception) {
                lastError = safeMessage(exception);
                return Map.copyOf(result);
            } catch (ClassCastException exception) {
                lastError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                return Map.copyOf(result);
            }
        }
        return Map.copyOf(result);
    }

    private void reconcileStaleIds(java.util.Set<String> currentIds) {
        String token = null;
        do {
            final String pageToken = token;
            Map<?, ?> page = rest.get().uri(builder -> {
                builder.path("/vectors/list").queryParam("namespace", namespace).queryParam("prefix", "code-").queryParam("limit", 100);
                if (pageToken != null && !pageToken.isBlank()) builder.queryParam("paginationToken", pageToken);
                return builder.build();
            }).retrieve().body(Map.class);
            if (page == null) return;
            Object listed = page.get("vectors");
            List<String> stale = new ArrayList<>();
            if (listed instanceof List<?> list) for (Object item : list) {
                if (item instanceof Map<?, ?> record && record.get("id") instanceof String id && !currentIds.contains(id)) stale.add(id);
            }
            for (int start = 0; start < stale.size(); start += 500) {
                List<String> batch = stale.subList(start, Math.min(start + 500, stale.size()));
                rest.post().uri("/vectors/delete").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("namespace", namespace, "ids", batch)).retrieve().toBodilessEntity();
            }
            Object pagination = page.get("pagination");
            Object next = pagination instanceof Map<?, ?> map ? map.get("next") : null;
            token = next instanceof String value ? value : null;
        } while (token != null && !token.isBlank());
    }

    public boolean readyFor(int chunkCount) { return configured() && namespaceCount() >= chunkCount && chunkCount > 0; }

    public String id(CodeSource chunk) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                (chunk.path() + "|" + chunk.symbol() + "|" + chunk.startLine() + "|" + chunk.content()).getBytes(StandardCharsets.UTF_8));
            return "code-" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String recordJson(CodeSource chunk) {
        return "{\"_id\":" + json(id(chunk)) + ",\"text\":" + json("File: " + chunk.path() + "\nMethod: " + chunk.symbol() + "\n" + chunk.content())
            + ",\"path\":" + json(chunk.path()) + ",\"symbol\":" + json(chunk.symbol())
            + ",\"startLine\":" + chunk.startLine() + ",\"endLine\":" + chunk.endLine()
            + ",\"content\":" + json(chunk.content()) + "}";
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    private static String normalizeHost(String value) {
        if (value == null) return "";
        return value.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int integer(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static String safeMessage(RestClientException exception) {
        String message = exception.getMessage();
        if (message == null) return exception.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    public record SemanticMatch(String path, String symbol, int startLine, int endLine, String content, double score) {}
}

package dev.rootlens.shop;

import static dev.rootlens.shop.ShopModels.CodeSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LocalVectorStore {
    private static final String HEADER = "#rootlens-local-vectors-v1";
    private final Path file;
    private final String model;
    private final Map<String, double[]> vectors = new ConcurrentHashMap<>();

    public LocalVectorStore(@Value("$" + "{rootlens.vector.path:.rootlens/code-vectors.tsv}") String path,
                            LocalOllamaClient ollama) {
        this.file = Path.of(path);
        this.model = ollama.embeddingModel();
        load();
    }

    public synchronized boolean replace(List<CodeSource> chunks, List<double[]> embeddings) {
        if (chunks.size() != embeddings.size() || chunks.isEmpty()) return false;
        Map<String, double[]> next = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) next.put(key(chunks.get(i)), embeddings.get(i));
        vectors.clear();
        vectors.putAll(next);
        persist();
        return true;
    }

    public synchronized boolean upsert(List<CodeSource> chunks, List<double[]> embeddings) {
        if (chunks.size() != embeddings.size() || chunks.isEmpty()) return false;
        for (int i = 0; i < chunks.size(); i++) vectors.put(key(chunks.get(i)), embeddings.get(i));
        persist();
        return true;
    }

    public synchronized void retain(List<CodeSource> chunks) {
        var current = chunks.stream().map(this::key).collect(java.util.stream.Collectors.toSet());
        if (vectors.keySet().retainAll(current)) persist();
    }

    public int size() { return vectors.size(); }
    public int dimensions() { return vectors.values().stream().findFirst().map(v -> v.length).orElse(0); }
    public boolean has(CodeSource chunk) { return vectors.containsKey(key(chunk)); }
    public double[] vector(CodeSource chunk) {
        double[] vector = vectors.get(key(chunk));
        return vector == null ? null : vector.clone();
    }
    public long coveredCount(List<CodeSource> chunks) { return chunks.stream().filter(this::has).count(); }
    public boolean dimensionsConsistent(List<CodeSource> chunks) {
        int expected = -1;
        for (CodeSource chunk : chunks) {
            double[] vector = vectors.get(key(chunk));
            if (vector == null || vector.length == 0) return false;
            if (expected < 0) expected = vector.length;
            if (vector.length != expected) return false;
            for (double value : vector) if (!Double.isFinite(value)) return false;
        }
        return !chunks.isEmpty();
    }
    public boolean compatible(List<CodeSource> chunks, double[] query) {
        if (query == null || query.length == 0 || chunks.isEmpty()) return false;
        for (double value : query) if (!Double.isFinite(value)) return false;
        for (CodeSource chunk : chunks) {
            double[] candidate = vectors.get(key(chunk));
            if (candidate == null || candidate.length != query.length) return false;
            double norm = 0;
            for (double value : candidate) {
                if (!Double.isFinite(value)) return false;
                norm += value * value;
            }
            if (norm == 0) return false;
        }
        return true;
    }
    public double similarity(CodeSource chunk, double[] query) {
        double[] candidate = vectors.get(key(chunk));
        if (candidate == null || candidate.length != query.length) return Double.NaN;
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < candidate.length; i++) {
            if (!Double.isFinite(candidate[i]) || !Double.isFinite(query[i])) return Double.NaN;
            dot += candidate[i] * query[i];
            a += candidate[i] * candidate[i];
            b += query[i] * query[i];
        }
        return a == 0 || b == 0 ? 0 : dot / (Math.sqrt(a) * Math.sqrt(b));
    }

    private String key(CodeSource chunk) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                (chunk.path() + "|" + chunk.symbol() + "|" + chunk.startLine() + "|" + chunk.content()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().equals(HEADER + "\t" + model)) return;
            for (String line : lines.subList(1, lines.size())) {
                String[] fields = line.split("\t", 2);
                if (fields.length != 2) continue;
                String id = fields[0];
                String[] values = fields[1].split(",");
                double[] vector = new double[values.length];
                for (int i = 0; i < values.length; i++) {
                    vector[i] = Double.parseDouble(values[i]);
                    if (!Double.isFinite(vector[i])) throw new NumberFormatException("Non-finite vector value");
                }
                vectors.put(id, vector);
            }
        } catch (IOException | NumberFormatException exception) {
            vectors.clear();
        }
    }

    private synchronized void persist() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            List<String> lines = vectors.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "\t" + java.util.Arrays.stream(entry.getValue()).mapToObj(Double::toString).collect(Collectors.joining(",")))
                .toList();
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            var output = new java.util.ArrayList<String>();
            output.add(HEADER + "\t" + model);
            output.addAll(lines);
            Files.write(temporary, output, StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException unsupportedAtomicMove) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not persist the local vector index at " + file, exception);
        }
    }
}

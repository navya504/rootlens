package dev.rootlens.shop;

import static dev.rootlens.shop.ShopModels.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class CodeKnowledgeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CodeKnowledgeService.class);
    private static final Pattern METHOD = Pattern.compile("^\\s*(?:public|protected|private)\\s+(?:(?:static|final|synchronized)\\s+)*(?:<[\\w, ? extends]+>\\s+)?[\\w.$<>?,\\[\\]]+\\s+(\\w+)\\s*\\(.*$");
    private static final Set<String> STOP_WORDS = Set.of("the", "why", "what", "how", "does", "this", "that", "with", "from", "when", "where", "is", "are", "a", "an", "to", "in", "of", "for", "it", "my", "i", "we");
    private static final Map<String, List<String>> ALIASES = Map.of(
        "twice", List.of("duplicate", "retry", "capture", "idempotency"),
        "charged", List.of("payment", "capture", "retry", "idempotency"),
        "coupon", List.of("discount", "root10", "pricing"),
        "discount", List.of("coupon", "root10", "pricing"),
        "stock", List.of("inventory", "reserve", "available"),
        "oversell", List.of("inventory", "reserve", "available"),
        "checkout", List.of("orders", "payment", "inventory", "pricing")
    );
    private final Path sourceRoot = Path.of("src", "main", "java", "dev", "rootlens", "shop");
    private final LocalOllamaClient ollama;
    private final LocalVectorStore vectors;
    private final PineconeVectorStore pinecone;
    private volatile List<CodeSource> indexedChunks = List.of();

    public CodeKnowledgeService(LocalOllamaClient ollama, LocalVectorStore vectors, PineconeVectorStore pinecone) {
        this.ollama = ollama;
        this.vectors = vectors;
        this.pinecone = pinecone;
    }

    public Map<String, Object> status() {
        List<CodeSource> chunks = index();
        return statusFor(chunks);
    }

    public Map<String, Object> indexInspection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status());
        result.put("chunks", chunkInspection());
        result.put("vectorDetailsEndpoint", "GET /api/rag/chunks/{index}/embedding");
        return result;
    }

    public List<ChunkInspection> chunkInspection() {
        List<CodeSource> chunks = index();
        // Do not fetch every full 1024-dimensional vector for the list page. Load the
        // selected chunk's vector through /rag/chunks/{index}/embedding on demand.
        boolean remoteReady = pinecone.configured() && pinecone.readyFor(chunks.size());
        List<ChunkInspection> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            CodeSource chunk = chunks.get(i);
            double[] vector = pinecone.configured() ? null : vectors.vector(chunk);
            String input = "File: " + chunk.path() + "\nMethod: " + chunk.symbol() + "\n" + chunk.content();
            List<Double> preview = vector == null ? List.of() : java.util.Arrays.stream(vector).limit(16).boxed().toList();
            double norm = 0, minimum = 0, maximum = 0;
            if (vector != null && vector.length > 0) {
                minimum = Double.POSITIVE_INFINITY;
                maximum = Double.NEGATIVE_INFINITY;
                for (double value : vector) {
                    norm += value * value;
                    minimum = Math.min(minimum, value);
                    maximum = Math.max(maximum, value);
                }
                norm = Math.sqrt(norm);
            }
            result.add(new ChunkInspection(i, chunk.path(), chunk.symbol(), chunk.startLine(), chunk.endLine(),
                chunk.content(), input, vector != null || remoteReady, vector == null ? (remoteReady ? pinecone.dimensions() : 0) : vector.length, preview,
                norm, minimum, maximum));
        }
        return List.copyOf(result);
    }

    public EmbeddingDetails chunkEmbedding(int index) {
        List<CodeSource> chunks = index();
        if (index < 0 || index >= chunks.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chunk not found.");
        double[] vector = pinecone.configured()
            ? pinecone.fetchValues(List.of(chunks.get(index))).get(pinecone.id(chunks.get(index)))
            : vectors.vector(chunks.get(index));
        if (vector == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, pinecone.configured()
            ? "This chunk has not been embedded in the configured Pinecone namespace. Reindex the source."
            : "This chunk has not been embedded yet. Reindex after connecting Ollama.");
        return new EmbeddingDetails(pinecone.configured() ? "llama-text-embed-v2 · Pinecone hosted" : ollama.embeddingModel(), vector.length, vector);
    }

    public synchronized Map<String, Object> reindex() {
        indexedChunks = loadChunks();
        if (pinecone.configured()) {
            boolean indexed = pinecone.upsert(indexedChunks);
            return reindexResponse(indexed
                ? "Upserted " + indexedChunks.size() + " code chunks into Pinecone namespace " + pinecone.namespace() + "."
                : "Pinecone indexing failed: " + pinecone.lastError());
        }
        int coveredBefore = (int) vectors.coveredCount(indexedChunks);
        List<CodeSource> missing = indexedChunks.stream().filter(chunk -> !vectors.has(chunk)).toList();
        // Small batches avoid overloading local Ollama when source methods are large; each successful
        // batch is persisted so a later retry resumes from the first unfinished methods.
        for (int start = 0; start < missing.size(); start += 2) {
            List<CodeSource> batch = missing.subList(start, Math.min(start + 2, missing.size()));
            List<double[]> embeddings = embedChunks(batch);
            if (embeddings.size() != batch.size()) {
                return reindexResponse("Vector indexing paused at " + vectors.coveredCount(indexedChunks) + "/" + indexedChunks.size()
                    + " chunks: " + ollama.lastEmbeddingError());
            }
            vectors.upsert(batch, embeddings);
        }
        if (vectors.coveredCount(indexedChunks) == indexedChunks.size()) vectors.retain(indexedChunks);
        int added = Math.max(0, (int) vectors.coveredCount(indexedChunks) - coveredBefore);
        return reindexResponse(missing.isEmpty() ? "Index is current; all " + indexedChunks.size() + " chunks already have vectors."
            : "Indexed " + added + " new or changed chunks; " + vectors.coveredCount(indexedChunks) + "/" + indexedChunks.size() + " vectors ready.");
    }

    private Map<String, Object> reindexResponse(String message) {
        Map<String, Object> result = new LinkedHashMap<>(statusSnapshot());
        result.put("reindexMessage", message);
        return result;
    }

    public Answer ask(String question) {
        return ask(question, "");
    }

    private Answer ask(String question, String investigationContext) {
        if (question == null || question.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask a question about the shop behavior.");
        if (question.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keep the question under 500 characters.");
        List<CodeSource> chunks = index();
        List<CodeSource> candidates = retrievalCandidates(chunks, question);
        List<CodeSource> lexical = rank(candidates, expand(question));
        Map<String, Double> lexicalScores = new HashMap<>();
        lexical.forEach(source -> lexicalScores.put(key(source), source.score()));
        double maxLexical = lexical.stream().mapToDouble(CodeSource::score).max().orElse(0);
        Map<String, Double> semanticScores = new HashMap<>();
        boolean hybrid;
        if (pinecone.configured()) {
            Map<String, CodeSource> byKey = new HashMap<>();
            candidates.forEach(chunk -> byKey.put(key(chunk), chunk));
            for (PineconeVectorStore.SemanticMatch match : pinecone.search(question, 30)) {
                String matchKey = match.path() + "#" + match.symbol() + "#" + match.startLine();
                if (byKey.containsKey(matchKey)) semanticScores.put(matchKey, match.score());
            }
            hybrid = !semanticScores.isEmpty() && pinecone.readyFor(chunks.size());
        } else {
            List<double[]> queryVectors = vectors.coveredCount(chunks) == chunks.size() && !chunks.isEmpty()
                ? ollama.embed(List.of(question)) : List.of();
            double[] queryVector = queryVectors.size() == 1 ? queryVectors.getFirst() : new double[0];
            hybrid = vectors.compatible(chunks, queryVector);
            if (hybrid) for (CodeSource chunk : candidates) {
                double similarity = vectors.similarity(chunk, queryVector);
                if (Double.isFinite(similarity)) semanticScores.put(key(chunk), similarity);
            }
        }
        List<CodeSource> ranked;
        if (hybrid) {
            ranked = candidates.stream().map(chunk -> {
                double lexicalScore = maxLexical == 0 ? 0 : lexicalScores.getOrDefault(key(chunk), 0.0) / maxLexical;
                double semantic = Math.max(0, semanticScores.getOrDefault(key(chunk), 0.0));
                return new CodeSource(chunk.path(), chunk.symbol(), chunk.startLine(), chunk.endLine(), chunk.content(),
                    Math.round((0.45 * lexicalScore + 0.55 * semantic) * 1000.0) / 1000.0);
            }).sorted(Comparator.comparingDouble(CodeSource::score).reversed()).limit(4).toList();
        } else ranked = lexical.stream().limit(4).toList();
        List<VectorPoint> points = vectorPoints(candidates, lexicalScores, maxLexical, semanticScores, hybrid, ranked);
        String retrieval = hybrid
            ? "hybrid retrieval · 45% BM25 + 55% cosine similarity · " + (pinecone.configured() ? "Pinecone llama-text-embed-v2" : ollama.embeddingModel())
            : "local BM25 · method-level source chunks with file and line citations";
        GeneratedRca generated;
        try {
            generated = ollama.generateRca(question, investigationContext, ranked);
        } catch (IllegalStateException exception) {
            LOGGER.error("LLM-generated RCA failed for question: {}", question, exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
        if (generated.codeFix() == null || generated.codeFix().isBlank()) {
            LOGGER.warn("RCA generated without an illustrative code snippet for question: {}", question);
        }
        return new Answer(question, generated.answer(), generated.rootCause(), generated.whyItHappened(), generated.remediation(),
            generated.codeFix() == null ? "" : generated.codeFix(), retrieval, "Ollama · " + ollama.chatModel(),
            excerpts(ranked, question), points, generated.severity(), generated.suspectedService(), generated.telemetrySignal(), generated.assessment());
    }

    public List<BugDefinition> knownBugs() {
        return List.of(
            new BugDefinition("SHOP-PAYMENT-001", "Duplicate payment after timeout", "Payments", "HIGH",
                "A successful capture can be repeated when the gateway response times out.",
                "Place an order of $100 or more", "Why did the customer get charged twice after a payment timeout?"),
            new BugDefinition("SHOP-COUPON-001", "ROOT10 discount mismatch", "Pricing", "MEDIUM",
                "ROOT10 is labelled as a 10% discount, but it deducts a flat $10.",
                "Add the $249 4K Studio Camera and apply ROOT10", "Why was the ROOT10 coupon discount wrong?"),
            new BugDefinition("SHOP-INVENTORY-001", "Inventory is not decremented", "Inventory", "HIGH",
                "Successful reservations leave the available stock count unchanged.",
                "Place repeated orders for the same product", "Why can checkout oversell stock?")
        );
    }

    public BugAnalysis analyzeBug(String bugId) {
        BugDefinition bug = knownBugs().stream().filter(item -> item.id().equalsIgnoreCase(bugId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bug not found."));
        String context = "Known defect: " + bug.title() + " (" + bug.id() + "). Service: " + bug.service()
            + ". Catalog severity: " + bug.severity() + ". Reported behavior: " + bug.summary()
            + ". Reproduction: " + bug.trigger() + ". Assess the report against retrieved source; do not assume the catalog description is proven.";
        Answer answer = ask(bug.question(), context);
        String status = answer.assessment();
        StringBuilder report = new StringBuilder("# ").append(bug.title()).append("\n\n")
            .append("- **Bug ID:** ").append(bug.id()).append("\n- **Service:** ").append(answer.suspectedService())
            .append("\n- **Severity:** ").append(answer.severity()).append("\n- **Assessment:** ").append(status)
            .append("\n- **Retrieval:** ").append(answer.retrieval()).append("\n\n")
            .append("## Root cause\n\n").append(answer.rootCause()).append("\n\n")
            .append("## Why it happened\n\n").append(answer.whyItHappened()).append("\n\n")
            .append("## Remediation\n\n").append(answer.remediation()).append("\n\n")
            .append("## Evidence\n\n");
        if (answer.sources().isEmpty()) report.append("No source evidence was retrieved. Treat this assessment as inconclusive.\n");
        for (CodeSource source : answer.sources()) report.append("- `").append(source.path()).append(":")
            .append(source.startLine()).append("- ").append(source.endLine()).append("` — **")
            .append(source.symbol()).append("**, retrieval score **").append(String.format(Locale.ROOT, "%.3f", source.score()))
            .append("**\n\n  ```java\n  ").append(source.content().replace("\n", "\n  ")).append("\n  ```\n");
        if (!answer.codeFix().isBlank()) report.append("\n## Suggested code change\n\n```java\n")
            .append(answer.codeFix()).append("\n```\n");
        report.append("\n## Retrieval details\n\n").append(answer.answerMode()).append("\n\n")
            .append("Generated by ").append(answer.answerMode()).append(" from retrieved source evidence. Retrieval scores rank evidence; they are not calibrated probabilities.\n");
        return new BugAnalysis(bug.id(), bug.title(), answer.suspectedService(), answer.severity(), status,
            answer.rootCause(), answer.whyItHappened(), answer.remediation(), answer.codeFix(),
            answer.retrieval(), answer.answerMode(), answer.sources(), answer.vectorPoints(), report.toString());
    }

    public CodeGraph graph() {
        List<GraphNode> nodes = new ArrayList<>();
        Map<String, String> descriptions = Map.of(
            "ShopController", "REST endpoints for products, checkout, orders, traces, and developer investigation.",
            "ShopService", "Coordinates checkout, pricing, inventory reservation, payment capture, and order events.",
            "InventoryService", "Checks and reserves available product quantities.",
            "PricingService", "Calculates cart subtotals, discounts, and checkout totals.",
            "PaymentService", "Records mock payment captures and simulates ambiguous gateway timeouts.",
            "ShopModels", "Typed request, response, product, order, and event records shared by the shopping app."
        );
        Map<String, String> displayNames = Map.of(
            "ShopController", "Shopping API",
            "ShopService", "Checkout Service",
            "InventoryService", "Inventory Service",
            "PricingService", "Pricing Service",
            "PaymentService", "Payment Service · Mock",
            "ShopModels", "Order & Product Models"
        );
        Map<String, String> types = Map.of(
            "ShopController", "API", "ShopService", "SERVICE", "InventoryService", "SERVICE",
            "PricingService", "SERVICE", "PaymentService", "SERVICE", "ShopModels", "DOMAIN MODEL"
        );
        for (var entry : descriptions.entrySet()) {
            String fileName = entry.getKey() + ".java";
            Path path = sourceRoot.resolve(fileName);
            try {
                String content = Files.readString(path);
                if (entry.getKey().equals("ShopController")) {
                    content = readChunks(path).stream()
                        .filter(method -> !method.content().contains("knowledge") && !method.content().contains("/rag/"))
                        .map(CodeSource::content).collect(java.util.stream.Collectors.joining("\n\n"));
                } else if (entry.getKey().equals("ShopModels")) {
                    int rcaModelsStart = content.indexOf("    public record CodeSource");
                    if (rcaModelsStart >= 0) content = content.substring(0, rcaModelsStart) + "}";
                }
                nodes.add(new GraphNode(entry.getKey(), displayNames.get(entry.getKey()), types.get(entry.getKey()),
                    "src/main/java/dev/rootlens/shop/" + fileName, entry.getValue(), content));
            } catch (IOException ignored) {
                // A missing source file is omitted from the architecture map rather than represented as a phantom node.
            }
        }
        List<String> componentOrder = List.of("ShopController", "ShopService", "InventoryService", "PricingService", "PaymentService", "ShopModels");
        nodes.sort(Comparator.comparingInt(node -> componentOrder.indexOf(node.id())));
        Set<String> present = nodes.stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
        List<GraphEdge> edges = new ArrayList<>();
        addGraphEdge(edges, present, "ShopController", "ShopService", "calls checkout service", 1.0);
        addGraphEdge(edges, present, "ShopService", "InventoryService", "checks / reserves stock", 1.0);
        addGraphEdge(edges, present, "ShopService", "PricingService", "calculates totals", 1.0);
        addGraphEdge(edges, present, "ShopService", "PaymentService", "captures payment", 1.0);
        for (String component : List.of("ShopController", "ShopService", "InventoryService"))
            addGraphEdge(edges, present, component, "ShopModels", "uses order / product types", 0.78);
        return new CodeGraph(List.copyOf(nodes), List.copyOf(edges));
    }

    private void addGraphEdge(List<GraphEdge> edges, Set<String> present, String source, String target, String relation, double score) {
        if (present.contains(source) && present.contains(target)) edges.add(new GraphEdge(source, target, relation, score));
    }

    public RcaReport investigate(Order order, List<ShopEvent> requestEvents) {
        List<ShopEvent> trace = requestEvents.stream().sorted(Comparator.comparing(ShopEvent::at)).toList();
        String question = "Analyze this shopping order for a defect or unexpected outcome. Explain what the observed events and source code establish, why it happened, and how to remediate it.";
        StringBuilder context = new StringBuilder()
            .append("Order ID: ").append(order.id()).append("\nCorrelation ID: ").append(order.correlationId())
            .append("\nOrder status: ").append(order.status()).append("\nSubtotal: ").append(order.subtotal())
            .append("\nDiscount: ").append(order.discount()).append("\nTotal: ").append(order.total())
            .append("\nPayment capture count: ").append(order.paymentCaptures())
            .append("\nCoupon: ").append(order.couponCode()).append("\nObserved event sequence (timestamp order):\n");
        if (trace.isEmpty()) context.append("No events were recorded for this order.\n");
        for (ShopEvent event : trace) context.append(event.at()).append(" · ").append(event.service()).append(" · ")
            .append(event.event()).append(" · ").append(event.level()).append(" · ").append(event.detail()).append("\n");
        Answer analysis = ask(question, context.toString());
        return new RcaReport(order.id(), order.correlationId(), analysis.severity(), analysis.suspectedService(),
            analysis.telemetrySignal(), analysis.assessment(), analysis.rootCause(), analysis.whyItHappened(), analysis.remediation(),
            analysis.codeFix(), analysis.retrieval(),
            analysis.answerMode(), trace.size(), analysis.sources(), analysis.vectorPoints());
    }

    private Map<String, Object> statusSnapshot() {
        return statusFor(indexedChunks);
    }

    private Map<String, Object> statusFor(List<CodeSource> chunks) {
        if (pinecone.configured()) {
            var stats = pinecone.stats();
            long covered = stats.map(pinecone::namespaceCount).orElse(0L);
            int dimensions = stats.map(pinecone::dimensions).orElse(1024);
            boolean indexed = !chunks.isEmpty() && covered >= chunks.size();
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("name", "RootLens Java source KB");
            state.put("chunks", chunks.size());
            state.put("vectors", Math.min(covered, chunks.size()));
            state.put("vectorsMissing", Math.max(0, chunks.size() - covered));
            state.put("vectorsIndexed", indexed);
            state.put("vectorDimensionsConsistent", indexed && dimensions == 1024);
            state.put("dimensions", dimensions);
            state.put("embeddingModel", "llama-text-embed-v2 · Pinecone hosted");
            state.put("chatModel", ollama.chatModel());
            state.put("vectorStore", "Pinecone");
            state.put("pineconeHost", pinecone.host());
            state.put("pineconeNamespace", pinecone.namespace());
            state.put("pineconeAvailable", stats.isPresent());
            state.put("embeddingModelAvailable", stats.isPresent());
            state.put("embeddingError", pinecone.lastError());
            state.put("ollamaAvailable", ollama.available());
            state.put("chatModelAvailable", ollama.chatModelAvailable());
            boolean ready = indexed && dimensions == 1024 && stats.isPresent();
            state.put("retrieval", ready ? "hybrid BM25 + Pinecone semantic search" : "local BM25 · Pinecone index needs reindex or is unavailable");
            state.put("ready", !chunks.isEmpty());
            state.put("embeddingsReady", ready);
            return state;
        }
        long covered = vectors.coveredCount(chunks);
        Set<String> models = ollama.models();
        boolean vectorsIndexed = !chunks.isEmpty() && covered == chunks.size();
        boolean dimensionsConsistent = vectorsIndexed && vectors.dimensionsConsistent(chunks);
        boolean embeddingModelAvailable = models.stream().anyMatch(name -> name.equalsIgnoreCase(ollama.embeddingModel())
            || name.toLowerCase(Locale.ROOT).startsWith(ollama.embeddingModel().toLowerCase(Locale.ROOT) + ":"));
        boolean embeddingsReady = vectorsIndexed && dimensionsConsistent && embeddingModelAvailable;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", "RootLens Java source KB");
        state.put("vectorStore", "Local TSV");
        state.put("chunks", chunks.size());
        state.put("vectors", covered);
        state.put("vectorsMissing", Math.max(0, chunks.size() - covered));
        state.put("vectorsIndexed", vectorsIndexed);
        state.put("vectorDimensionsConsistent", dimensionsConsistent);
        state.put("dimensions", vectors.dimensions());
        state.put("embeddingModel", ollama.embeddingModel());
        state.put("chatModel", ollama.chatModel());
        state.put("ollamaAvailable", !models.isEmpty());
        state.put("embeddingModelAvailable", embeddingModelAvailable);
        state.put("embeddingError", ollama.lastEmbeddingError());
        state.put("chatModelAvailable", ollama.chatModelAvailable());
        state.put("retrieval", embeddingsReady ? "hybrid BM25 + local vector search" : "local BM25 over method chunks");
        state.put("ready", !chunks.isEmpty());
        state.put("embeddingsReady", embeddingsReady);
        return state;
    }

    private List<double[]> embedChunks(List<CodeSource> chunks) {
        List<String> texts = chunks.stream()
            .map(chunk -> "File: " + chunk.path() + "\nMethod: " + chunk.symbol() + "\n" + chunk.content()).toList();
        return ollama.embed(texts);
    }

    private List<VectorPoint> vectorPoints(List<CodeSource> chunks, Map<String, Double> lexicalScores,
                                           double maxLexical, Map<String, Double> semanticScores,
                                           boolean hybrid, List<CodeSource> selected) {
        Set<String> chosen = selected.stream().map(this::key).collect(java.util.stream.Collectors.toSet());
        return chunks.stream().filter(chunk -> hybrid || lexicalScores.containsKey(key(chunk)))
            .map(chunk -> {
                double lexical = maxLexical == 0 ? 0 : lexicalScores.getOrDefault(key(chunk), 0.0) / maxLexical;
                double cosine = hybrid ? semanticScores.getOrDefault(key(chunk), 0.0) : 0;
                double semanticRelevance = Math.max(0, cosine);
                double score = hybrid ? 0.45 * lexical + 0.55 * semanticRelevance : lexical;
                return new VectorPoint(chunk.path(), chunk.symbol(), round(lexical), round(cosine), round(score), chosen.contains(key(chunk)));
            }).sorted(Comparator.comparingDouble(VectorPoint::hybridScore).reversed()).limit(30).toList();
    }

    private double round(double value) { return Math.round(value * 1000.0) / 1000.0; }

    private String key(CodeSource source) {
        return source.path() + "#" + source.symbol() + "#" + source.startLine();
    }

    private List<CodeSource> excerpts(List<CodeSource> sources, String question) {
        List<String> queryTerms = expand(question);
        return sources.stream().limit(2).map(source -> {
            String[] lines = source.content().split("\\R", -1);
            int bestLine = 0;
            int bestScore = -1;
            for (int i = 0; i < lines.length; i++) {
                int score = lineScore(lines[i], queryTerms);
                if (score > bestScore) { bestScore = score; bestLine = i; }
            }
            int from = Math.max(0, bestLine - 2);
            int to = Math.min(lines.length, bestLine + 3);
            while (from < bestLine && lines[from].isBlank()) from++;
            while (to > bestLine + 1 && lines[to - 1].isBlank()) to--;
            String excerpt = String.join("\n", java.util.Arrays.copyOfRange(lines, from, to));
            if (excerpt.length() > 900) excerpt = excerpt.substring(0, 897) + "...";
            int startLine = source.startLine() + from;
            int endLine = source.startLine() + to - 1;
            return new CodeSource(source.path(), source.symbol(), startLine, endLine, excerpt, source.score());
        }).toList();
    }

    private int lineScore(String line, List<String> queryTerms) {
        if (line.isBlank()) return 0;
        Map<String, Integer> words = frequencies(line);
        return queryTerms.stream().mapToInt(term -> Math.min(words.getOrDefault(term, 0), 2)).sum();
    }

    private List<CodeSource> index() {
        if (!indexedChunks.isEmpty()) return indexedChunks;
        synchronized (this) {
            if (indexedChunks.isEmpty()) indexedChunks = loadChunks();
            return indexedChunks;
        }
    }

    private List<CodeSource> loadChunks() {
        if (!Files.isDirectory(sourceRoot)) return List.of();
        try (var files = Files.list(sourceRoot)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().flatMap(p -> readChunks(p).stream()).toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private List<CodeSource> readChunks(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            String name = file.getFileName().toString();
            String path = sourceRoot.resolve(name).toString().replace('\\', '/');
            List<CodeSource> chunks = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = METHOD.matcher(lines.get(i));
                if (!matcher.matches()) continue;
                int depth = 0;
                boolean opened = false;
                int end = i;
                for (int j = i; j < lines.size(); j++) {
                    String code = lines.get(j).replaceAll("\"(?:\\\\.|[^\"])*\"", "").replaceAll("//.*", "");
                    for (char ch : code.toCharArray()) {
                        if (ch == '{') { depth++; opened = true; }
                        if (ch == '}') depth--;
                    }
                    end = j;
                    if (opened && depth <= 0) break;
                }
                if (opened) {
                    chunks.add(new CodeSource(path, matcher.group(1), i + 1, end + 1,
                        String.join("\n", lines.subList(i, end + 1)), 0));
                    i = end;
                }
            }
            if (chunks.isEmpty() && !lines.isEmpty()) chunks.add(new CodeSource(path, name.replace(".java", ""), 1,
                lines.size(), String.join("\n", lines), 0));
            return chunks;
        } catch (IOException exception) {
            return List.of();
        }
    }

    private List<String> expand(String question) {
        List<String> terms = new ArrayList<>();
        for (String token : question.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() < 2 || STOP_WORDS.contains(token)) continue;
            terms.add(token);
            terms.addAll(ALIASES.getOrDefault(token, List.of()));
        }
        return terms.stream().distinct().toList();
    }

    private List<CodeSource> rank(List<CodeSource> chunks, List<String> queryTerms) {
        if (chunks.isEmpty() || queryTerms.isEmpty()) return List.of();
        List<Map<String, Integer>> frequencies = chunks.stream().map(chunk -> frequencies(chunk.content())).toList();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (Map<String, Integer> frequency : frequencies) for (String term : frequency.keySet()) documentFrequency.merge(term, 1, Integer::sum);
        double averageLength = frequencies.stream().mapToInt(f -> f.values().stream().mapToInt(Integer::intValue).sum()).average().orElse(1);
        List<CodeSource> ranked = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Integer> frequency = frequencies.get(i);
            int length = frequency.values().stream().mapToInt(Integer::intValue).sum();
            double score = 0;
            for (String term : queryTerms) {
                int tf = frequency.getOrDefault(term, 0);
                if (tf == 0) continue;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
                double k1 = 1.2, b = 0.75;
                score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * length / averageLength));
            }
            if (score > 0) {
                CodeSource c = chunks.get(i);
                ranked.add(new CodeSource(c.path(), c.symbol(), c.startLine(), c.endLine(), c.content(), Math.round(score * 100.0) / 100.0));
            }
        }
        ranked.sort(Comparator.comparingDouble(CodeSource::score).reversed());
        return ranked;
    }

    private List<CodeSource> retrievalCandidates(List<CodeSource> chunks, String question) {
        String query = question.toLowerCase(Locale.ROOT);
        boolean shopBehaviorQuestion = List.of("stock", "inventory", "oversell", "coupon", "discount", "root10",
            "payment", "charged", "capture", "checkout", "order").stream().anyMatch(query::contains);
        boolean asksAboutRootLens = List.of("rootlens", "rag", "retrieval", "embedding", "index", "knowledge base", "codeknowledge")
            .stream().anyMatch(query::contains);
        if (!shopBehaviorQuestion || asksAboutRootLens) return chunks;

        // Keep RCA prompts searchable for RootLens questions, but don't let analysis plumbing outrank the storefront implementation.
        return chunks.stream().filter(chunk -> !chunk.path().endsWith("/CodeKnowledgeService.java")).toList();
    }

    private Map<String, Integer> frequencies(String content) {
        String splitCamelCase = content.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        Map<String, Integer> result = new HashMap<>();
        for (String term : splitCamelCase.split("[^a-z0-9]+")) if (term.length() > 1) result.merge(term, 1, Integer::sum);
        return result;
    }


}

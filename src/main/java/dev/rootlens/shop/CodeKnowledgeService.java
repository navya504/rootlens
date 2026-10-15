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

@Service
public class CodeKnowledgeService {
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
    private volatile List<CodeSource> indexedChunks = List.of();

    public CodeKnowledgeService(LocalOllamaClient ollama, LocalVectorStore vectors) {
        this.ollama = ollama;
        this.vectors = vectors;
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
        List<ChunkInspection> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            CodeSource chunk = chunks.get(i);
            double[] vector = vectors.vector(chunk);
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
                chunk.content(), input, vector != null, vector == null ? 0 : vector.length, preview,
                norm, minimum, maximum));
        }
        return List.copyOf(result);
    }

    public EmbeddingDetails chunkEmbedding(int index) {
        List<CodeSource> chunks = index();
        if (index < 0 || index >= chunks.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chunk not found.");
        double[] vector = vectors.vector(chunks.get(index));
        if (vector == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This chunk has not been embedded yet. Reindex after connecting Ollama.");
        return new EmbeddingDetails(ollama.embeddingModel(), vector.length, vector);
    }

    public synchronized Map<String, Object> reindex() {
        indexedChunks = loadChunks();
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
        return ask(question, true);
    }

    private Answer ask(String question, boolean generateWithLocalModel) {
        if (question == null || question.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask a question about the shop behavior.");
        if (question.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keep the question under 500 characters.");
        List<CodeSource> chunks = index();
        List<CodeSource> candidates = retrievalCandidates(chunks, question);
        List<CodeSource> lexical = rank(candidates, expand(question));
        Map<String, Double> lexicalScores = new HashMap<>();
        lexical.forEach(source -> lexicalScores.put(key(source), source.score()));
        double maxLexical = lexical.stream().mapToDouble(CodeSource::score).max().orElse(0);
        List<double[]> queryVectors = vectors.coveredCount(chunks) == chunks.size() && !chunks.isEmpty()
            ? ollama.embed(List.of(question)) : List.of();
        double[] queryVector = queryVectors.size() == 1 ? queryVectors.getFirst() : new double[0];
        boolean hybrid = vectors.compatible(chunks, queryVector);
        if (!hybrid) queryVector = new double[0];
        Map<String, Double> semanticScores = new HashMap<>();
        if (hybrid) for (CodeSource chunk : candidates) {
            double similarity = vectors.similarity(chunk, queryVector);
            if (Double.isFinite(similarity)) semanticScores.put(key(chunk), similarity);
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
            ? "hybrid retrieval · 45% BM25 + 55% cosine similarity · " + ollama.embeddingModel()
            : "local BM25 · method-level source chunks with file and line citations";
        var generated = generateWithLocalModel && !ranked.isEmpty()
            ? ollama.generate(question, ranked) : java.util.Optional.<String>empty();
        Diagnosis guidance = diagnose(question, ranked);
        String answer = generated.orElse(guidance.rootCause() + " " + guidance.whyItHappened() + " " + guidance.remediation());
        return new Answer(question, answer, guidance.rootCause(), guidance.whyItHappened(), guidance.remediation(), guidance.codeFix(), retrieval,
            generated.isPresent() ? "Local model · " + ollama.chatModel()
                : generateWithLocalModel ? "Built-in demo explanation · local chat unavailable or timed out"
                : "Built-in demo explanation · deterministic sample RCA",
            excerpts(ranked, question), points);
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
        Answer answer = ask(bug.question(), false);
        String status = answer.sources().isEmpty() ? "INCONCLUSIVE" : "EVIDENCE FOUND";
        StringBuilder report = new StringBuilder("# ").append(bug.title()).append("\n\n")
            .append("- **Bug ID:** ").append(bug.id()).append("\n- **Service:** ").append(bug.service())
            .append("\n- **Severity:** ").append(bug.severity()).append("\n- **Assessment:** ").append(status)
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
            .append("Generated locally by RootLens from the indexed source chunks. Retrieval scores rank evidence; they are not calibrated probabilities.\n");
        return new BugAnalysis(bug.id(), bug.title(), bug.service(), bug.severity(), status,
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
        boolean duplicatePayment = trace.stream().anyMatch(event -> event.event().equals("payment.retry_captured"))
            || order.paymentCaptures() > 1;
        boolean paymentUncertain = trace.stream().anyMatch(event -> event.event().equals("payment.response_timeout"));
        boolean couponApplied = trace.stream().anyMatch(event -> event.event().equals("coupon.applied")
            && event.detail().toUpperCase(Locale.ROOT).contains("ROOT10"));
        boolean inventoryReserved = trace.stream().anyMatch(event -> event.event().equals("inventory.reserved"));

        String severity = duplicatePayment ? "HIGH" : paymentUncertain ? "MEDIUM" : couponApplied ? "MEDIUM" : "INFO";
        String service = duplicatePayment || paymentUncertain ? "payments" : couponApplied ? "pricing" : inventoryReserved ? "inventory" : "checkout";
        String signal = duplicatePayment
            ? "Telemetry shows an ambiguous payment response followed by a retry capture; this order recorded " + order.paymentCaptures() + " captures."
            : paymentUncertain
                ? "Telemetry shows an ambiguous payment response. Confirm the capture state before retrying."
                : couponApplied
                    ? "Telemetry confirms ROOT10 was applied during checkout. The trace records the actual amount deducted; compare it with the 10% label shown in the cart."
                    : inventoryReserved
                        ? "Telemetry shows inventory accepted for this checkout. The source check can confirm how reservation changes stock."
                        : "No warning or error signal was recorded for this request; showing the checkout flow's source evidence.";
        String question = duplicatePayment || paymentUncertain ? "Why did the customer get charged twice?"
            : couponApplied ? "Why was the ROOT10 coupon discount wrong?"
            : inventoryReserved ? "Why can the shop oversell stock?"
            : "How does checkout process this order?";
        Answer evidence = ask(question, false);
        String rootCause;
        String why;
        String remediation;
        if (duplicatePayment) {
            rootCause = "ShopService charged this order twice: PaymentService captured the $189 payment, its response timed out, and checkout retried with a different idempotency key.";
            why = "The event sequence is checkout started → payment response timeout → retry captured → order confirmed. In this demo, PaymentService simulates that timeout for orders of $100 or more, after it has already recorded the first capture. A timeout means the caller did not receive a timely response; it does not mean the gateway rolled back the capture. ShopService then changes the key from `orderId-payment` to `orderId-payment-retry`, so the gateway treats the retry as a new charge. The two entries in the payment ledger confirm the duplicate.";
            remediation = "Use one stable idempotency key for every attempt belonging to this order. After a timeout, query or reconcile the gateway by that key before retrying. If the gateway confirms a capture, continue without charging again; if the result remains unknown, keep the order pending and reconcile asynchronously. Add a unique database constraint for the order/payment key so concurrent retries cannot create duplicate captures.";
        } else if (paymentUncertain) {
            rootCause = "The payment outcome is unknown because the gateway response timed out; this trace does not prove whether the charge was captured.";
            why = "A timeout is a communication failure between the application and gateway, not a definitive payment failure. If checkout treats it as failed and sends a new payment with a new key, the first charge may later settle too, producing a duplicate. The current trace has no retry capture, so this is a risk rather than a confirmed duplicate.";
            remediation = "Keep the order in a pending-payment state. Reconcile the gateway using the original idempotency key, then mark the order paid or failed from the authoritative gateway result. Do not create another key while the first attempt is unresolved.";
        } else if (couponApplied) {
            rootCause = "ROOT10 is presented in the cart with a ‘10% discount’ label, but the applied coupon rule deducts a flat $10.";
            why = "The coupon label describes a percentage offer while PricingService applies a fixed-dollar amount. The checkout trace shows the actual $10 deduction; this is misleading offer copy, not a frontend calculation error or a rounding difference.";
            remediation = "Make ROOT10 a true 10% discount in PricingService by calculating 10% of the subtotal, capped at the subtotal. Use that single calculated amount for the cart display, order total, and checkout confirmation; for a $249 subtotal, all three should show $24.90 off.";
        } else if (inventoryReserved) {
            rootCause = "InventoryService.reserve() accepts the item after checking quantity, but it never records the reservation or reduces available stock.";
            why = "The trace confirms inventory accepted this checkout. In the implementation, reserve() reads available(product.id()) and returns whether it is large enough, but there is no write that subtracts quantity. A later checkout therefore reads the original count and is accepted too. Two simultaneous requests can both read the same count and both pass, even if a decrement is added after the check; the check and update must be atomic. This order’s telemetry confirms acceptance, while the source code explains the oversell risk.";
            remediation = "Replace the check-only path with an atomic conditional stock decrement. Reject the order when the update affects zero rows or insufficient stock remains. Keep a reservation ID so it can be released if payment fails; in a multi-instance deployment, enforce this in the database rather than with an in-memory map.";
        } else {
            rootCause = "This request trace contains no clear failure signal.";
            why = "The observed events show the checkout flow, but they do not identify a failed or inconsistent outcome for this order.";
            remediation = "Compare the expected outcome with the order and payment records, then add a structured error event at the first point where they diverge.";
        }
        String codeFix = evidence.codeFix();
        return new RcaReport(order.id(), order.correlationId(), severity, service, signal, rootCause, why, remediation,
            codeFix, evidence.retrieval(), "Telemetry rules · " + evidence.answerMode(), trace.size(), evidence.sources(), evidence.vectorPoints());
    }

    private Map<String, Object> statusSnapshot() {
        return statusFor(indexedChunks);
    }

    private Map<String, Object> statusFor(List<CodeSource> chunks) {
        long covered = vectors.coveredCount(chunks);
        Set<String> models = ollama.models();
        boolean vectorsIndexed = !chunks.isEmpty() && covered == chunks.size();
        boolean dimensionsConsistent = vectorsIndexed && vectors.dimensionsConsistent(chunks);
        boolean embeddingModelAvailable = models.stream().anyMatch(name -> name.equalsIgnoreCase(ollama.embeddingModel())
            || name.toLowerCase(Locale.ROOT).startsWith(ollama.embeddingModel().toLowerCase(Locale.ROOT) + ":"));
        boolean embeddingsReady = vectorsIndexed && dimensionsConsistent && embeddingModelAvailable;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", "RootLens Java source KB");
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

        // The RCA service contains canned sample questions and explanations. Keep it searchable for RootLens questions,
        // but don't let those prompt strings outrank the storefront implementation for shop-behavior investigations.
        return chunks.stream().filter(chunk -> !chunk.path().endsWith("/CodeKnowledgeService.java")).toList();
    }

    private Map<String, Integer> frequencies(String content) {
        String splitCamelCase = content.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        Map<String, Integer> result = new HashMap<>();
        for (String term : splitCamelCase.split("[^a-z0-9]+")) if (term.length() > 1) result.merge(term, 1, Integer::sum);
        return result;
    }

    private Diagnosis diagnose(String question, List<CodeSource> sources) {
        if (sources.isEmpty()) return new Diagnosis(
            "No matching source passage was retrieved, so RootLens cannot support a code-level root cause yet.",
            "The query may use different terms from the indexed methods, or the relevant behavior may not be in the indexed source set.",
            "Try a specific service or behavior such as coupon pricing, payment retry, stock reservation, or checkout; then inspect the cited chunks before acting.", "");
        String q = question.toLowerCase(Locale.ROOT);
        if ((q.contains("coupon") || q.contains("discount") || q.contains("root10")) && sources.stream().anyMatch(s -> s.symbol().equals("discount"))) {
            return new Diagnosis(
                "ROOT10 is labelled ‘10% discount’ in the cart, but PricingService.discount() applies a fixed $10. The issue is that the offer wording does not match the amount deducted.",
                "The cart intentionally presents ROOT10 as a 10% discount, while PricingService uses a flat $10 rule. A $249 order therefore shows a $10 deduction, not $24.90. This is a mismatch in the coupon label and applied amount, not a separate preview calculation or a rounding difference.",
                "Make ROOT10 a true 10% discount in PricingService by calculating 10% of the subtotal, capped at the subtotal. Use that single calculated amount for the cart display, order total, and checkout confirmation; for a $249 subtotal, all three should show $24.90 off.",
                "const discount = couponApplied ? subtotal * 0.1 : 0;\nreturn { subtotal, discount, total: subtotal - discount };");
        }
        if ((q.contains("twice") || q.contains("duplicate") || q.contains("payment") || q.contains("charged")) && sources.stream().anyMatch(s -> s.symbol().equals("checkout") || s.symbol().equals("capture"))) {
            return new Diagnosis(
                "ShopService can charge the same order twice because its retry uses a different idempotency key from the original payment attempt.",
                "The first gateway call may capture the money and then time out before the application receives the response. The catch block treats that missing response as a reason to retry. Since it changes the key from `orderId-payment` to `orderId-payment-retry`, the gateway sees a new payment and creates a second capture. A timeout is an unknown outcome, not proof that the first capture failed.",
                "Keep one stable key per order/payment. After a timeout, look up the gateway result by that key before attempting another capture. If captured, continue without charging again; if not found, retry with the same key; if still unknown, leave the order pending for reconciliation. Enforce uniqueness for the key in the payment store so concurrent retries are deduplicated too.",
                "String key = orderId + \"-payment\"; // same key for every attempt\nPaymentStatus status = gateway.lookup(key);\nif (status == PaymentStatus.CAPTURED) {\n    markPaid(orderId);\n} else if (status == PaymentStatus.NOT_FOUND) {\n    gateway.capture(orderId, total, key);\n} else {\n    markPendingForReconciliation(orderId, key);\n}");
        }
        if ((q.contains("stock") || q.contains("inventory") || q.contains("oversell")) && sources.stream().anyMatch(s -> s.symbol().equals("reserve") || s.symbol().equals("available"))) {
            return new Diagnosis(
                "InventoryService.reserve() approves the checkout after a stock check but never records or subtracts the reserved quantity. The inventory count therefore remains unchanged.",
                "The method currently returns `available(product.id()) >= quantity` directly. That expression only answers whether the current count is sufficient; it does not mutate the count. Every later checkout reads the same count and can also be accepted. Simply adding a separate decrement after this check is still unsafe under concurrency: two requests can both read the same quantity before either writes. The read/check/update must be one atomic operation. The selected order's `inventory.reserved` event confirms this request passed the check; the code explains why repeated or concurrent checkouts can oversell.",
                "Make the inventory map mutable and perform the conditional decrement atomically. Reject non-positive quantities and requests larger than the remaining stock. Release the quantity if a later payment step fails. For a multi-instance production service, use a database conditional update such as `UPDATE inventory SET available = available - :qty WHERE product_id = :id AND available >= :qty` and require exactly one updated row.",
                "import java.util.concurrent.ConcurrentHashMap;\n\nprivate final Map<String, Integer> available = new ConcurrentHashMap<>(Map.of(\n    \"audio-pro\", 3, \"mechanical-keys\", 8,\n    \"desk-light\", 5, \"webcam-4k\", 2));\n\npublic synchronized boolean reserve(Product product, int quantity) {\n    int onHand = available(product.id());\n    if (quantity <= 0 || onHand < quantity) return false;\n    available.put(product.id(), onHand - quantity);\n    return true;\n}");
        }
        return new Diagnosis(
            "Related code was retrieved, but the evidence does not establish a specific defect for this question.",
            "The cited methods show the relevant execution path. Compare their conditions and state changes with the behavior you observed; a source match alone does not prove a runtime failure.",
            "Share the affected order or request trace, or narrow the question to a concrete behavior. RootLens can then connect telemetry to the cited code path.", "");
    }

    private record Diagnosis(String rootCause, String whyItHappened, String remediation, String codeFix) {}
}

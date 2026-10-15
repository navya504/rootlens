package dev.rootlens.shop;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ShopModels {
    private ShopModels() {}

    public record Product(String id, String name, String category, String description, BigDecimal price,
                          int available, String color, String badge) {}
    public record CartItem(String productId, int quantity) {}
    public record CheckoutRequest(List<CartItem> items, String couponCode) {}
    public record CheckoutResult(String orderId, String correlationId, String status, BigDecimal subtotal,
                                 BigDecimal discount, BigDecimal total, int paymentCaptures, List<String> notices) {}
    public record Order(String id, String correlationId, Instant createdAt, String status, BigDecimal subtotal,
                        BigDecimal discount, BigDecimal total, int paymentCaptures, List<CartItem> items,
                        String couponCode) {}
    public record ShopEvent(String id, String correlationId, Instant at, String orderId, String service,
                            String event, String detail, String level) {}
    public record CodeSource(String path, String symbol, int startLine, int endLine, String content, double score) {}
    public record ChunkInspection(int index, String path, String symbol, int startLine, int endLine,
                                  String content, String embeddingInput, boolean embedded, int dimensions,
                                  List<Double> preview, double norm, double minimum, double maximum) {}
    public record EmbeddingDetails(String model, int dimensions, double[] values) {}
    public record VectorPoint(String path, String symbol, double lexicalScore, double embeddingSimilarity,
                              double hybridScore, boolean selected) {}
    public record BugDefinition(String id, String title, String service, String severity, String summary,
                                String trigger, String question) {}
    public record BugAnalysis(String bugId, String title, String service, String severity, String status,
                              String rootCause, String whyItHappened, String remediation, String codeFix,
                              String retrieval, String answerMode, List<CodeSource> sources,
                              List<VectorPoint> vectorPoints, String reportMarkdown) {}
    public record GraphNode(String id, String label, String type, String path,
                            String description, String content) {}
    public record GraphEdge(String source, String target, String relation, double score) {}
    public record CodeGraph(List<GraphNode> nodes, List<GraphEdge> edges) {}
    public record Answer(String query, String answer, String rootCause, String whyItHappened, String remediation,
                         String codeFix,
                         String retrieval, String answerMode, List<CodeSource> sources,
                         List<VectorPoint> vectorPoints) {}
    public record RcaReport(String orderId, String correlationId, String severity, String suspectedService,
                            String telemetrySignal, String rootCause, String whyItHappened, String remediation, String codeFix,
                            String retrieval, String answerMode, int traceEventCount, List<CodeSource> sources,
                            List<VectorPoint> vectorPoints) {}
}

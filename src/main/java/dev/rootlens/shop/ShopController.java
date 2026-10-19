package dev.rootlens.shop;

import static dev.rootlens.shop.ShopModels.*;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ShopController {
    private final ShopService shop;
    private final CodeKnowledgeService knowledge;

    public ShopController(ShopService shop, CodeKnowledgeService knowledge) {
        this.shop = shop;
        this.knowledge = knowledge;
    }

    @GetMapping("/shop/products")
    public List<Product> products() { return shop.products(); }

    @GetMapping("/shop/orders")
    public List<Order> orders() { return shop.orders(); }

    @GetMapping("/shop/events")
    public List<ShopEvent> events() { return shop.events(); }

    @PostMapping("/shop/checkout")
    public CheckoutResult checkout(@RequestBody CheckoutRequest request) { return shop.checkout(request); }

    @GetMapping("/shop/orders/{id}")
    public Order order(@PathVariable String id) {
        return shop.orders().stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }

    @GetMapping("/shop/orders/{id}/events")
    public List<ShopEvent> orderEvents(@PathVariable String id) { return shop.eventsFor(id); }

    @GetMapping("/shop/orders/{id}/captures")
    public List<PaymentService.Capture> captures(@PathVariable String id) { return shop.capturesFor(id); }

    @GetMapping("/shop/orders/{id}/rca")
    public RcaReport orderRca(@PathVariable String id) {
        Order order = shop.orders().stream().filter(o -> o.id().equals(id)).findFirst()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Order not found."));
        return knowledge.investigate(order, shop.eventsFor(id));
    }

    @GetMapping("/rag/status")
    public Map<String, Object> knowledgeStatus() { return knowledge.status(); }

    @GetMapping("/rag/index")
    public Map<String, Object> indexInspection() { return knowledge.indexInspection(); }

    @GetMapping("/bugs")
    public List<BugDefinition> knownBugs() { return knowledge.knownBugs(); }

    @GetMapping("/bugs/{id}/analyze")
    public BugAnalysis analyzeBug(@PathVariable String id) { return knowledge.analyzeBug(id); }

    // Apply-fix is intentionally disabled: analysis suggestions must not edit source files on the running server.

    @PostMapping("/demo/bugs/{id}/reproduce")
    public CheckoutResult reproduceDefect(@PathVariable String id) {
        String bugId = id.toUpperCase();
        CartItem item = switch (bugId) {
            case "SHOP-PAYMENT-001" -> new CartItem("audio-pro", 1);
            case "SHOP-COUPON-001" -> new CartItem("webcam-4k", 1);
            case "SHOP-INVENTORY-001" -> new CartItem("desk-light", 1);
            default -> throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Demo defect not found.");
        };
        CheckoutRequest request = new CheckoutRequest(List.of(item), bugId.equals("SHOP-COUPON-001") ? "ROOT10" : null);
        return bugId.equals("SHOP-PAYMENT-001") ? shop.reproducePaymentDefect(request) : shop.checkout(request);
    }

    @GetMapping("/rag/graph")
    public CodeGraph codeGraph() { return knowledge.graph(); }

    @GetMapping("/rag/chunks")
    public List<ChunkInspection> indexedChunks() { return knowledge.chunkInspection(); }

    @GetMapping("/rag/chunks/{index}/embedding")
    public EmbeddingDetails chunkEmbedding(@PathVariable int index) { return knowledge.chunkEmbedding(index); }

    @PostMapping("/rag/reindex")
    public Map<String, Object> reindexKnowledge() { return knowledge.reindex(); }

    @PostMapping("/rag/ask")
    public Answer ask(@RequestBody AskRequest request) { return knowledge.ask(request.question()); }

    public record AskRequest(String question) {}
}

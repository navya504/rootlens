package dev.rootlens.shop;

import static dev.rootlens.shop.ShopModels.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.rootlens.shop.PaymentService.AmbiguousPaymentException;

@Service
public class ShopService {
    private final InventoryService inventory;
    private final PricingService pricing;
    private final PaymentService payments;
    private final Map<String, Product> catalog = new ConcurrentHashMap<>();
    private final List<Order> orders = new CopyOnWriteArrayList<>();
    private final List<ShopEvent> events = new CopyOnWriteArrayList<>();

    public ShopService(InventoryService inventory, PricingService pricing, PaymentService payments) {
        this.inventory = inventory;
        this.pricing = pricing;
        this.payments = payments;
        add("audio-pro", "Studio Headphones", "Audio", "Immersive sound, all-day comfort, and a clean matte finish.", "189.00", "#c49b78", "BEST SELLER");
        add("mechanical-keys", "Mechanical Keyboard", "Workspace", "Tactile low-profile switches in a compact aluminum body.", "129.00", "#6f8b9e", "NEW");
        add("desk-light", "Ambient Desk Light", "Workspace", "Warm adjustable light for focused work and late-night builds.", "64.00", "#d7b563", "STAFF PICK");
        add("webcam-4k", "4K Studio Camera", "Video", "A crisp image and natural color for calls, demos, and streams.", "249.00", "#8b9b88", "LIMITED STOCK");
    }

    private void add(String id, String name, String category, String description, String price, String color, String badge) {
        catalog.put(id, new Product(id, name, category, description, new BigDecimal(price), inventory.available(id), color, badge));
    }

    public List<Product> products() { return catalog.values().stream().sorted(Comparator.comparing(Product::id)).toList(); }
    public List<Order> orders() { return orders.stream().sorted(Comparator.comparing(Order::createdAt).reversed()).toList(); }
    public List<ShopEvent> events() { return events.stream().sorted(Comparator.comparing(ShopEvent::at).reversed()).limit(100).toList(); }
    public List<ShopEvent> eventsFor(String orderId) { return events().stream().filter(e -> orderId.equals(e.orderId())).toList(); }
    public List<PaymentService.Capture> capturesFor(String orderId) { return payments.allCaptures().stream().filter(c -> orderId.equals(c.orderId())).toList(); }

    public synchronized CheckoutResult checkout(CheckoutRequest request) {
        return checkout(request, false);
    }

    public synchronized CheckoutResult reproducePaymentDefect(CheckoutRequest request) {
        return checkout(request, true);
    }

    private CheckoutResult checkout(CheckoutRequest request, boolean simulatePaymentTimeout) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your cart is empty.");
        }
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        List<String> notices = new ArrayList<>();
        event(correlationId, orderId, "storefront", "checkout.started", "Checkout submitted.", "INFO");
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem item : request.items()) {
            Product product = catalog.get(item.productId());
            if (product == null || item.quantity() < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cart item.");
            if (!inventory.reserve(product, item.quantity())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough stock for " + product.name());
            subtotal = subtotal.add(pricing.subtotal(product.price(), item.quantity()));
            event(correlationId, orderId, "inventory", "inventory.reserved", item.quantity() + " × " + product.name() + " accepted.", "INFO");
        }
        BigDecimal discount = pricing.discount(subtotal, request.couponCode());
        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(2);
        if (request.couponCode() != null && !request.couponCode().isBlank()) {
            event(correlationId, orderId, "pricing", "coupon.applied", "Coupon " + request.couponCode() + " applied; discount " + discount, "INFO");
        }
        try {
            payments.capture(orderId, total, orderId + "-payment", 1, simulatePaymentTimeout);
            event(correlationId, orderId, "payments", "payment.captured", "Payment captured successfully.", "INFO");
        } catch (AmbiguousPaymentException ambiguous) {
            event(correlationId, orderId, "payments", "payment.response_timeout", ambiguous.getMessage(), "WARN");
            notices.add("Payment processing took longer than usual. Your order is confirmed.");
            // Intentional demo defect: retry changes the idempotency key, so an ambiguous first capture is charged again.
            payments.capture(orderId, total, orderId + "-payment-retry", 2, simulatePaymentTimeout);
            event(correlationId, orderId, "payments", "payment.retry_captured", "Retry used a different key; a second capture was recorded.", "ERROR");
        }
        int captureCount = payments.capturesFor(orderId);
        orders.add(new Order(orderId, correlationId, Instant.now(), "CONFIRMED", subtotal, discount, total, captureCount,
            List.copyOf(request.items()), request.couponCode()));
        event(correlationId, orderId, "orders", "order.confirmed", "Order confirmed for " + total + ".", "INFO");
        return new CheckoutResult(orderId, correlationId, "CONFIRMED", subtotal, discount, total, captureCount, notices);
    }

    private void event(String correlationId, String orderId, String service, String name, String detail, String level) {
        events.add(new ShopEvent(UUID.randomUUID().toString().substring(0, 8), correlationId, Instant.now(), orderId, service, name, detail, level));
    }
}

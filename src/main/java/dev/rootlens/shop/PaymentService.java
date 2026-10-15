package dev.rootlens.shop;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    private final Map<String, Integer> capturesByOrder = new ConcurrentHashMap<>();
    private final List<Capture> captures = new CopyOnWriteArrayList<>();

    public void capture(String orderId, BigDecimal amount, String idempotencyKey, int attempt, boolean simulateAmbiguousTimeout) {
        captures.add(new Capture(orderId, amount, idempotencyKey, Instant.now()));
        capturesByOrder.merge(orderId, 1, Integer::sum);
        // Inject the timeout only for the payment-defect scenario, never as a side effect of a coupon demo.
        if (simulateAmbiguousTimeout && amount.compareTo(new BigDecimal("100.00")) >= 0 && attempt == 1) {
            throw new AmbiguousPaymentException("The gateway captured the payment, but its response timed out.");
        }
    }

    public int capturesFor(String orderId) { return capturesByOrder.getOrDefault(orderId, 0); }
    public List<Capture> allCaptures() { return List.copyOf(captures); }

    public record Capture(String orderId, BigDecimal amount, String idempotencyKey, Instant createdAt) {}
    public static class AmbiguousPaymentException extends RuntimeException {
        public AmbiguousPaymentException(String message) { super(message); }
    }
}

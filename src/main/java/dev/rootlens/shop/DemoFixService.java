// package dev.rootlens.shop;
//
// import java.io.IOException;
// import java.nio.charset.StandardCharsets;
// import java.nio.file.AtomicMoveNotSupportedException;
// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.nio.file.StandardCopyOption;
// import java.util.ArrayList;
// import java.util.LinkedHashMap;
// import java.util.List;
// import java.util.Map;
//
// import org.springframework.stereotype.Service;
// import org.springframework.web.server.ResponseStatusException;
// import org.springframework.http.HttpStatus;
//
// /**
//  * Retained as a reference for the former local demo patch workflow.
//  * Disabled: source edits must be reviewed and applied outside the running application.
//  */
// // @Service
// public class DemoFixService {
//     private record Edit(String file, String before, String after) {}
//
//     public synchronized Map<String, Object> apply(String bugId) {
//         List<Edit> edits = switch (bugId.toUpperCase()) {
//             case "SHOP-COUPON-001" -> List.of(
//                 new Edit("src/main/resources/static/index.html",
//                     "const discount=couponApplied?subtotal*.1:0;",
//                     "const discount=couponApplied?Math.min(10,subtotal):0;"),
//                 new Edit("src/main/resources/static/index.html",
//                     "ROOT10 applied: 10% off your bag.", "ROOT10 applied: $10 off your bag."));
//             case "SHOP-PAYMENT-001" -> List.of(
//                 new Edit("src/main/java/dev/rootlens/shop/ShopService.java",
//                     "orderId + \"-payment-retry\", 2", "orderId + \"-payment\", 2"),
//                 new Edit("src/main/java/dev/rootlens/shop/ShopService.java",
//                     "\"payment.retry_captured\", \"Retry used a different key; a second capture was recorded.\", \"ERROR\"",
//                     "\"payment.retry_deduplicated\", \"Retry reused the original key; no additional capture was created.\", \"INFO\""),
//                 new Edit("src/main/java/dev/rootlens/shop/PaymentService.java",
//                     "    private final List<Capture> captures = new CopyOnWriteArrayList<>();",
//                     "    private final List<Capture> captures = new CopyOnWriteArrayList<>();\n    private final Map<String, Capture> capturesByKey = new ConcurrentHashMap<>();"),
//                 new Edit("src/main/java/dev/rootlens/shop/PaymentService.java",
//                     String.join("\n",
//                         "    public void capture(String orderId, BigDecimal amount, String idempotencyKey, int attempt) {",
//                         "        captures.add(new Capture(orderId, amount, idempotencyKey, Instant.now()));",
//                         "        capturesByOrder.merge(orderId, 1, Integer::sum);",
//                         "        // Simulate an ambiguous gateway timeout after the first capture of orders over $100.",
//                         "        if (amount.compareTo(new BigDecimal(\"100.00\")) >= 0 && attempt == 1) {",
//                         "            throw new AmbiguousPaymentException(\"The gateway captured the payment, but its response timed out.\");",
//                         "        }", "    }"),
//                     String.join("\n",
//                         "    public void capture(String orderId, BigDecimal amount, String idempotencyKey, int attempt) {",
//                         "        Capture capture = new Capture(orderId, amount, idempotencyKey, Instant.now());",
//                         "        Capture existing = capturesByKey.putIfAbsent(idempotencyKey, capture);",
//                         "        if (existing != null) return;",
//                         "        captures.add(capture);",
//                         "        capturesByOrder.merge(orderId, 1, Integer::sum);",
//                         "        // Simulate an ambiguous gateway timeout after the first capture of orders over $100.",
//                         "        if (amount.compareTo(new BigDecimal(\"100.00\")) >= 0 && attempt == 1) {",
//                         "            throw new AmbiguousPaymentException(\"The gateway captured the payment, but its response timed out.\");",
//                         "        }", "    }")));
//             case "SHOP-INVENTORY-001" -> List.of(
//                 new Edit("src/main/java/dev/rootlens/shop/InventoryService.java",
//                     "private final Map<String, Integer> available = Map.of(",
//                     "private final Map<String, Integer> available = new java.util.concurrent.ConcurrentHashMap<>(Map.of("),
//                 new Edit("src/main/java/dev/rootlens/shop/InventoryService.java",
//                     "\"audio-pro\", 3, \"mechanical-keys\", 8, \"desk-light\", 5, \"webcam-4k\", 2\n    );",
//                     "\"audio-pro\", 3, \"mechanical-keys\", 8, \"desk-light\", 5, \"webcam-4k\", 2\n    ));"),
//                 new Edit("src/main/java/dev/rootlens/shop/InventoryService.java",
//                     String.join("\n", "    public boolean reserve(Product product, int quantity) {",
//                         "        // Intentional demo defect: availability is checked, but the inventory count is never decremented.",
//                         "        return available(product.id()) >= quantity;", "    }"),
//                     String.join("\n", "    public synchronized boolean reserve(Product product, int quantity) {",
//                         "        if (quantity <= 0 || available(product.id()) < quantity) return false;",
//                         "        available.put(product.id(), available(product.id()) - quantity);", "        return true;", "    }")));
//             default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No allow-listed demo fix exists for that defect.");
//         };
//
//         Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
//         Map<Path, String> updated = new LinkedHashMap<>();
//         Map<Path, String> originals = new LinkedHashMap<>();
//         List<String> changed = new ArrayList<>();
//         try {
//             for (Edit edit : edits) {
//                 Path source = root.resolve(edit.file()).normalize();
//                 if (!source.startsWith(root)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid patch target.");
//                 String content = updated.getOrDefault(source, Files.readString(source, StandardCharsets.UTF_8));
//                 if (content.contains(edit.before())) {
//                     originals.putIfAbsent(source, content);
//                     content = content.replace(edit.before(), edit.after());
//                     updated.put(source, content);
//                     changed.add(edit.file());
//                 } else if (!content.contains(edit.after())) {
//                     throw new ResponseStatusException(HttpStatus.CONFLICT,
//                         "Source has changed since this demo fix was prepared. Review the current code before applying it.");
//                 }
//             }
//             // The application serves static assets from target/classes during a local demo.
//             Path sourcePage = root.resolve("src/main/resources/static/index.html");
//             Path deployedPage = root.resolve("target/classes/static/index.html");
//             if (updated.containsKey(sourcePage) && Files.exists(deployedPage)) {
//                 originals.putIfAbsent(deployedPage, Files.readString(deployedPage, StandardCharsets.UTF_8));
//                 updated.put(deployedPage, updated.get(sourcePage));
//                 changed.add("target/classes/static/index.html");
//             }
//             if (changed.isEmpty()) return Map.of("status", "ALREADY_APPLIED", "files", List.of(), "message", "This exact demo fix is already present in source.");
//             Map<Path, Path> staged = new LinkedHashMap<>();
//             for (var item : updated.entrySet()) {
//                 Path temp = Files.createTempFile(item.getKey().getParent(), "rootlens-fix-", ".tmp");
//                 Files.writeString(temp, item.getValue(), StandardCharsets.UTF_8);
//                 staged.put(item.getKey(), temp);
//             }
//             List<Path> committed = new ArrayList<>();
//             try {
//                 for (var item : staged.entrySet()) {
//                     try { Files.move(item.getValue(), item.getKey(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
//                     catch (AtomicMoveNotSupportedException e) { Files.move(item.getValue(), item.getKey(), StandardCopyOption.REPLACE_EXISTING); }
//                     committed.add(item.getKey());
//                 }
//             } catch (IOException failure) {
//                 for (Path path : committed) Files.writeString(path, originals.get(path), StandardCharsets.UTF_8);
//                 throw failure;
//             } finally {
//                 for (Path temp : staged.values()) Files.deleteIfExists(temp);
//             }
//         } catch (IOException e) {
//             throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not apply the demo patch: " + e.getMessage(), e);
//         }
//         return Map.of("status", "APPLIED", "files", changed.stream().distinct().toList(),
//             "message", "Allow-listed source patch applied. Rebuild and restart to load Java changes; static storefront fixes are active immediately.");
//     }
// }

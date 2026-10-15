package dev.rootlens.shop;

import java.util.Map;

import org.springframework.stereotype.Service;

import dev.rootlens.shop.ShopModels.Product;

@Service
public class InventoryService {
    private final Map<String, Integer> available = Map.of(
        "audio-pro", 3, "mechanical-keys", 8, "desk-light", 5, "webcam-4k", 2
    );

    public int available(String productId) { return available.getOrDefault(productId, 0); }

    public boolean reserve(Product product, int quantity) {
        // Intentional demo defect: availability is checked, but the inventory count is never decremented.
        return available(product.id()) >= quantity;
    }
}

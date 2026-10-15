package dev.rootlens.shop;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

@Service
public class PricingService {
    public BigDecimal subtotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal discount(BigDecimal subtotal, String couponCode) {
        if (couponCode == null || !couponCode.trim().equalsIgnoreCase("ROOT10")) return BigDecimal.ZERO.setScale(2);
        // ROOT10 is a flat $10 discount, capped at the subtotal.
        return subtotal.min(new BigDecimal("10.00")).setScale(2, RoundingMode.HALF_UP);
    }
}

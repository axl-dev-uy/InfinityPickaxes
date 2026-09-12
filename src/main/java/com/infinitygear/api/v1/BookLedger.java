package com.infinitygear.api.v1;

import java.math.BigDecimal;
import java.util.*;

/** Journal contract; implementations perform blocking I/O and must be called off the server thread.
 * A receipt recovers identity, NOT a second delivery entitlement. */
public interface BookLedger {
    record Issue(UUID operationId, UUID rewardId, String enchantmentKey, int level,
                 String provenanceReference, BigDecimal sourceValue) {
        public Issue {
            Objects.requireNonNull(operationId); Objects.requireNonNull(rewardId);
            if (enchantmentKey == null || !enchantmentKey.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || level < 1)
                throw new IllegalArgumentException("Invalid enchantment");
            if (provenanceReference == null || provenanceReference.isBlank() || provenanceReference.length() > 512)
                throw new IllegalArgumentException("Invalid provenance reference");
            sourceValue = exactValue(sourceValue);
        }
    }
    record Receipt(UUID bookId, Issue issue, boolean consumed) {}
    Receipt issue(Issue request) throws Exception;
    Optional<Receipt> find(UUID bookId) throws Exception;
    Optional<Receipt> find(UUID operationId, UUID rewardId) throws Exception;

    static BigDecimal exactValue(BigDecimal value) {
        Objects.requireNonNull(value);
        value = value.setScale(18, java.math.RoundingMode.UNNECESSARY);
        if (value.signum() < 0 || value.precision() > 38) throw new IllegalArgumentException("Value exceeds DECIMAL(38,18)");
        return value;
    }
}

package com.infinitygear.api.v1;

import java.math.BigDecimal;
import java.util.*;

/** Product policy is deliberately not supplied by InfinityGear. Unknown cases reject before mutation. */
public interface ProvenancePolicy {
    enum Operation { APPLY, REMOVE, TRANSFER, REPLACE, PAIR_FUSION, BULK_FUSION }
    record Source(UUID identity, boolean archive, BigDecimal value) {
        public Source {
            Objects.requireNonNull(identity); value = BookLedger.exactValue(value);
            if (!archive && value.signum() != 0) throw new IllegalArgumentException("Ordinary source cannot carry Archive value");
        }
    }
    record Decision(boolean allowed, List<BigDecimal> outputValues, String reason) {
        public Decision { outputValues = outputValues.stream().map(BookLedger::exactValue).toList(); Objects.requireNonNull(reason); }
    }
    Decision decide(Operation operation, List<Source> sources, int outputCount);
    static ProvenancePolicy unresolved() {
        return (operation, sources, count) -> new Decision(false, List.of(), "Product provenance policy has not been configured: " + operation);
    }
}

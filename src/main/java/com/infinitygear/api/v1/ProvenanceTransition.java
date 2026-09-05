package com.infinitygear.api.v1;

import java.util.*;

/** A ledger transition is a participant in an external inventory operation, not inventory delivery itself. */
public interface ProvenanceTransition {
    record Output(String enchantmentKey, int level, String provenanceReference) {}
    record Request(UUID operationId, ProvenancePolicy.Operation operation, List<UUID> sourceBooks, List<Output> outputs) {
        public Request {
            Objects.requireNonNull(operationId); Objects.requireNonNull(operation);
            sourceBooks = List.copyOf(sourceBooks); outputs = List.copyOf(outputs);
            if (sourceBooks.isEmpty() || new HashSet<>(sourceBooks).size() != sourceBooks.size())
                throw new IllegalArgumentException("Distinct source identities required");
        }
    }
    List<BookLedger.Receipt> transition(Request request, ProvenancePolicy policy) throws Exception;
}

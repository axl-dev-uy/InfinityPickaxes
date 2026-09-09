package com.infinitygear.api.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking durable journal contract for a recoverable physical book lifecycle participant.
 * Implementations must be called away from the server thread.
 *
 * <p>{@link #prepare(BookLifecycleRequest)} is idempotent only for the same operation ID and
 * canonical fingerprint. Reuse of an operation ID with any different payload must fail. Phase
 * changes are compare-and-set operations: repeating an already committed identical advance
 * recovers its state, while a different expected/current transition must fail. Physical recovery
 * may inspect only records returned by {@link #find(UUID)}; this contract never authorizes an
 * inventory scan, inference from item absence, or recreation from an uncommitted request.</p>
 */
public interface BookLifecycleTransaction {
    enum Phase {
        /** Request, before-images, intended results and lineage decision are durable; no mutation. */
        PREPARED,
        /** Operation-scoped custody markers are physically present and verified. */
        CUSTODY_MARKED,
        /** Exact prepared sources have been removed; absence alone is never evidence of this phase. */
        SOURCES_REMOVED,
        /** Exact prepared equipment after-image and attachment revision are physically present. */
        EQUIPMENT_MUTATED,
        /** Every exact prepared output identity is physically present at its recorded destination. */
        OUTPUTS_INSERTED,
        /** Durable ledger, attachment and physical disposition agree. */
        FINALIZED,
        /** The caller durably acknowledged the finalized result. */
        ACKNOWLEDGED,
        /** A prepared operation was abandoned before any physical mutation. */
        ABORTED,
        /** Physical before-images were positively restored and all custody markers were cleared. */
        ROLLED_BACK;

        public boolean terminal() {
            return this == ACKNOWLEDGED || this == ABORTED || this == ROLLED_BACK;
        }

        /** Applicable phases may be skipped only when the immutable request has no such participant. */
        public boolean permits(Phase next) {
            Objects.requireNonNull(next, "next");
            if (next == this) return true;
            if (terminal()) return false;
            if (next == ABORTED) return this == PREPARED;
            if (next == ROLLED_BACK) return this == CUSTODY_MARKED || this == SOURCES_REMOVED
                    || this == EQUIPMENT_MUTATED || this == OUTPUTS_INSERTED;
            return switch (this) {
                case PREPARED -> next == CUSTODY_MARKED || next == SOURCES_REMOVED
                        || next == EQUIPMENT_MUTATED || next == OUTPUTS_INSERTED || next == FINALIZED;
                case CUSTODY_MARKED -> next == SOURCES_REMOVED || next == EQUIPMENT_MUTATED
                        || next == OUTPUTS_INSERTED || next == FINALIZED;
                case SOURCES_REMOVED -> next == EQUIPMENT_MUTATED || next == OUTPUTS_INSERTED
                        || next == FINALIZED;
                case EQUIPMENT_MUTATED -> next == OUTPUTS_INSERTED || next == FINALIZED;
                case OUTPUTS_INSERTED -> next == FINALIZED;
                case FINALIZED -> next == ACKNOWLEDGED;
                case ACKNOWLEDGED, ABORTED, ROLLED_BACK -> false;
            };
        }
    }

    record State(BookLifecycleRequest request, String fingerprint, Phase phase,
                 Instant preparedAt, Instant updatedAt) {
        public State {
            Objects.requireNonNull(request, "request");
            fingerprint = BookLifecycleTransaction.fingerprint(fingerprint);
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(preparedAt, "preparedAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (!fingerprint.equals(request.fingerprint())) {
                throw new IllegalArgumentException("Lifecycle fingerprint does not match its request");
            }
            if (updatedAt.isBefore(preparedAt)) {
                throw new IllegalArgumentException("Lifecycle update predates preparation");
            }
        }

        public State requireReplay(BookLifecycleRequest candidate) {
            Objects.requireNonNull(candidate, "candidate");
            if (!request.operationId().equals(candidate.operationId())
                    || !fingerprint.equals(candidate.fingerprint())) {
                throw new IllegalArgumentException("Operation ID reused with a conflicting lifecycle payload");
            }
            return this;
        }
    }

    record Advance(UUID operationId, String fingerprint, Phase expected, Phase next) {
        public Advance {
            Objects.requireNonNull(operationId, "operationId");
            fingerprint = BookLifecycleTransaction.fingerprint(fingerprint);
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(next, "next");
            if (!expected.permits(next)) {
                throw new IllegalArgumentException("Invalid lifecycle phase transition");
            }
        }
    }

    State prepare(BookLifecycleRequest request) throws Exception;

    Optional<State> find(UUID operationId) throws Exception;

    State advance(Advance request) throws Exception;

    private static String fingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid lifecycle fingerprint");
        }
        return value;
    }
}

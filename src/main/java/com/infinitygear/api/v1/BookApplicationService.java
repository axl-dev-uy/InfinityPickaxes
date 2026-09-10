package com.infinitygear.api.v1;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Operation-specific, asynchronous Archive-book application capability.
 *
 * <p>Call {@link #apply(Request)} on the primary server thread. The implementation captures
 * exact inventory participants there, performs durable work away from that thread, and returns
 * to it for every physical mutation. Retrying an operation ID recovers its journaled request;
 * it is never permission to consume a second book.</p>
 */
public interface BookApplicationService {
    /** Current operation-specific readiness; callers must recheck immediately before use. */
    boolean available();

    record Request(UUID operationId, UUID actorId, int equipmentSlot, int sourceSlot,
                   String enchantmentKey) {
        public Request {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(actorId, "actorId");
            if (equipmentSlot < 0 || sourceSlot < 0 || equipmentSlot == sourceSlot) {
                throw new IllegalArgumentException("Application requires two distinct inventory slots");
            }
            if (enchantmentKey == null
                    || !enchantmentKey.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid enchantment key");
            }
        }
    }

    enum Outcome { ACKNOWLEDGED, REJECTED, RECOVERY_REQUIRED }

    record Result(UUID operationId, Outcome outcome, String reason,
                  long attachmentRevision, int enchantmentLevel) {
        public Result {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            if (attachmentRevision < -1 || enchantmentLevel < 0) {
                throw new IllegalArgumentException("Invalid application result");
            }
        }
    }

    CompletionStage<Result> apply(Request request);

    /** Recover only the durable request identified by this operation ID. */
    CompletionStage<Result> recover(UUID operationId);

    /**
     * Product authority supplied by the Archive owner. Called away from the server thread.
     * An approval authorizes only the exact no-replacement mapping described by the request.
     */
    interface PolicyAuthority {
        Decision authorize(PolicyRequest request) throws Exception;
    }

    record PolicyRequest(UUID operationId, UUID actorId, UUID equipmentId, String profileId,
                         UUID sourceBookId, String enchantmentKey, int enchantmentLevel,
                         String sourceProvenanceReference, BigDecimal sourceValue) {
        public PolicyRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(equipmentId, "equipmentId");
            if (profileId == null || profileId.isBlank() || profileId.length() > 256) {
                throw new IllegalArgumentException("Invalid profile ID");
            }
            Objects.requireNonNull(sourceBookId, "sourceBookId");
            if (enchantmentKey == null
                    || !enchantmentKey.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || enchantmentLevel < 1) {
                throw new IllegalArgumentException("Invalid enchantment");
            }
            if (sourceProvenanceReference == null || sourceProvenanceReference.isBlank()
                    || sourceProvenanceReference.length() > 512) {
                throw new IllegalArgumentException("Invalid provenance reference");
            }
            sourceValue = BookLedger.exactValue(sourceValue);
        }
    }

    record Decision(boolean allowed, String policyReference, String reason) {
        public Decision {
            Objects.requireNonNull(policyReference, "policyReference");
            Objects.requireNonNull(reason, "reason");
            if (policyReference.isBlank() || policyReference.length() > 512
                    || reason.isBlank() || reason.length() > 1024) {
                throw new IllegalArgumentException("Invalid application policy evidence");
            }
        }
    }
}

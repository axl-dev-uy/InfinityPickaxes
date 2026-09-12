package com.infinitygear.integration;

import com.infinitygear.api.v1.BookLifecycleTransaction;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Disposable-fixture observation seam. InfinityGear never registers a provider and behavior is
 * unchanged when none exists. A live acceptance harness may hold an exact phase boundary while
 * the operator records state or interrupts only the disposable Paper process.
 */
public interface BookApplicationPhaseProbe {
    enum Point {
        BEFORE_PREPARE_COMMIT,
        AFTER_PREPARE_COMMIT,
        AFTER_CUSTODY_MARKING,
        AFTER_SOURCE_REMOVAL,
        AFTER_EQUIPMENT_MUTATION,
        AFTER_FINALIZATION_COMMIT_BEFORE_ACK,
        BEFORE_ACKNOWLEDGEMENT_CLEANUP
    }

    CompletionStage<Void> reached(UUID operationId, Point point,
                                  BookLifecycleTransaction.Phase durablePhase);
}

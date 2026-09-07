package com.infinitygear.api.v1;

import java.util.Map;
import java.util.Objects;

/**
 * Optional, fail-closed description of an authoritative physical mining producer.
 *
 * <p>The producer registers this service. A consumer may separately register a
 * {@link Receiver}; producer support never implies that XP or downstream rewards are active.</p>
 */
public interface MiningAuthority {
    enum Path { ORDINARY, BLAST_MINING, DYNAMITE, VEIN_MINER, SETBLOCK, BREAK_NATURALLY }

    record Capability(boolean supported, String evidence) {
        public Capability {
            Objects.requireNonNull(evidence);
            if (evidence.isBlank()) throw new IllegalArgumentException("Missing capability evidence");
        }
    }

    /** Immutable snapshot. Missing paths are unsupported. */
    Map<Path, Capability> capabilities();

    /** Changes whenever the producer fence/configuration changes. */
    String providerRevision();

    /**
     * Optional server-thread handoff. Implementations must enqueue or reject immediately;
     * they must not perform blocking persistence in this call. False/exception means omission,
     * never producer retry or compensation.
     */
    interface Receiver {
        boolean accept(MiningCompletion completion);
    }
}

package com.infinitygear.api.v1;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Forensic recording only. No method in this service authorizes a reward or compensation. */
public interface MiningIncidentService {
    record Evidence(UUID operationId, UUID playerId, UUID itemId, String mine, UUID generationId,
                    UUID worldId, int x, int y, int z, String originalData, String placement,
                    String producerEvidence, String stage, String reason, Instant observedAt,
                    Map<String, String> versions, String configurationRevision) {
        public Evidence {
            Objects.requireNonNull(operationId); Objects.requireNonNull(playerId); Objects.requireNonNull(worldId);
            Objects.requireNonNull(observedAt);
            for (String value : List.of(mine, originalData, placement, producerEvidence, stage, reason, configurationRevision))
                if (value.isBlank() || value.length() > 16000) throw new IllegalArgumentException("Invalid mining evidence");
            versions = Map.copyOf(versions);
            if (versions.size() > 32 || versions.entrySet().stream().anyMatch(e -> e.getKey().length() > 256 || e.getValue().length() > 1024))
                throw new IllegalArgumentException("Invalid version evidence");
        }
    }
    /** True only after durable recording; failures must never change mining/reward behavior. */
    CompletionStage<Boolean> recordVoided(Evidence evidence);
}

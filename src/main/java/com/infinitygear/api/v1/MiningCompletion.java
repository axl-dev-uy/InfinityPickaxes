package com.infinitygear.api.v1;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence emitted only after a producer's synchronous physical-return boundary. */
public record MiningCompletion(UUID completionId, UUID instanceId, UUID operationId,
                               UUID playerId, UUID itemId, UUID worldId,
                               String mine, UUID generationId, int x, int y, int z,
                               String originalBlockData, MiningCredit.Source source, String placement,
                               boolean returnedSuccess, boolean acceptedReplacement, boolean finalAir,
                               boolean generationMatches, boolean placementMatches,
                               boolean mutationUnchanged, boolean producerException,
                               Instant observedAt, Map<String, String> versions,
                               String configurationRevision) {
    public MiningCompletion {
        Objects.requireNonNull(completionId); Objects.requireNonNull(instanceId); Objects.requireNonNull(operationId);
        Objects.requireNonNull(playerId); Objects.requireNonNull(itemId); Objects.requireNonNull(worldId);
        Objects.requireNonNull(generationId); Objects.requireNonNull(source); Objects.requireNonNull(observedAt);
        for (String value : List.of(mine, originalBlockData, placement, configurationRevision))
            if (value == null || value.isBlank() || value.length() > 16000)
                throw new IllegalArgumentException("Missing or oversized completion evidence");
        versions = Map.copyOf(versions);
        if (versions.size() > 32 || versions.entrySet().stream().anyMatch(e -> e.getKey().length() > 256 || e.getValue().length() > 1024))
            throw new IllegalArgumentException("Invalid version evidence");
    }

    public boolean confirmed() {
        return returnedSuccess && acceptedReplacement && finalAir && generationMatches
                && placementMatches && mutationUnchanged && !producerException
                && placement.equals("NATURAL");
    }
}

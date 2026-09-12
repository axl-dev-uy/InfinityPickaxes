package com.infinitygear.api.v1;

import java.util.*;

/** Physical instance ID is issued by a trusted world/reset provider, not derived from coordinates or ticks. */
public record MiningCredit(UUID creditId, UUID instanceId, UUID playerId, UUID pickaxeId, String profileId,
                           UUID worldId, int x, int y, int z, String originalBlockData,
                           Source source, String generation, boolean legitimate, boolean successful) {
    public enum Source { NORMAL, BLAST_MINING, DYNAMITE, VEIN_MINER, OTHER }
    public MiningCredit {
        Objects.requireNonNull(creditId); Objects.requireNonNull(instanceId); Objects.requireNonNull(playerId);
        Objects.requireNonNull(pickaxeId); Objects.requireNonNull(worldId); Objects.requireNonNull(source);
        if (profileId == null || profileId.isBlank() || generation == null || generation.isBlank()
                || originalBlockData == null || originalBlockData.isBlank()) throw new IllegalArgumentException("Missing attribution");
    }
}

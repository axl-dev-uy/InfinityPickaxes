package com.infinitygear.api.v1;

import java.util.*;
import java.util.concurrent.CompletionStage;

/** Optional producer bridge, not implemented by raw Bukkit events.
 * Provider owns persistent placement/movement/reset identities and confirms actual break completion.
 * Call observe before nested effects, including for setblock/natural-break modes.
 * Multiple observations of one physical instance MUST return the same instance and credit IDs.
 */
public interface MiningAuthority {
    record Attempt(UUID playerId, UUID pickaxeId, String profileId, UUID worldId,
                   int x, int y, int z, String originalBlockData) {}
    CompletionStage<MiningCredit> observe(Attempt attempt);
    boolean supportsDirectSetblock();
    boolean supportsBreakNaturally();
    String providerRevision();
}

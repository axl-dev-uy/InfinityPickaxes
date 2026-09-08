package com.infinitygear.mining;

import java.util.Objects;
import java.util.UUID;

/** An absolute, committed account transition. Projection never replays the originating operation. */
public record XpProjectionReceipt(UUID operationId, Kind kind, UUID pickaxeId, String profileId,
                                  long expectedRevision, MiningXpPlan.Progress before,
                                  MiningXpPlan.Account account) {
    public enum Kind { MINING, ADMIN_ADD_XP, ADMIN_SET_LEVEL }

    public XpProjectionReceipt {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(pickaxeId);
        Objects.requireNonNull(before);
        Objects.requireNonNull(account);
        if (profileId == null || profileId.isBlank() || expectedRevision < 0
                || !pickaxeId.equals(account.pickaxeId()) || !profileId.equals(account.profileId())
                || account.revision() != Math.addExact(expectedRevision, 1)) {
            throw new IllegalArgumentException("Invalid XP projection receipt");
        }
    }
}

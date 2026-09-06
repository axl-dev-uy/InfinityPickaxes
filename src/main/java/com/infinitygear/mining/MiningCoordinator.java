package com.infinitygear.mining;

import com.infinitygear.api.v1.MiningCredit;
import java.util.*;
import java.util.function.Consumer;

/** One coordinator for provider-confirmed physical instances. Server-thread owned.
 * Journal claims must be durable in production; ambiguous XP failures are never automatically retried. */
public final class MiningCoordinator {
    public interface Journal {
        /** Durable production journals require the atomic XP participant, not an arbitrary callback. */
        default boolean permitsCallbackAwards() { return true; }
        boolean reserve(MiningCredit credit);
        void complete(UUID instanceId);
        void needsRecovery(UUID instanceId);
        /** Called only after notification returns. Durable implementations retain unacknowledged credits. */
        void notificationDelivered(UUID creditId);
    }
    private final Journal journal;
    private final Map<UUID, MiningCredit> pending = new HashMap<>();
    public MiningCoordinator(Journal journal) { this.journal = Objects.requireNonNull(journal); }

    /** Called before nested effects. Original immutable state survives subsequent block changes. */
    public void begin(MiningCredit observation) {
        if (observation.successful()) throw new IllegalArgumentException("Begin requires an unconfirmed observation");
        var prior = pending.putIfAbsent(observation.instanceId(), observation);
        if (prior != null && !prior.equals(observation))
            throw new IllegalArgumentException("Provider returned inconsistent physical identity");
    }

    /** Provider reports the final outcome for the entire instance attempt, not each nested raw event.
     * False means no credit and releases the attempt. */
    public boolean complete(UUID instanceId, boolean successful, Runnable awardXp, Consumer<MiningCredit> notify) {
        var original = pending.remove(instanceId);
        if (original == null || !successful) return false;
        return credit(new MiningCredit(original.creditId(), original.instanceId(), original.playerId(), original.pickaxeId(),
                original.profileId(), original.worldId(), original.x(), original.y(), original.z(), original.originalBlockData(),
                original.source(), original.generation(), original.legitimate(), true), awardXp, notify);
    }
    public boolean credit(MiningCredit credit, Runnable awardXp, Consumer<MiningCredit> notify) {
        if (!credit.legitimate() || !credit.successful() || isAir(credit.originalBlockData())) return false;
        if (!journal.permitsCallbackAwards()) throw new IllegalStateException("Durable mining requires the confirmed-only XP receipt participant");
        if (!journal.reserve(credit)) return false;
        try {
            awardXp.run();
            journal.complete(credit.instanceId());
        } catch (RuntimeException failure) {
            try { journal.needsRecovery(credit.instanceId()); }
            catch (RuntimeException recoveryFailure) {
                if (recoveryFailure != failure) failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        }
        // A notification failure must never make the XP operation eligible again.
        notify.accept(credit);
        journal.notificationDelivered(credit.creditId());
        return true;
    }
    private static boolean isAir(String data) {
        String type = data.split("\\[", 2)[0];
        return Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air").contains(type);
    }
}

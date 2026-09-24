package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MiningCreditDigestTest {
    @Test void everyImmutableFieldChangesTheVersionedDigest() {
        var base = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "infinitygear:pickaxe", UUID.randomUUID(), 1, 2, 3, "minecraft:stone",
                MiningCredit.Source.NORMAL, "generation-1", true, true);
        var digest = MiningCreditDigest.sha256(base);
        assertArrayEquals(digest, MiningCreditDigest.sha256(base));
        assertEquals(32, digest.length);
        for (var changed : new MiningCredit[] {
                new MiningCredit(UUID.randomUUID(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), base.y(), base.z(), base.originalBlockData(), base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), UUID.randomUUID(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), base.y(), base.z(), base.originalBlockData(), base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), UUID.randomUUID(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), base.y(), base.z(), base.originalBlockData(), base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), UUID.randomUUID(), base.profileId(), base.worldId(), base.x(), base.y(), base.z(), base.originalBlockData(), base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), "other:profile", base.worldId(), base.x(), base.y(), base.z(), base.originalBlockData(), base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), UUID.randomUUID(), base.x(), base.y(), base.z(), base.originalBlockData(), base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), 4, base.y(), base.z(), base.originalBlockData(), base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), 4, base.z(), base.originalBlockData(), base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), base.y(), 4, base.originalBlockData(), base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), base.y(), base.z(), "minecraft:dirt", base.source(), base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), base.y(), base.z(), base.originalBlockData(), MiningCredit.Source.VEIN_MINER, base.generation(), true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), base.y(), base.z(), base.originalBlockData(), base.source(), "generation-2", true, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), base.y(), base.z(), base.originalBlockData(), base.source(), base.generation(), false, true),
                new MiningCredit(base.creditId(), base.instanceId(), base.playerId(), base.pickaxeId(), base.profileId(), base.worldId(), base.x(), base.y(), base.z(), base.originalBlockData(), base.source(), base.generation(), true, false)
        }) assertFalse(java.util.Arrays.equals(digest, MiningCreditDigest.sha256(changed)));
    }
}

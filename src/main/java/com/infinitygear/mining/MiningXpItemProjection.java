package com.infinitygear.mining;

import com.infinitygear.data.GearData;
import com.infinitygear.data.TrackedKind;
import com.infinitygear.persistence.MariaMiningXpLedger.Receipt;
import com.infinitypickaxes.core.pickaxe.PickaxeData;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Compare-and-set item projection of a committed DB receipt. Caller must establish unique live custody.
 * No additive XP, level-up event or inventory delivery occurs here. A lost player save can replay
 * receipt revisions in order; conflicting admin/legacy edits are rejected, never overwritten. */
public final class MiningXpItemProjection {
    public static final NamespacedKey REVISION = new NamespacedKey("infinitygear", "mining_xp_revision");
    public enum Result { APPLIED, ALREADY_APPLIED, CONFLICT }

    public static boolean isManaged(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(REVISION);
    }
    /** Common legacy save guard: unrelated item metadata may change, managed XP may not. */
    public static void requireUnchangedProgress(ItemStack item, int level, double xp, long blocks) {
        if (!isManaged(item)) return;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(REVISION, PersistentDataType.LONG)
                || !matches(pdc, new MiningXpPlan.Progress(level, xp, blocks), pdc.has(PickaxeData.KEY_IS_INFINITY)))
            throw new IllegalStateException("Database-managed mining XP must be changed through its durable participant");
    }

    public Result apply(ItemStack item, Receipt receipt) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        if (item == null || item.getAmount() != 1 || !item.hasItemMeta()) return Result.CONFLICT;
        var meta = item.getItemMeta(); var pdc = meta.getPersistentDataContainer();
        var plan = receipt.plan(); var after = receipt.account();
        if (!plan.pickaxeId().equals(after.pickaxeId()) || !plan.profileId().equals(after.profileId())
                || after.revision() != Math.addExact(plan.expectedRevision(), 1) || !plan.after().equals(after.progress()))
            throw new IllegalArgumentException("Inconsistent XP receipt");
        if (!plan.pickaxeId().toString().equals(pdc.get(GearData.KEY_UUID, PersistentDataType.STRING))
                || !plan.profileId().equals(pdc.get(GearData.KEY_PROFILE, PersistentDataType.STRING))
                || !pdc.has(GearData.KEY_MARKER, PersistentDataType.BYTE)
                || !TrackedKind.GEAR.name().equals(pdc.get(GearData.KEY_KIND, PersistentDataType.STRING))
                || pdc.has(GearData.KEY_QUARANTINED)) return Result.CONFLICT;
        boolean legacy = pdc.has(PickaxeData.KEY_IS_INFINITY);
        if (legacy && (!GearData.LEGACY_PICKAXE_PROFILE.equals(plan.profileId())
                || !plan.pickaxeId().toString().equals(pdc.get(PickaxeData.KEY_UUID, PersistentDataType.STRING))
                || pdc.has(PickaxeData.KEY_QUARANTINED))) return Result.CONFLICT;
        if (pdc.has(REVISION) && !pdc.has(REVISION, PersistentDataType.LONG)) return Result.CONFLICT;
        Long revision = pdc.get(REVISION, PersistentDataType.LONG);
        long currentRevision = revision == null ? 0 : revision;
        if (currentRevision == after.revision() && matches(pdc, after.progress(), legacy)) return Result.ALREADY_APPLIED;
        if (currentRevision != plan.expectedRevision() || !matches(pdc, plan.before(), legacy)) return Result.CONFLICT;
        pdc.set(GearData.KEY_LEVEL, PersistentDataType.INTEGER, after.progress().level());
        pdc.set(GearData.KEY_XP, PersistentDataType.DOUBLE, after.progress().xp());
        pdc.set(GearData.KEY_BLOCKS, PersistentDataType.LONG, after.progress().blocksMined());
        if (legacy) {
            pdc.set(PickaxeData.KEY_LEVEL, PersistentDataType.INTEGER, after.progress().level());
            pdc.set(PickaxeData.KEY_XP, PersistentDataType.DOUBLE, after.progress().xp());
            pdc.set(PickaxeData.KEY_BLOCKS_MINED, PersistentDataType.LONG, after.progress().blocksMined());
        }
        pdc.set(REVISION, PersistentDataType.LONG, after.revision());
        return item.setItemMeta(meta) ? Result.APPLIED : Result.CONFLICT;
    }
    private static boolean matches(PersistentDataContainer pdc, MiningXpPlan.Progress expected, boolean legacy) {
        if (!Integer.valueOf(expected.level()).equals(pdc.get(GearData.KEY_LEVEL, PersistentDataType.INTEGER))
                || !Double.valueOf(expected.xp()).equals(pdc.get(GearData.KEY_XP, PersistentDataType.DOUBLE))
                || !Long.valueOf(expected.blocksMined()).equals(pdc.get(GearData.KEY_BLOCKS, PersistentDataType.LONG))) return false;
        return !legacy || (Integer.valueOf(expected.level()).equals(pdc.get(PickaxeData.KEY_LEVEL, PersistentDataType.INTEGER))
                && Double.valueOf(expected.xp()).equals(pdc.get(PickaxeData.KEY_XP, PersistentDataType.DOUBLE))
                && Long.valueOf(expected.blocksMined()).equals(pdc.get(PickaxeData.KEY_BLOCKS_MINED, PersistentDataType.LONG)));
    }
}

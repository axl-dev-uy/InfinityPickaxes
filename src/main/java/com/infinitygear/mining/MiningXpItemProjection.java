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

import java.util.Objects;
import java.util.UUID;

/** Compare-and-set item projection of a committed DB receipt. Caller must establish unique live custody.
 * No additive XP, level-up event or inventory delivery occurs here. A lost player save can replay
 * receipt revisions in order; conflicting admin/legacy edits are rejected, never overwritten. */
public final class MiningXpItemProjection {
    public static final NamespacedKey REVISION = new NamespacedKey("infinitygear", "mining_xp_revision");
    public static final NamespacedKey ADOPTION = new NamespacedKey("infinitygear", "mining_xp_adoption");
    public enum Result { APPLIED, ALREADY_APPLIED, CONFLICT }
    public record AdoptionFence(UUID adoptionId, UUID pickaxeId, String profileId, MiningXpPlan.Progress baseline) { }

    public static boolean isManaged(ItemStack item) {
        return item != null && item.hasItemMeta() && (item.getItemMeta().getPersistentDataContainer().has(REVISION)
                || item.getItemMeta().getPersistentDataContainer().has(ADOPTION));
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
        var plan = receipt.plan();
        return apply(item, new XpProjectionReceipt(receipt.credit().creditId(), XpProjectionReceipt.Kind.MINING,
                plan.pickaxeId(), plan.profileId(), plan.expectedRevision(), plan.before(), receipt.account()));
    }

    public AdoptionFence beginAdoption(ItemStack item, UUID adoptionId) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        Objects.requireNonNull(adoptionId);
        if (item == null || item.getAmount() != 1 || !item.hasItemMeta() || isManaged(item))
            throw new IllegalStateException("Adoption requires one unmanaged, unstacked item");
        var meta = item.getItemMeta(); var pdc = meta.getPersistentDataContainer();
        String uuid = pdc.get(GearData.KEY_UUID, PersistentDataType.STRING);
        String profile = pdc.get(GearData.KEY_PROFILE, PersistentDataType.STRING);
        if (!pdc.has(GearData.KEY_MARKER, PersistentDataType.BYTE)
                || !TrackedKind.GEAR.name().equals(pdc.get(GearData.KEY_KIND, PersistentDataType.STRING))
                || pdc.has(GearData.KEY_QUARANTINED) || pdc.has(PickaxeData.KEY_QUARANTINED))
            throw new IllegalStateException("Malformed or quarantined gear cannot be adopted");
        try {
            UUID id = UUID.fromString(uuid);
            boolean legacy = pdc.has(PickaxeData.KEY_IS_INFINITY);
            if (profile == null || (legacy && (!GearData.LEGACY_PICKAXE_PROFILE.equals(profile)
                    || !uuid.equals(pdc.get(PickaxeData.KEY_UUID, PersistentDataType.STRING)))))
                throw new IllegalStateException("Conflicting gear identity");
            var baseline = readProgress(pdc, legacy);
            if (!matches(pdc, baseline, legacy)) throw new IllegalStateException("Conflicting mirrored progression");
            pdc.set(ADOPTION, PersistentDataType.STRING, adoptionId.toString());
            if (!item.setItemMeta(meta)) throw new IllegalStateException("Could not fence item adoption");
            return new AdoptionFence(adoptionId, id, profile, baseline);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Malformed gear identity or progression", invalid);
        }
    }

    public Result completeAdoption(ItemStack item, AdoptionFence fence, MiningXpPlan.Account account) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        if (item == null || item.getAmount() != 1 || !item.hasItemMeta()) return Result.CONFLICT;
        var meta = item.getItemMeta(); var pdc = meta.getPersistentDataContainer();
        String token = pdc.get(ADOPTION, PersistentDataType.STRING);
        if (!fence.adoptionId().toString().equals(token) || !fence.pickaxeId().equals(account.pickaxeId())
                || !fence.profileId().equals(account.profileId()) || account.revision() != 0
                || !fence.baseline().equals(account.progress()) || !matchesIdentity(pdc, fence.pickaxeId(), fence.profileId())
                || pdc.has(GearData.KEY_QUARANTINED) || pdc.has(PickaxeData.KEY_QUARANTINED)
                || !matches(pdc, fence.baseline(), pdc.has(PickaxeData.KEY_IS_INFINITY))) return Result.CONFLICT;
        pdc.set(REVISION, PersistentDataType.LONG, 0L); pdc.remove(ADOPTION);
        return item.setItemMeta(meta) ? Result.APPLIED : Result.CONFLICT;
    }

    public Result apply(ItemStack item, XpProjectionReceipt receipt) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        if (item == null || item.getAmount() != 1 || !item.hasItemMeta()) return Result.CONFLICT;
        var meta = item.getItemMeta(); var pdc = meta.getPersistentDataContainer();
        var after = receipt.account();
        if (!receipt.pickaxeId().toString().equals(pdc.get(GearData.KEY_UUID, PersistentDataType.STRING))
                || !receipt.profileId().equals(pdc.get(GearData.KEY_PROFILE, PersistentDataType.STRING))
                || !pdc.has(GearData.KEY_MARKER, PersistentDataType.BYTE)
                || !TrackedKind.GEAR.name().equals(pdc.get(GearData.KEY_KIND, PersistentDataType.STRING))
                || pdc.has(GearData.KEY_QUARANTINED) || pdc.has(ADOPTION)) return Result.CONFLICT;
        boolean legacy = pdc.has(PickaxeData.KEY_IS_INFINITY);
        if (legacy && (!GearData.LEGACY_PICKAXE_PROFILE.equals(receipt.profileId())
                || !receipt.pickaxeId().toString().equals(pdc.get(PickaxeData.KEY_UUID, PersistentDataType.STRING))
                || pdc.has(PickaxeData.KEY_QUARANTINED))) return Result.CONFLICT;
        if (pdc.has(REVISION) && !pdc.has(REVISION, PersistentDataType.LONG)) return Result.CONFLICT;
        Long revision = pdc.get(REVISION, PersistentDataType.LONG);
        long currentRevision = revision == null ? 0 : revision;
        if (currentRevision == after.revision() && matches(pdc, after.progress(), legacy)) return Result.ALREADY_APPLIED;
        if (currentRevision != receipt.expectedRevision() || !matches(pdc, receipt.before(), legacy)) return Result.CONFLICT;
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
    public static Long revision(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(REVISION, PersistentDataType.LONG) ? pdc.get(REVISION, PersistentDataType.LONG) : null;
    }
    public static UUID adoptionId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(ADOPTION, PersistentDataType.STRING);
        try { return value == null ? null : UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }
    public static boolean matchesAccount(ItemStack item, MiningXpPlan.Account account) {
        if (item == null || !item.hasItemMeta() || account == null) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return matchesIdentity(pdc, account.pickaxeId(), account.profileId())
                && !pdc.has(GearData.KEY_QUARANTINED) && !pdc.has(PickaxeData.KEY_QUARANTINED)
                && Long.valueOf(account.revision()).equals(pdc.get(REVISION, PersistentDataType.LONG))
                && matches(pdc, account.progress(), pdc.has(PickaxeData.KEY_IS_INFINITY));
    }
    private static boolean matchesIdentity(PersistentDataContainer pdc, UUID id, String profile) {
        return id.toString().equals(pdc.get(GearData.KEY_UUID, PersistentDataType.STRING))
                && profile.equals(pdc.get(GearData.KEY_PROFILE, PersistentDataType.STRING))
                && pdc.has(GearData.KEY_MARKER, PersistentDataType.BYTE)
                && TrackedKind.GEAR.name().equals(pdc.get(GearData.KEY_KIND, PersistentDataType.STRING));
    }
    private static MiningXpPlan.Progress readProgress(PersistentDataContainer pdc, boolean legacy) {
        Integer level = pdc.get(GearData.KEY_LEVEL, PersistentDataType.INTEGER);
        Double xp = pdc.get(GearData.KEY_XP, PersistentDataType.DOUBLE);
        Long blocks = pdc.get(GearData.KEY_BLOCKS, PersistentDataType.LONG);
        if (level == null || xp == null || blocks == null) throw new IllegalArgumentException("Missing progression");
        return new MiningXpPlan.Progress(level, xp, blocks);
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

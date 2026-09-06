package com.infinitygear.data;

import com.infinitygear.gear.GearInstance;
import com.infinitypickaxes.core.pickaxe.PickaxeData;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** Versioned InfinityGear PDC with indefinite dual-read support for legacy pickaxes. */
public final class GearData {
    public static final int SCHEMA_VERSION = 1;
    public static final String LEGACY_PICKAXE_PROFILE = "infinitygear:pickaxe";
    public static final NamespacedKey KEY_MARKER = new NamespacedKey("infinitygear", "tracked");
    public static final NamespacedKey KEY_KIND = new NamespacedKey("infinitygear", "kind");
    public static final NamespacedKey KEY_UUID = new NamespacedKey("infinitygear", "uuid");
    public static final NamespacedKey KEY_PROFILE = new NamespacedKey("infinitygear", "profile");
    public static final NamespacedKey KEY_SCHEMA = new NamespacedKey("infinitygear", "schema_version");
    public static final NamespacedKey KEY_LEVEL = new NamespacedKey("infinitygear", "level");
    public static final NamespacedKey KEY_XP = new NamespacedKey("infinitygear", "xp");
    public static final NamespacedKey KEY_BLOCKS = new NamespacedKey("infinitygear", "blocks_mined");
    public static final NamespacedKey KEY_SOCKETS = new NamespacedKey("infinitygear", "socket_capacity");
    public static final NamespacedKey KEY_QUARANTINED = new NamespacedKey("infinitygear", "quarantined");

    public enum State { NOT_GEAR, VALID_NEW, MIGRATED_LEGACY, MALFORMED_NEW, MALFORMED_LEGACY }
    public record ReadResult(State state, GearInstance gear, String diagnostic) {
        public boolean valid() { return gear != null; }
    }

    private GearData() {}

    public static boolean isGear(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String kind = pdc.get(KEY_KIND, PersistentDataType.STRING);
        return (pdc.has(KEY_MARKER, PersistentDataType.BYTE) && TrackedKind.GEAR.name().equals(kind))
                || PickaxeData.isInfinityPickaxe(item);
    }

    /** Reads live data and lazily writes new keys only when every legacy field validates. */
    public static ReadResult read(ItemStack item, int legacySocketCapacity, boolean migrate) {
        if (item == null || !item.hasItemMeta()) return new ReadResult(State.NOT_GEAR, null, null);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(KEY_MARKER, PersistentDataType.BYTE)) return readNew(item, pdc);
        if (!PickaxeData.isInfinityPickaxe(item)) return new ReadResult(State.NOT_GEAR, null, null);

        LegacyGearPayload.ParseResult parsed = LegacyGearPayload.parse(
                pdc.get(PickaxeData.KEY_UUID, PersistentDataType.STRING),
                pdc.get(PickaxeData.KEY_LEVEL, PersistentDataType.INTEGER),
                pdc.get(PickaxeData.KEY_XP, PersistentDataType.DOUBLE),
                pdc.get(PickaxeData.KEY_BLOCKS_MINED, PersistentDataType.LONG),
                pdc.get(PickaxeData.KEY_QUARANTINED, PersistentDataType.BYTE));
        if (!parsed.valid()) return new ReadResult(State.MALFORMED_LEGACY, null, parsed.failure());
        LegacyGearPayload legacy = parsed.payload();
        GearInstance gear = new GearInstance(item, legacy.uuid(), LEGACY_PICKAXE_PROFILE,
                legacy.level(), legacy.xp(), legacy.blocksMined(), legacySocketCapacity);
        if (migrate) save(gear, legacy.quarantined(), true);
        return new ReadResult(State.MIGRATED_LEGACY, gear, null);
    }

    private static ReadResult readNew(ItemStack item, PersistentDataContainer pdc) {
        String kind = pdc.get(KEY_KIND, PersistentDataType.STRING);
        if (!TrackedKind.GEAR.name().equals(kind)) return new ReadResult(State.MALFORMED_NEW, null, "wrong_kind");
        String uuidText = pdc.get(KEY_UUID, PersistentDataType.STRING);
        String profile = pdc.get(KEY_PROFILE, PersistentDataType.STRING);
        Integer schema = pdc.get(KEY_SCHEMA, PersistentDataType.INTEGER);
        try {
            if (schema == null || schema < 1 || profile == null || profile.isBlank()) throw new IllegalArgumentException();
            UUID uuid = UUID.fromString(uuidText);
            return new ReadResult(State.VALID_NEW, new GearInstance(item, uuid, profile,
                    pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 0),
                    pdc.getOrDefault(KEY_XP, PersistentDataType.DOUBLE, 0.0),
                    pdc.getOrDefault(KEY_BLOCKS, PersistentDataType.LONG, 0L),
                    pdc.getOrDefault(KEY_SOCKETS, PersistentDataType.INTEGER, 0)), null);
        } catch (RuntimeException invalid) {
            return new ReadResult(State.MALFORMED_NEW, null, "invalid_new_identity");
        }
    }

    public static void save(GearInstance gear, boolean quarantined, boolean mirrorLegacyPickaxe) {
        com.infinitygear.mining.MiningXpItemProjection.requireUnchangedProgress(gear.item(), gear.level(), gear.xp(), gear.blocksMined());
        ItemMeta meta = gear.item().getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Gear item has no metadata.");
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_MARKER, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_KIND, PersistentDataType.STRING, TrackedKind.GEAR.name());
        pdc.set(KEY_UUID, PersistentDataType.STRING, gear.uuid().toString());
        pdc.set(KEY_PROFILE, PersistentDataType.STRING, gear.profileId());
        pdc.set(KEY_SCHEMA, PersistentDataType.INTEGER, SCHEMA_VERSION);
        pdc.set(KEY_LEVEL, PersistentDataType.INTEGER, gear.level());
        pdc.set(KEY_XP, PersistentDataType.DOUBLE, gear.xp());
        pdc.set(KEY_BLOCKS, PersistentDataType.LONG, gear.blocksMined());
        pdc.set(KEY_SOCKETS, PersistentDataType.INTEGER, gear.socketCapacity());
        if (quarantined) pdc.set(KEY_QUARANTINED, PersistentDataType.BYTE, (byte) 1);
        else pdc.remove(KEY_QUARANTINED);
        if (mirrorLegacyPickaxe && LEGACY_PICKAXE_PROFILE.equals(gear.profileId())) {
            pdc.set(PickaxeData.KEY_IS_INFINITY, PersistentDataType.BYTE, (byte) 1);
            pdc.set(PickaxeData.KEY_UUID, PersistentDataType.STRING, gear.uuid().toString());
            pdc.set(PickaxeData.KEY_LEVEL, PersistentDataType.INTEGER, gear.level());
            pdc.set(PickaxeData.KEY_XP, PersistentDataType.DOUBLE, gear.xp());
            pdc.set(PickaxeData.KEY_BLOCKS_MINED, PersistentDataType.LONG, gear.blocksMined());
            if (quarantined) pdc.set(PickaxeData.KEY_QUARANTINED, PersistentDataType.BYTE, (byte) 1);
        }
        gear.item().setItemMeta(meta);
    }
}

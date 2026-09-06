package com.infinitypickaxes.core.pickaxe;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class PickaxeData {

    public static final NamespacedKey KEY_IS_INFINITY;
    public static final NamespacedKey KEY_UUID;
    public static final NamespacedKey KEY_LEVEL;
    public static final NamespacedKey KEY_XP;
    public static final NamespacedKey KEY_BLOCKS_MINED;
    public static final NamespacedKey KEY_QUARANTINED;

    static {
        String namespace = "infinitypickaxes";
        KEY_IS_INFINITY = new NamespacedKey(namespace, "is_infinity_pickaxe");
        KEY_UUID = new NamespacedKey(namespace, "pickaxe_uuid");
        KEY_LEVEL = new NamespacedKey(namespace, "level");
        KEY_XP = new NamespacedKey(namespace, "xp");
        KEY_BLOCKS_MINED = new NamespacedKey(namespace, "blocks_mined");
        KEY_QUARANTINED = new NamespacedKey(namespace, "quarantined");
    }

    private PickaxeData() {}

    /**
     * Checks if an ItemStack is an Infinity Pickaxe.
     */
    public static boolean isInfinityPickaxe(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(KEY_IS_INFINITY, PersistentDataType.BYTE);
    }

    public static UUID getPickaxeUuid(ItemStack item) {
        if (!isInfinityPickaxe(item)) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(KEY_UUID, PersistentDataType.STRING);
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void setPickaxeUuid(ItemStack item, UUID uuid) {
        if (item == null || uuid == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(KEY_UUID, PersistentDataType.STRING, uuid.toString());
        item.setItemMeta(meta);
    }

    public static void setQuarantined(ItemStack item, boolean quarantined) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (quarantined) meta.getPersistentDataContainer().set(KEY_QUARANTINED, PersistentDataType.BYTE, (byte) 1);
        else meta.getPersistentDataContainer().remove(KEY_QUARANTINED);
        item.setItemMeta(meta);
    }

    public static boolean isQuarantined(ItemStack item) {
        if (!isInfinityPickaxe(item)) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer()
                .get(KEY_QUARANTINED, PersistentDataType.BYTE);
        return value != null && value != 0;
    }

    /**
     * Reads and parses an InfinityPickaxe instance from an ItemStack.
     */
    public static InfinityPickaxe fromItemStack(ItemStack item) {
        if (!isInfinityPickaxe(item)) return null;

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        UUID uuid;
        String uuidStr = pdc.get(KEY_UUID, PersistentDataType.STRING);
        if (uuidStr != null) {
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (Exception e) {
                return null; // Never silently replace a malformed deployed identity.
            }
        } else {
            return null;
        }

        int level = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 0);
        double xp = pdc.getOrDefault(KEY_XP, PersistentDataType.DOUBLE, 0.0);
        long blocksMined = pdc.getOrDefault(KEY_BLOCKS_MINED, PersistentDataType.LONG, 0L);

        return new InfinityPickaxe(item, uuid, level, xp, blocksMined);
    }

    /**
     * Saves the InfinityPickaxe data into the ItemStack's PDC.
     */
    public static void saveToItemStack(InfinityPickaxe pickaxe, ItemStack item) {
        if (item == null || pickaxe == null) return;
        com.infinitygear.mining.MiningXpItemProjection.requireUnchangedProgress(item, pickaxe.getLevel(), pickaxe.getXp(), pickaxe.getBlocksMined());

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_IS_INFINITY, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_UUID, PersistentDataType.STRING, pickaxe.getUuid().toString());
        pdc.set(KEY_LEVEL, PersistentDataType.INTEGER, pickaxe.getLevel());
        pdc.set(KEY_XP, PersistentDataType.DOUBLE, pickaxe.getXp());
        pdc.set(KEY_BLOCKS_MINED, PersistentDataType.LONG, pickaxe.getBlocksMined());

        item.setItemMeta(meta);
    }

}

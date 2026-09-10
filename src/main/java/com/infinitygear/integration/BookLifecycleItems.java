package com.infinitygear.integration;

import com.infinitygear.api.v1.BookLifecycleRequest;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Operation-scoped physical markers. They are not Archive identity or durable authority. */
public final class BookLifecycleItems {
    public static final NamespacedKey OPERATION = new NamespacedKey("infinitygear", "book_lifecycle_operation");
    public static final NamespacedKey ROLE = new NamespacedKey("infinitygear", "book_lifecycle_role");
    public static final NamespacedKey BEFORE_HASH = new NamespacedKey("infinitygear", "book_lifecycle_before_hash");
    public static final NamespacedKey ATTACHMENT_REVISION = new NamespacedKey("infinitygear", "archive_attachment_revision");
    static final String SOURCE = "source";
    static final String SOURCE_TOKEN = "source-token";
    static final String EQUIPMENT = "equipment";

    private BookLifecycleItems() { }

    public static boolean hasCustodyMarker(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(OPERATION) || pdc.has(ROLE) || pdc.has(BEFORE_HASH);
    }

    static void markBefore(ItemStack item, UUID operationId, String role, BookLifecycleRequest.ItemImage before) {
        Objects.requireNonNull(item, "item");
        if (hasCustodyMarker(item)) throw new IllegalStateException("Lifecycle custody marker collision");
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "item metadata");
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(OPERATION, PersistentDataType.STRING, operationId.toString());
        pdc.set(ROLE, PersistentDataType.STRING, role);
        pdc.set(BEFORE_HASH, PersistentDataType.STRING, before.sha256());
        item.setItemMeta(meta);
    }

    public static void markEquipmentAfter(ItemStack item, UUID operationId, long attachmentRevision) {
        Objects.requireNonNull(item, "item");
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "item metadata");
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(OPERATION) || pdc.has(ROLE) || pdc.has(BEFORE_HASH)) {
            throw new IllegalStateException("Lifecycle custody marker collision");
        }
        pdc.set(OPERATION, PersistentDataType.STRING, operationId.toString());
        pdc.set(ROLE, PersistentDataType.STRING, EQUIPMENT);
        pdc.set(ATTACHMENT_REVISION, PersistentDataType.LONG, attachmentRevision);
        item.setItemMeta(meta);
    }

    static ItemStack sourceToken(UUID operationId, BookLifecycleRequest.ItemImage before) {
        ItemStack token = new ItemStack(Material.STRUCTURE_VOID);
        ItemMeta meta = Objects.requireNonNull(token.getItemMeta(), "token metadata");
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(OPERATION, PersistentDataType.STRING, operationId.toString());
        pdc.set(ROLE, PersistentDataType.STRING, SOURCE_TOKEN);
        pdc.set(BEFORE_HASH, PersistentDataType.STRING, before.sha256());
        token.setItemMeta(meta);
        return token;
    }

    static boolean markedBeforeMatches(ItemStack item, UUID operationId, String role,
                                       BookLifecycleRequest.ItemImage before) {
        if (!markerMatches(item, operationId, role, before.sha256())) return false;
        ItemStack normalized = item.clone();
        clearCustody(normalized);
        return exact(normalized, before);
    }

    static boolean sourceTokenMatches(ItemStack item, UUID operationId,
                                      BookLifecycleRequest.ItemImage before) {
        return item != null && item.getAmount() == 1 && item.getType() == Material.STRUCTURE_VOID
                && markerMatches(item, operationId, SOURCE_TOKEN, before.sha256());
    }

    static boolean exact(ItemStack item, BookLifecycleRequest.ItemImage image) {
        return item != null && Arrays.equals(item.serializeAsBytes(), image.serializedItem());
    }

    static boolean finalizedEquipmentMatches(ItemStack item, BookLifecycleRequest.Equipment equipment) {
        if (item == null || !item.hasItemMeta()) return false;
        Long revision = item.getItemMeta().getPersistentDataContainer()
                .get(ATTACHMENT_REVISION, PersistentDataType.LONG);
        if (!Long.valueOf(equipment.resultingRevision()).equals(revision)) return false;
        ItemStack normalizedLive = item.clone();
        ItemStack normalizedExpected = deserialize(equipment.afterImage());
        clearCustody(normalizedLive);
        clearCustody(normalizedExpected);
        return normalizedLive.isSimilar(normalizedExpected)
                && Arrays.equals(normalizedLive.serializeAsBytes(), normalizedExpected.serializeAsBytes());
    }

    static boolean markerMatches(ItemStack item, UUID operationId, String role, String beforeHash) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return operationId.toString().equals(pdc.get(OPERATION, PersistentDataType.STRING))
                && role.equals(pdc.get(ROLE, PersistentDataType.STRING))
                && (beforeHash == null || beforeHash.equals(pdc.get(BEFORE_HASH, PersistentDataType.STRING)));
    }

    static void clearCustody(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(OPERATION);
        pdc.remove(ROLE);
        pdc.remove(BEFORE_HASH);
        item.setItemMeta(meta);
    }

    static ItemStack deserialize(BookLifecycleRequest.ItemImage image) {
        return ItemStack.deserializeBytes(image.serializedItem());
    }
}

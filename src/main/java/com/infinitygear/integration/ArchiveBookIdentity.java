package com.infinitygear.integration;

import com.infinitygear.api.v1.BookLedger;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.UUID;

/** PDC is a lookup reference, never proof. Authenticity also requires a live, unconsumed ledger record. */
public final class ArchiveBookIdentity {
    public static final NamespacedKey ID = new NamespacedKey("infinitygear", "archive_book_id");
    public static final NamespacedKey SCHEMA = new NamespacedKey("infinitygear", "archive_book_schema");
    private ArchiveBookIdentity() {}
    public static boolean marked(ItemStack item) {
        return item != null && item.hasItemMeta() && (item.getItemMeta().getPersistentDataContainer().has(ID)
                || item.getItemMeta().getPersistentDataContainer().has(SCHEMA)
                || "ARCHIVE_BOOK".equals(item.getItemMeta().getPersistentDataContainer().get(
                        com.infinitygear.data.GearData.KEY_KIND, PersistentDataType.STRING)));
    }
    public static void rejectLegacyMutation(ItemStack item) {
        if (marked(item)) throw new IllegalArgumentException("Tracked Archive book requires a journaled provenance lifecycle operation");
    }
    public static void stamp(ItemStack item, BookLedger.Receipt receipt) {
        if (receipt.consumed() || item.getAmount() != 1) throw new IllegalArgumentException("Unconsumed single book required");
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(ID, PersistentDataType.STRING, receipt.bookId().toString());
        meta.getPersistentDataContainer().set(SCHEMA, PersistentDataType.INTEGER, 1);
        item.setItemMeta(meta);
    }
    public static boolean matches(ItemStack item, BookLedger.Receipt receipt) {
        if (item == null || item.getAmount() != 1 || receipt == null || receipt.consumed()
                || !(item.getItemMeta() instanceof EnchantmentStorageMeta meta)) return false;
        var pdc = meta.getPersistentDataContainer();
        if (!Integer.valueOf(1).equals(pdc.get(SCHEMA, PersistentDataType.INTEGER))) return false;
        if (!receipt.bookId().toString().equals(pdc.get(ID, PersistentDataType.STRING))) return false;
        var identity = com.infinitygear.data.TrackedItemData.read(item);
        if (identity == null || identity.kind() != com.infinitygear.data.TrackedKind.ARCHIVE_BOOK
                || !identity.uuid().equals(receipt.bookId()) || identity.quarantined()
                || !identity.type().equals(receipt.issue().enchantmentKey())) return false;
        return meta.getEnchants().isEmpty() && meta.getStoredEnchants().size() == 1
                && meta.getStoredEnchants().entrySet().stream().allMatch(e -> e.getKey().getKey().toString()
                .equals(receipt.issue().enchantmentKey()) && e.getValue() == receipt.issue().level());
    }
}

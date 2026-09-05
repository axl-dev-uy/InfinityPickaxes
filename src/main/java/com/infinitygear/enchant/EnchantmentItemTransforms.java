package com.infinitygear.enchant;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;

/** Safe Bukkit metadata adapter for removal and transfer previews. Inputs are never mutated. */
public final class EnchantmentItemTransforms {
    public record Removal(ItemStack sourceResult, int removedLevel) {}
    public record Transfer(ItemStack sourceResult, ItemStack bookResult, int transferredLevel) {}
    private final CanonicalBookFactory books = new CanonicalBookFactory();

    public Map<Enchantment, Integer> enchantments(ItemStack source) {
        Map<Enchantment, Integer> result = new LinkedHashMap<>();
        if (source == null || !source.hasItemMeta()) return result;
        ItemMeta meta = source.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) result.putAll(storage.getStoredEnchants());
        meta.getEnchants().forEach(result::putIfAbsent);
        return result;
    }

    public Removal remove(ItemStack source, Enchantment selected, boolean removable) {
        com.infinitygear.integration.ArchiveBookIdentity.rejectLegacyMutation(source);
        if (source == null || source.getAmount() != 1 || selected == null) throw new IllegalArgumentException("One source item is required.");
        Integer level = enchantments(source).get(selected);
        if (level == null) throw new IllegalArgumentException("Selected enchantment is not present.");
        if (!removable) throw new IllegalArgumentException("Selected enchantment is non-removable.");
        ItemStack result = source.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) storage.removeStoredEnchant(selected);
        meta.removeEnchant(selected);
        result.setItemMeta(meta);
        if (result.getType() == Material.ENCHANTED_BOOK && enchantments(result).isEmpty()) {
            result = new ItemStack(Material.BOOK);
        }
        return new Removal(result, level);
    }

    public Transfer transfer(ItemStack source, Enchantment selected, int standardMaximum, ItemStack blankBook) {
        return transfer(source, selected, standardMaximum, blankBook, true);
    }

    public Transfer transfer(ItemStack source, Enchantment selected, int standardMaximum,
                             ItemStack blankBook, boolean removable) {
        if (blankBook == null || blankBook.getType() != Material.BOOK || blankBook.getAmount() < 1
                || blankBook.hasItemMeta() && (!blankBook.getItemMeta().getEnchants().isEmpty())) {
            throw new IllegalArgumentException("A blank ordinary book is required.");
        }
        Removal removal = remove(source, selected, removable);
        if (removal.removedLevel() > standardMaximum) {
            throw new IllegalArgumentException("LimitBroken enchantments cannot be transferred.");
        }
        return new Transfer(removal.sourceResult(), books.create(selected, removal.removedLevel()), removal.removedLevel());
    }
}

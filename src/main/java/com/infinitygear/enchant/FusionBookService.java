package com.infinitygear.enchant;

import com.infinitypickaxes.core.enchant.EnchantManager;
import com.infinitypickaxes.core.enchant.EnchantSocket;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bukkit adapter around the pure planner; all outputs are fresh canonical books. */
public final class FusionBookService {
    public enum Failure { NONE, INVALID_BOOK, MULTIPLE_ENCHANTMENTS, DIFFERENT_ENCHANTMENTS,
        DIFFERENT_LEVELS, STANDARD_MAXIMUM, NO_MATCHING_PAIR }
    public record Result(Failure failure, EnchantSocket enchantment, FusionCalculator.Plan plan,
                         List<ItemStack> consumedInputs, List<ItemStack> outputs) {
        public boolean allowed() { return failure == Failure.NONE; }
    }

    private final EnchantManager enchants;
    private final CanonicalBookFactory books = new CanonicalBookFactory();

    public FusionBookService(EnchantManager enchants) { this.enchants = enchants; }

    public Result pair(ItemStack first, ItemStack second) {
        var a = single(first); var b = single(second);
        if (a == null || b == null) return failure(Failure.INVALID_BOOK);
        if (!a.socket().getKeyString().equalsIgnoreCase(b.socket().getKeyString())) return failure(Failure.DIFFERENT_ENCHANTMENTS);
        if (a.level() != b.level()) return failure(Failure.DIFFERENT_LEVELS);
        if (a.level() >= a.socket().getMaxLevel()) return failure(Failure.STANDARD_MAXIMUM);
        FusionCalculator.Plan plan = FusionCalculator.fusePair(a.level(), b.level(), a.socket().getMaxLevel());
        ItemStack output = books.create(enchants.getEnchantment(a.socket().getKeyString()), a.level() + 1);
        return new Result(Failure.NONE, a.socket(), plan, List.of(first.clone(), second.clone()), List.of(output));
    }

    public Result bulk(List<ItemStack> candidates, String selectedCanonicalKey) {
        if (candidates == null) return failure(Failure.INVALID_BOOK);
        String selected = selectedCanonicalKey == null ? "" : selectedCanonicalKey.toLowerCase(Locale.ROOT);
        List<ItemStack> matching = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        EnchantSocket socket = enchants.getSocketByKey(selected);
        if (socket == null) return failure(Failure.INVALID_BOOK);
        for (ItemStack candidate : candidates) {
            var enchant = single(candidate);
            if (enchant != null && enchant.socket().getKeyString().equalsIgnoreCase(selected)) {
                matching.add(candidate);
                levels.add(enchant.level());
            }
        }
        final FusionCalculator.Plan plan;
        try { plan = FusionCalculator.fuseAll(levels, socket.getMaxLevel()); }
        catch (IllegalArgumentException invalid) { return failure(Failure.NO_MATCHING_PAIR); }
        List<ItemStack> consumed = plan.consumedInputIndices().stream().map(matching::get).map(ItemStack::clone).toList();
        List<ItemStack> outputs = plan.createdOutputs().stream()
                .map(level -> books.create(enchants.getEnchantment(socket.getKeyString()), level)).toList();
        return new Result(Failure.NONE, socket, plan, consumed, outputs);
    }

    private EnchantManager.ManagedBookEnchant single(ItemStack item) {
        if (com.infinitygear.integration.ArchiveBookIdentity.marked(item)) return null;
        if (item == null || item.getType() != org.bukkit.Material.ENCHANTED_BOOK || !item.hasItemMeta()) return null;
        java.util.Set<org.bukkit.enchantments.Enchantment> all = new java.util.HashSet<>(item.getEnchantments().keySet());
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage) {
            all.addAll(storage.getStoredEnchants().keySet());
        }
        if (all.size() != 1) return null;
        List<EnchantManager.ManagedBookEnchant> found = enchants.getManagedBookEnchants(item);
        return found.size() == 1 ? found.getFirst() : null;
    }

    private Result failure(Failure failure) { return new Result(failure, null, null, List.of(), List.of()); }
}

package com.infinitygear.mining;

import com.infinitygear.data.GearData;
import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.pickaxe.PickaxeData;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** One shared local-custody resolver for adoption, recovery, and confirmed mining projection. */
final class XpItemCustody {
    record Located(ItemStack item, Player owner, String location, int copies, boolean quarantined) {
        boolean uniqueAndUsable(InfinityPickaxes plugin) {
            return copies == 1 && item != null && item.getAmount() == 1 && !quarantined
                    && !plugin.getDuplicateService().isRestricted(itemId(item));
        }

        /** Bukkit may return distinct ItemStack wrappers for the same inventory slot.  Prove
         * physical main-hand custody from the resolver's exact slot, UUID and current contents
         * instead of relying on Java reference identity. */
        boolean uniqueMainHand(Player expectedOwner, UUID expectedId) {
            if (copies != 1 || item == null || owner != expectedOwner || expectedOwner == null) return false;
            ItemStack held = expectedOwner.getInventory().getItemInMainHand();
            String heldLocation = "player:" + expectedOwner.getName() + ':'
                    + expectedOwner.getInventory().getHeldItemSlot();
            return location.equals(heldLocation) && item.getAmount() == 1 && held.getAmount() == 1
                    && expectedId.equals(itemId(item)) && expectedId.equals(itemId(held))
                    && item.isSimilar(held);
        }
    }

    private final InfinityPickaxes plugin;

    XpItemCustody(InfinityPickaxes plugin) { this.plugin = Objects.requireNonNull(plugin); }

    Located locate(UUID id) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        Objects.requireNonNull(id);
        List<Located> matches = new ArrayList<>();
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player player : Bukkit.getOnlinePlayers()) {
            collect(player.getInventory(), player, "player:" + player.getName(), id, seen, matches);
            collect(player.getEnderChest(), player, "enderchest:" + player.getName(), id, seen, matches);
            collect(player.getOpenInventory().getTopInventory(), player, "open:" + player.getName(), id, seen, matches);
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) for (Item entity : world.getEntitiesByClass(Item.class)) {
            collectItem(entity.getItemStack(), null, "drop:" + entity.getUniqueId(), id, seen, matches, 0);
        }
        int copies = matches.stream().mapToInt(Located::copies).sum();
        if (matches.isEmpty()) return new Located(null, null, "not-visible", 0, false);
        Located first = matches.getFirst();
        return new Located(first.item(), first.owner(), matches.stream().map(Located::location).distinct()
                .reduce((a, b) -> a + "," + b).orElse(first.location()), copies,
                matches.stream().anyMatch(Located::quarantined));
    }

    ItemStack projectable(UUID id) {
        Located located = locate(id);
        return located.uniqueAndUsable(plugin) ? located.item() : null;
    }

    private void collect(Inventory inventory, Player owner, String label, UUID id,
                         Set<ItemStack> seen, List<Located> matches) {
        if (inventory == null) return;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++)
            collectItem(contents[slot], owner, label + ":" + slot, id, seen, matches, 0);
    }

    private void collectItem(ItemStack item, Player owner, String location, UUID id,
                             Set<ItemStack> seen, List<Located> matches, int depth) {
        if (item == null || !seen.add(item)) return;
        if (id.equals(itemId(item))) matches.add(observation(item, owner, location));
        int maximumDepth = Math.max(0, plugin.getConfigManager().getConfig()
                .getInt("duplicate-protection.container-recursion-depth", 3));
        if (depth >= maximumDepth || !(item.getItemMeta() instanceof BlockStateMeta blockMeta)
                || !(blockMeta.getBlockState() instanceof TileStateInventoryHolder holder)) return;
        ItemStack[] nested = holder.getInventory().getContents();
        for (int slot = 0; slot < nested.length; slot++)
            collectItem(nested[slot], owner, location + "/container:" + slot, id, seen, matches, depth + 1);
    }

    private Located observation(ItemStack item, Player owner, String location) {
        boolean quarantined = item.hasItemMeta() && (item.getItemMeta().getPersistentDataContainer().has(GearData.KEY_QUARANTINED)
                || item.getItemMeta().getPersistentDataContainer().has(PickaxeData.KEY_QUARANTINED));
        return new Located(item, owner, location, Math.max(1, item.getAmount()), quarantined);
    }

    static UUID itemId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(GearData.KEY_UUID, PersistentDataType.STRING);
        if (value == null) return PickaxeData.getPickaxeUuid(item);
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException malformed) { return null; }
    }
}

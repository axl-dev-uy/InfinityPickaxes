package com.infinitypickaxes.core.pickaxe;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.utils.ProgressBarUtil;
import com.infinitypickaxes.utils.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class PickaxeManager {

    private final InfinityPickaxes plugin;

    public PickaxeManager(InfinityPickaxes plugin) {
        this.plugin = plugin;
    }

    /**
     * Checks whether a Material is any recognized pickaxe.
     */
    public boolean isPickaxeMaterial(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_PICKAXE");
    }

    /**
     * Creates a brand new Infinity Pickaxe item stack.
     */
    public ItemStack createPickaxe(int startingLevel) {
        if (plugin.getDuplicateService() != null && !plugin.getDuplicateService().authorityReady()) {
            throw new IllegalStateException("Tracked-item authority is unavailable");
        }
        FileConfiguration config = plugin.getConfigManager().getConfig();
        Material material = Material.matchMaterial(config.getString("settings.default-material", "NETHERITE_PICKAXE"));
        if (material == null) material = Material.NETHERITE_PICKAXE;

        ItemStack item = new ItemStack(material);
        applyDefaultEnchantments(item, config);
        InfinityPickaxe pickaxe = new InfinityPickaxe(
                item,
                UUID.randomUUID(),
                startingLevel,
                0.0,
                0L
        );

        syncPickaxe(pickaxe);
        return pickaxe.getItemStack();
    }

    /**
     * Converts a vanilla pickaxe into an Infinity Pickaxe on the fly, preserving any existing enchantments.
     */
    public InfinityPickaxe convertVanillaPickaxe(ItemStack item, Player player) {
        if (plugin.getDuplicateService() != null && !plugin.getDuplicateService().authorityReady()) return null;
        if (item == null || !isPickaxeMaterial(item.getType())) return null;
        if (PickaxeData.isInfinityPickaxe(item)) {
            if (plugin.getDuplicateService() != null && !plugin.getDuplicateService().isUsable(item)) return null;
            if (plugin.getGearManager() != null) plugin.getGearManager().inspect(item, true);
            return PickaxeData.fromItemStack(item);
        }

        applyDefaultEnchantments(item, plugin.getConfigManager().getConfig());
        InfinityPickaxe pickaxe = new InfinityPickaxe(
                item,
                UUID.randomUUID(),
                0,
                0.0,
                0L
        );

        syncPickaxe(pickaxe);
        return pickaxe;
    }

    /**
     * Gets existing InfinityPickaxe or auto-converts a vanilla pickaxe if auto-conversion is enabled.
     */
    public InfinityPickaxe getOrCreatePickaxe(ItemStack item, Player player) {
        if (item == null || !isPickaxeMaterial(item.getType())) return null;

        if (PickaxeData.isInfinityPickaxe(item)) {
            if (plugin.getDuplicateService() != null && !plugin.getDuplicateService().isUsable(item)) return null;
            if (plugin.getGearManager() != null) plugin.getGearManager().inspect(item, true);
            return PickaxeData.fromItemStack(item);
        }

        FileConfiguration config = plugin.getConfigManager().getConfig();
        if (config.getBoolean("settings.auto-convert-vanilla", false)) {
            return convertVanillaPickaxe(item, player);
        }

        return null;
    }

    /**
     * Synchronizes the pickaxe state to its underlying ItemStack.
     */
    public void syncPickaxe(InfinityPickaxe pickaxe) {
        if (pickaxe == null) return;
        ItemStack item = pickaxe.getItemStack();
        if (item == null) return;

        // 1. Save data into PDC
        PickaxeData.saveToItemStack(pickaxe, item);
        int progressionSockets = plugin.getEnchantManager() == null ? 0
                : plugin.getEnchantManager().getSocketLimit(pickaxe.getLevel());
        var existingGear = com.infinitygear.data.GearData.read(item, progressionSockets, false);
        int socketCapacity = existingGear.valid()
                ? Math.max(progressionSockets, existingGear.gear().socketCapacity()) : progressionSockets;
        var gear = new com.infinitygear.gear.GearInstance(item, pickaxe.getUuid(),
                com.infinitygear.data.GearData.LEGACY_PICKAXE_PROFILE, pickaxe.getLevel(), pickaxe.getXp(),
                pickaxe.getBlocksMined(), socketCapacity);
        com.infinitygear.data.GearData.save(gear, PickaxeData.isQuarantined(item), true);

        // 2. Refresh Lore, Display Name, Unbreakable & Enchants
        updateLore(pickaxe);
    }

    /**
     * Rebuilds the pickaxe ItemMeta, including dynamic Lore, Display Name, Real Enchantments, and Unbreakable tags.
     */
    public void updateLore(InfinityPickaxe pickaxe) {
        if (pickaxe == null || pickaxe.getItemStack() == null) return;

        ItemStack item = pickaxe.getItemStack();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        FileConfiguration config = plugin.getConfigManager().getConfig();

        // 1. Unbreakable & Flags
        meta.setUnbreakable(config.getBoolean("settings.unbreakable", true));
        // EcoEnchants uses a pre-existing HIDE_ENCHANTS flag as an explicit
        // instruction to suppress its generated enchantment lore. Always clear
        // our old flag and let EcoEnchants own enchant display and hiding.
        meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        if (config.getBoolean("settings.hide-flags", true)) {
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        }

        // 2. Display Name (Only modified if custom-display-name is enabled)
        if (config.getBoolean("pickaxe-lore.custom-display-name", false)) {
            String nameTemplate = config.getString("pickaxe-lore.display-name", "");
            if (!nameTemplate.isEmpty()) {
                nameTemplate = nameTemplate.replace("%level%", String.valueOf(pickaxe.getLevel()));
                meta.displayName(TextUtil.parse(nameTemplate));
            }
        }

        // 3. Progress bar calculation
        double reqXp = plugin.getLevelManager().getRequiredXp(pickaxe.getLevel());
        String bar = ProgressBarUtil.getProgressBar(
                pickaxe.getXp(),
                reqXp,
                config.getInt("progress-bar.total-bars", 20),
                config.getString("progress-bar.completed-symbol", "■"),
                config.getString("progress-bar.uncompleted-symbol", "□"),
                config.getString("progress-bar.completed-color", "<#00FF88>"),
                config.getString("progress-bar.uncompleted-color", "<#555555>")
        );

        // 4. Assemble only InfinityPickaxes-owned lore. EcoEnchants owns enchantment display.
        List<String> loreTemplates = config.getStringList("pickaxe-lore.lore");
        List<Component> finalLore = new ArrayList<>();

        if (plugin.getDuplicateService() != null && plugin.getDuplicateService().isRestricted(pickaxe.getUuid())) {
            List<String> quarantineLore = config.isList("pickaxe-lore.quarantine-lore")
                    ? config.getStringList("pickaxe-lore.quarantine-lore")
                    : List.of(
                    "<red><b>QUARANTINED PICKAXE</b></red>",
                    "<gray>Duplicate UUID: <white>%uuid%</white></gray>",
                    "<yellow>Contact an administrator to resolve this item.</yellow>");
            for (String template : quarantineLore) {
                finalLore.add(TextUtil.parse(template
                        .replace("%uuid%", pickaxe.getUuid().toString())
                        .replace("%profile%", com.infinitygear.data.GearData.LEGACY_PICKAXE_PROFILE)));
            }
        }

        int maxSockets = plugin.getEnchantManager().getSocketLimit(pickaxe);
        for (String template : loreTemplates) {
            if (template.contains("%enchants_list%")) {
                continue; // Legacy token: EcoEnchants owns and renders this section.
            } else {
                String processed = template
                        .replace("%level%", String.valueOf(pickaxe.getLevel()))
                        .replace("%max_level%", String.valueOf(plugin.getLevelManager().getMaxLevel()))
                        .replace("%current_xp%", String.format("%.0f", pickaxe.getXp()))
                        .replace("%required_xp%", String.format("%.0f", reqXp))
                        .replace("%xp_bar%", bar)
                        .replace("%blocks_mined%", String.format("%,d", pickaxe.getBlocksMined()))
                        .replace("%enchant_count%", String.valueOf(plugin.getEnchantManager().countUsedSockets(pickaxe)))
                        .replace("%max_sockets%", String.valueOf(maxSockets));
                finalLore.add(TextUtil.parse(processed));
            }
        }

        meta.lore(finalLore);
        item.setItemMeta(meta);
    }

    /**
     * Checks if the item held by a player in main hand is an Infinity Pickaxe (or converts vanilla) and returns the model.
     */
    public InfinityPickaxe getHeldPickaxe(Player player) {
        if (player == null) return null;
        ItemStack held = player.getInventory().getItemInMainHand();
        return getOrCreatePickaxe(held, player);
    }

    private void applyDefaultEnchantments(ItemStack item, FileConfiguration config) {
        int efficiencyLevel = Math.max(0, config.getInt("settings.default-efficiency-level", 20));
        if (efficiencyLevel > item.getEnchantmentLevel(Enchantment.EFFICIENCY)) {
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, efficiencyLevel);
        }
    }

}

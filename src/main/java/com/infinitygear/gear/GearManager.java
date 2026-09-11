package com.infinitygear.gear;

import com.infinitygear.data.GearData;
import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.utils.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class GearManager {
    private final InfinityPickaxes plugin;
    private final GearProfileRegistry profiles;

    public GearManager(InfinityPickaxes plugin, GearProfileRegistry profiles) {
        this.plugin = plugin;
        this.profiles = profiles;
    }

    public Optional<GearInstance> inspect(ItemStack item, boolean migrate) {
        var legacy = com.infinitypickaxes.core.pickaxe.PickaxeData.fromItemStack(item);
        int legacySockets = plugin.getEnchantManager() == null ? 0
                : plugin.getEnchantManager().getSocketLimit(legacy == null ? 0 : legacy.getLevel());
        GearData.ReadResult read = GearData.read(item, legacySockets, migrate);
        if (!read.valid()) {
            if (read.state() == GearData.State.MALFORMED_LEGACY || read.state() == GearData.State.MALFORMED_NEW) {
                plugin.getLogger().warning("Preserved malformed gear without assigning a new UUID: " + read.diagnostic());
            }
            return Optional.empty();
        }
        return profiles.find(read.gear().profileId()).filter(GearProfile::enabled)
                .filter(profile -> accepts(profile, item)).map(profile -> read.gear());
    }

    public ItemStack create(String profileId, int startingLevel) {
        if (plugin.getDuplicateService() != null && !plugin.getDuplicateService().authorityReady()) {
            throw new IllegalStateException("Tracked-item authority is unavailable");
        }
        GearProfile profile = profiles.find(profileId).filter(GearProfile::enabled)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or disabled gear profile: " + profileId));
        int level = Math.min(profile.maximumLevel(), Math.max(0, startingLevel));
        // The migrated pickaxe profile must retain the hardened legacy factory:
        // it owns configured default Efficiency, name/lore, flags, and mirror PDC.
        if (GearData.LEGACY_PICKAXE_PROFILE.equals(profile.id())) {
            return plugin.getPickaxeManager().createPickaxe(level);
        }
        ItemStack item;
        if (!profile.defaultExternalProvider().isBlank()) {
            if (!"nexo".equals(profile.defaultExternalProvider())
                    || !plugin.getServer().getPluginManager().isPluginEnabled("Nexo")) {
                throw new IllegalStateException("Default external item provider is unavailable: "
                        + profile.defaultExternalProvider());
            }
            var nexo = new com.infinitygear.nexo.NexoProvider();
            item = nexo.createItem(profile.defaultExternalItemId());
            if (item == null) throw new IllegalStateException("Invalid default Nexo item: "
                    + profile.defaultExternalItemId());
            item.setAmount(1);
        } else {
            item = new ItemStack(profile.defaultMaterial());
        }
        GearInstance gear = new GearInstance(item, UUID.randomUUID(), profile.id(),
                level, 0, 0,
                profile.socketCapacityAtLevel(level));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(profile.unbreakable());
            item.setItemMeta(meta);
        }
        GearData.save(gear, false, GearData.LEGACY_PICKAXE_PROFILE.equals(profile.id()));
        refreshPresentation(gear, profile);
        return item;
    }

    /**
     * Converts one ordinary vanilla item when exactly one enabled profile opts in.
     * Existing gear is only inspected; stacked items and ambiguous profile matches
     * fail closed so a unique identity is never stamped onto multiple objects.
     */
    public Optional<GearInstance> autoConvert(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() != 1) return Optional.empty();
        if (GearData.isGear(item)) {
            Optional<GearInstance> existing = inspect(item, true);
            existing.filter(ignored -> plugin.getDuplicateService() == null
                            || plugin.getDuplicateService().isUsable(item))
                    .ifPresent(this::refreshPresentation);
            return existing;
        }
        if (plugin.getDuplicateService() != null && !plugin.getDuplicateService().authorityReady()) return Optional.empty();
        if (isRecognizedExternalItem(item)) return Optional.empty();

        List<GearProfile> matches = profiles.accepting(item.getType(), true);
        if (matches.size() != 1) return Optional.empty();

        GearProfile profile = matches.getFirst();
        if (GearData.LEGACY_PICKAXE_PROFILE.equals(profile.id())) {
            var converted = plugin.getPickaxeManager().convertVanillaPickaxe(item, null);
            return converted == null ? Optional.empty() : inspect(item, true);
        }

        GearInstance gear = new GearInstance(item, UUID.randomUUID(), profile.id(),
                0, 0, 0, profile.socketCapacityAtLevel(0));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Optional.empty();
        meta.setUnbreakable(profile.unbreakable());
        item.setItemMeta(meta);
        GearData.save(gear, false, false);
        refreshPresentation(gear, profile);
        return Optional.of(gear);
    }

    private boolean isRecognizedExternalItem(ItemStack item) {
        if (plugin.getServer() == null || !plugin.getServer().getPluginManager().isPluginEnabled("Nexo")) {
            return false;
        }
        try {
            return new com.infinitygear.nexo.NexoProvider().itemId(item) != null;
        } catch (LinkageError | RuntimeException unavailable) {
            // If Nexo is enabled but its item classifier is unavailable, fail
            // closed instead of destructively treating a custom item as vanilla.
            return true;
        }
    }

    /** Rebuilds profile presentation while leaving enchantments and unconfigured names intact. */
    public void refreshPresentation(GearInstance gear) {
        if (gear == null || com.infinitygear.integration.BookLifecycleItems.hasCustodyMarker(gear.item())) return;
        profiles.find(gear.profileId()).filter(GearProfile::enabled)
                .ifPresent(profile -> refreshPresentation(gear, profile));
    }

    /** Refreshes one already-managed item without ever converting ordinary equipment. */
    public void refreshPresentation(ItemStack item) {
        if (!GearData.isGear(item) || com.infinitygear.integration.BookLifecycleItems.hasCustodyMarker(item)) return;
        inspect(item, true).ifPresent(this::refreshPresentation);
    }

    private void refreshPresentation(GearInstance gear, GearProfile profile) {
        if (GearData.LEGACY_PICKAXE_PROFILE.equals(profile.id())) {
            var legacy = com.infinitypickaxes.core.pickaxe.PickaxeData.fromItemStack(gear.item());
            if (legacy != null) plugin.getPickaxeManager().syncPickaxe(legacy);
            return;
        }
        ItemMeta meta = gear.item().getItemMeta();
        if (meta == null) return;
        meta.setUnbreakable(profile.unbreakable());
        // EcoEnchants treats this flag as a request to suppress its generated lore.
        meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        String displayName = render(profile.displayName(), gear, profile);
        if (!displayName.isBlank()) meta.displayName(TextUtil.parse(displayName));
        List<Component> lore = new ArrayList<>();
        boolean quarantined = plugin.getDuplicateService() != null
                && plugin.getDuplicateService().isRestricted(gear.uuid());
        if (quarantined) {
            var config = plugin.getConfigManager().getConfig();
            List<String> warning = config.isList("gear-lore.quarantine-lore")
                    ? config.getStringList("gear-lore.quarantine-lore") : List.of(
                    "<red><b>QUARANTINED GEAR</b></red>",
                    "<gray>Duplicate UUID: <white>%uuid%</white></gray>",
                    "<yellow>Contact an administrator to resolve this item.</yellow>");
            for (String template : warning) lore.add(TextUtil.parse(render(template, gear, profile)));
        }
        for (String template : profile.lore()) lore.add(TextUtil.parse(render(template, gear, profile)));
        meta.lore(lore);
        gear.item().setItemMeta(meta);
    }

    static String render(String template, GearInstance gear, GearProfile profile) {
        if (template == null || template.isEmpty()) return "";
        String material = humanize(gear.item().getType().name());
        return template
                .replace("%uuid%", gear.uuid().toString())
                .replace("%profile%", gear.profileId())
                .replace("%level%", String.valueOf(gear.level()))
                .replace("%max_level%", String.valueOf(profile.maximumLevel()))
                .replace("%current_xp%", String.format(Locale.ROOT, "%.0f", gear.xp()))
                .replace("%xp%", String.format(Locale.ROOT, "%.0f", gear.xp()))
                .replace("%blocks_mined%", String.format(Locale.ROOT, "%,d", gear.blocksMined()))
                .replace("%sockets%", String.valueOf(gear.socketCapacity()))
                .replace("%max_sockets%", String.valueOf(profile.maximumExpandedSockets()))
                .replace("%material%", material)
                .replace("%item%", material);
    }

    private static String humanize(String material) {
        StringBuilder result = new StringBuilder();
        for (String word : material.toLowerCase(Locale.ROOT).split("_")) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private boolean accepts(GearProfile profile, ItemStack item) {
        if (profile.accepts(item.getType())) return true;
        if (profile.externalItemIds().isEmpty()
                || !plugin.getServer().getPluginManager().isPluginEnabled("Nexo")) return false;
        try {
            String id = new com.infinitygear.nexo.NexoProvider().itemId(item);
            return id != null && profile.acceptsExternal("nexo", id);
        } catch (LinkageError unavailable) {
            return false;
        }
    }
}

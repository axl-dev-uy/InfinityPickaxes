package com.infinitypickaxes.core.enchant;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.api.events.PickaxeEnchantUpgradeEvent;
import com.infinitypickaxes.core.pickaxe.InfinityPickaxe;
import com.infinitypickaxes.utils.SoundUtil;
import com.infinitygear.enchant.EnchantmentApplicationPolicy;
import com.infinitygear.api.events.GearEnchantChangeEvent;
import com.infinitygear.data.GearData;
import com.infinitygear.enchant.ResolvedEnchantmentPolicy;
import com.infinitygear.gear.GearProfile;
import com.willfp.ecoenchants.enchant.EcoEnchant;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class EnchantManager {

    private final InfinityPickaxes plugin;
    private final EcoEnchantsHook ecoHook;
    private final Map<String, EnchantSocket> socketsById = new LinkedHashMap<>();
    private final Map<String, EnchantSocket> socketsByKey = new LinkedHashMap<>();
    private PickaxeProgressionPolicy progressionPolicy;

    private Sound upgradeSound = Sound.BLOCK_ANVIL_USE;
    private float upgradeSoundVolume = 1.0f;
    private float upgradeSoundPitch = 1.1f;

    public EnchantManager(InfinityPickaxes plugin) {
        this.plugin = plugin;
        this.ecoHook = new EcoEnchantsHook(plugin);
        loadConfig();
    }

    public void loadConfig() {
        socketsById.clear();
        socketsByKey.clear();

        FileConfiguration config = plugin.getConfigManager().getEnchantsConfig();
        this.progressionPolicy = PickaxeProgressionPolicy.from(config);

        // Sound settings
        this.upgradeSound = SoundUtil.resolve(config.getString(
                "settings.upgrade-sound.sound", "BLOCK_ANVIL_USE"), Sound.BLOCK_ANVIL_USE);
        this.upgradeSoundVolume = (float) config.getDouble("settings.upgrade-sound.volume", 1.0);
        this.upgradeSoundPitch = (float) config.getDouble("settings.upgrade-sound.pitch", 1.1);

        // EcoEnchants supplies custom enchantments. Fortune and Silk Touch are
        // deliberately managed alongside them as vanilla socket exceptions.
        discoverAndRegisterEcoEnchants();

        long enabledSockets = socketsById.values().stream().filter(EnchantSocket::isEnabled).count();
        plugin.getLogger().info("Loaded " + socketsById.size() + " compatible enchantment sockets ("
                + enabledSockets + " enabled).");
        if (socketsById.isEmpty() && ecoHook.isEcoEnchantsPresent()) {
            plugin.getLogger().warning("EcoEnchants is enabled, but no pickaxe-compatible enchantments "
                    + "were discovered. The enchantment menu will remain unavailable until EcoEnchants "
                    + "has loaded its registry; run /ipickaxe reload afterward.");
        } else if (!socketsById.isEmpty() && enabledSockets == 0) {
            plugin.getLogger().warning("Every discovered managed enchantment is disabled in enchants.yml; "
                    + "the enchantment menu has no available sockets.");
        }
    }

    public void discoverAndRegisterEcoEnchants() {
        FileConfiguration policy = plugin.getConfigManager().getEnchantsConfig();
        Collection<EcoEnchant> liveEnchants = ecoHook.getGearEnchants();
        synchronizePolicy(policy, liveEnchants);
        int added = 0;

        for (EcoEnchant ecoEnchant : liveEnchants) {
            Enchantment ench = ecoEnchant.getEnchantment();
            if (ench == null || ench.getKey() == null) continue;
            String keyStr = ench.getKey().toString().toLowerCase(Locale.ROOT);
            String id = ecoEnchant.getID().toLowerCase(Locale.ROOT);
            String path = "enchants." + id;

            String displayName = ecoHook.getEnchantmentDisplayName(ench,
                    policy.getString(path + ".display-color", "<gray>"));
            List<String> desc = ecoHook.getEnchantmentDescription(ench);
            int nativeMax = Math.max(1, ecoEnchant.getMaximumLevel());
            int maxLevel = effectiveMaximum(policy.get(path + ".max-level"), nativeMax, id);
            int unlockLevel = Math.max(0, policy.getInt(path + ".unlock-pickaxe-level", 0));
            boolean enabled = policy.getBoolean(path + ".enabled", true);
            Set<String> additionalConflicts = new LinkedHashSet<>(
                    policy.getStringList(path + ".additional-conflicts"));

            String configuredKey = policy.getString(path + ".key", keyStr);
            if (!keyStr.equalsIgnoreCase(configuredKey)) {
                plugin.getLogger().warning("Ignoring non-canonical key for EcoEnchant '" + id
                        + "' in enchants.yml; live key is " + keyStr + ".");
            }

            EnchantSocket socket = new EnchantSocket(
                    id,
                    keyStr,
                    ench.getKey(),
                    displayName,
                    Material.ENCHANTED_BOOK,
                    -1,
                    enabled,
                    unlockLevel,
                    maxLevel,
                    new TreeMap<>(),
                    desc,
                    null,
                    additionalConflicts
            );

            socketsById.put(id, socket);
            socketsByKey.put(keyStr, socket);
            added++;
        }

        if (added > 0) {
            plugin.getLogger().info("Loaded " + added + " gear enchantments from EcoEnchants.");
        }
        registerVanillaSocket(policy, "fortune", "minecraft:fortune", "Fortune",
                List.of("<gray>Increases the drops from certain blocks.</gray>"), true);
        registerVanillaSocket(policy, "silk_touch", "minecraft:silk_touch", "Silk Touch",
                List.of("<gray>Allows compatible blocks to drop themselves.</gray>"), false);
        validateAdditionalConflicts();
        validateProfileOverrides();
    }

    public EnchantSocket getSocket(String id) {
        if (id == null) return null;
        return socketsById.get(id.toLowerCase());
    }

    public EnchantSocket getSocketByKey(String keyString) {
        if (keyString == null) return null;
        return socketsByKey.get(keyString.toLowerCase());
    }

    public Collection<EnchantSocket> getAllSockets() {
        return Collections.unmodifiableCollection(socketsById.values());
    }

    public PickaxeProgressionPolicy getProgressionPolicy() {
        return progressionPolicy;
    }

    public int getSocketLimit(int pickaxeLevel) {
        return progressionPolicy == null ? 0 : progressionPolicy.getSocketLimit(pickaxeLevel);
    }

    public int getSocketLimit(InfinityPickaxe pickaxe) {
        if (pickaxe == null) return 0;
        int progression = getSocketLimit(pickaxe.getLevel());
        var read = GearData.read(pickaxe.getItemStack(), progression, false);
        return read.valid() ? Math.max(progression, read.gear().socketCapacity()) : progression;
    }

    /** Counts every managed socket enchantment, including Fortune and Silk Touch. */
    public int countUsedSockets(InfinityPickaxe pickaxe) {
        if (pickaxe == null) return 0;
        int used = 0;
        for (String enchantmentKey : pickaxe.getEnchantments().keySet()) {
            EnchantSocket socket = getSocketByKey(enchantmentKey);
            if (socket != null) used = (int) Math.min(Integer.MAX_VALUE,
                    (long) used + resolvedPolicy(pickaxe, socket).socketCost());
        }
        return used;
    }

    static int countRecognizedSockets(Collection<String> enchantmentKeys,
                                      Map<String, EnchantSocket> socketsByKey) {
        if (enchantmentKeys == null || socketsByKey == null) return 0;
        int used = 0;
        for (String enchantmentKey : enchantmentKeys) {
            if (enchantmentKey == null) continue;
            EnchantSocket socket = socketsByKey.get(enchantmentKey.toLowerCase(Locale.ROOT));
            if (socket != null) {
                used++;
            }
        }
        return used;
    }

    public EcoEnchantsHook getEcoHook() {
        return ecoHook;
    }

    /**
     * Applies all rules for introducing a new managed enchantment. Existing
     * enchantments are grandfathered and can still be upgraded while over cap.
     */
    public boolean canIntroduceEnchantment(Player player, InfinityPickaxe pickaxe, EnchantSocket socket) {
        if (player == null || pickaxe == null || socket == null) return false;
        ResolvedEnchantmentPolicy candidatePolicy = resolvedPolicy(pickaxe, socket);
        if (!candidatePolicy.enabled()) return false;

        int used = countUsedSockets(pickaxe);
        int limit = getSocketLimit(pickaxe);
        if ((long) used + candidatePolicy.socketCost() > limit) {
            plugin.getMessageManager().sendMessage(player, "messages.enchant-sockets-full",
                    "%used%", String.valueOf(used),
                    "%max%", String.valueOf(limit));
            return false;
        }

        Enchantment candidate = getEnchantment(socket.getKeyString());
        for (String existingKey : pickaxe.getEnchantments().keySet()) {
            Enchantment existing = getEnchantment(existingKey);
            EnchantSocket existingSocket = getSocketByKey(existingKey);
            ResolvedEnchantmentPolicy existingPolicy = existingSocket == null ? null
                    : resolvedPolicy(pickaxe, existingSocket);
            boolean adminConflict = candidatePolicy.additionallyConflictsWith(existingKey)
                    || existingPolicy != null
                    && existingPolicy.additionallyConflictsWith(candidatePolicy.enchantmentKey());
            boolean nativeConflict = candidate != null && existing != null
                    && (candidate.conflictsWith(existing) || existing.conflictsWith(candidate));
            if (adminConflict || nativeConflict || ecoHook.conflictsWith(candidate, existing)) {
                plugin.getMessageManager().sendMessage(player, "messages.enchant-conflict",
                        "%enchant%", socket.getDisplayName());
                return false;
            }
        }

        boolean ecoManaged = ecoHook.findEcoEnchant(candidate) != null;
        boolean canApply = ecoManaged
                ? ecoHook.canApply(pickaxe.getItemStack(), candidate)
                : candidate != null && candidate.canEnchantItem(pickaxe.getItemStack());
        if (!canApply) {
            plugin.getMessageManager().sendMessage(player, "messages.enchant-conflict",
                    "%enchant%", socket.getDisplayName());
            return false;
        }
        return true;
    }

    /**
     * Checks if a player can apply a book to upgrade an enchantment socket.
     * Returns true if successfully upgraded, false otherwise.
     */
    public boolean handleSocketUpgrade(Player player, InfinityPickaxe pickaxe, EnchantSocket socket, ItemStack bookItem) {
        if (com.infinitygear.integration.ArchiveBookIdentity.marked(bookItem)) return false;
        if (player == null || pickaxe == null || socket == null || bookItem == null) {
            return false;
        }
        // Regular socket progression consumes actual enchanted books only.
        // LimitBreak validates its independently configurable PDC item.
        if (bookItem.getType() != Material.ENCHANTED_BOOK) return false;
        if (!plugin.getDuplicateService().isUsable(pickaxe.getItemStack())) {
            plugin.getMessageManager().sendMessage(player, "messages.pickaxe-quarantined");
            return false;
        }
        ResolvedEnchantmentPolicy policy = resolvedPolicy(pickaxe, socket);
        if (!policy.enabled()) return false;

        // A normal application book has one unambiguous managed identity. This
        // rejects mixed books even when the selected enchantment is present.
        List<ManagedBookEnchant> managedBookEnchants = getManagedBookEnchants(bookItem);
        if (managedBookEnchants.size() != 1) {
            plugin.getMessageManager().sendMessage(player, "messages.enchant-invalid-single-book");
            return false;
        }
        ManagedBookEnchant bookEnchant = managedBookEnchants.getFirst();
        if (!bookEnchant.socket().getKeyString().equalsIgnoreCase(socket.getKeyString())) {
            plugin.getMessageManager().sendMessage(player, "messages.enchant-invalid-book",
                    "%enchant%", socket.getDisplayName(),
                    "%required_book_level%", String.valueOf(Math.max(1,
                            pickaxe.getEnchantmentLevel(socket.getKeyString()) + 1)));
            return false;
        }

        if (!policy.unlockedAt(pickaxe.getLevel())) {
            plugin.getMessageManager().sendMessage(player, "messages.enchant-locked-pickaxe-level",
                    "%required%", String.valueOf(policy.unlockLevel()));
            return false;
        }

        int currentLevelOnPickaxe = pickaxe.getEnchantmentLevel(socket.getKeyString());
        int maxAllowedForPickaxe = policy.standardMaximum();

        int bookLevel = bookEnchant.level();
        EnchantmentApplicationPolicy.Decision decision = EnchantmentApplicationPolicy.evaluate(
                new EnchantmentApplicationPolicy.Request(
                        managedBookEnchants.size(), true, policy.enabled(),
                        policy.unlockedAt(pickaxe.getLevel()), currentLevelOnPickaxe, bookLevel,
                        countUsedSockets(pickaxe), getSocketLimit(pickaxe),
                        policy.socketCost(), maxAllowedForPickaxe, policy.absoluteMaximum(),
                        false, true));

        if (decision.failure() == EnchantmentApplicationPolicy.Failure.EQUAL_OR_LOWER_LEVEL) {
            plugin.getMessageManager().sendMessage(player, "messages.enchant-max-reached",
                    "%max%", String.valueOf(currentLevelOnPickaxe));
            return false;
        }
        if (decision.failure() == EnchantmentApplicationPolicy.Failure.ABOVE_STANDARD_MAXIMUM) {
            plugin.getMessageManager().sendMessage(player, "messages.enchant-invalid-book",
                    "%enchant%", socket.getDisplayName(),
                    "%required_book_level%", String.valueOf(maxAllowedForPickaxe));
            return false;
        }
        // Native targets/conflicts and configured additional conflicts only
        // constrain introduction. Existing grandfathered enchantments remain active.
        if (currentLevelOnPickaxe == 0 && !canIntroduceEnchantment(player, pickaxe, socket)) return false;
        if (!decision.allowed() && currentLevelOnPickaxe != 0) return false;

        int nextLevel = bookLevel;

        GearEnchantChangeEvent gearEvent = new GearEnchantChangeEvent(player, pickaxe.getItemStack(),
                GearData.LEGACY_PICKAXE_PROFILE, socket.getKeyString(), currentLevelOnPickaxe, nextLevel,
                GearEnchantChangeEvent.Operation.APPLY);
        Bukkit.getPluginManager().callEvent(gearEvent);
        if (gearEvent.isCancelled()) {
            plugin.getMessageManager().sendMessage(player, "messages.station.cancelled-by-plugin");
            return false;
        }

        // Continue firing the legacy event for the migrated pickaxe profile.
        PickaxeEnchantUpgradeEvent event = new PickaxeEnchantUpgradeEvent(player, pickaxe, socket, currentLevelOnPickaxe, nextLevel);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            plugin.getMessageManager().sendMessage(player, "messages.station.cancelled-by-plugin");
            return false;
        }

        // 6. Consume 1 book from cursor/stack
        if (bookItem.getAmount() > 1) {
            bookItem.setAmount(bookItem.getAmount() - 1);
        } else {
            bookItem.setAmount(0);
        }

        // 7. Apply upgrade to pickaxe
        pickaxe.setEnchantmentLevel(socket.getKeyString(), nextLevel);
        pickaxe.saveAndSync();

        // 8. Play sound & feedback
        player.playSound(player.getLocation(), upgradeSound, upgradeSoundVolume, upgradeSoundPitch);
        plugin.getMessageManager().sendMessage(player, "messages.enchant-upgraded",
                "%enchant%", socket.getDisplayName(),
                "%level%", String.valueOf(nextLevel));

        return true;
    }

    public ResolvedEnchantmentPolicy resolvedPolicy(InfinityPickaxe pickaxe, EnchantSocket socket) {
        GearProfile profile = plugin.getGearProfiles() == null ? null
                : plugin.getGearProfiles().find(GearData.LEGACY_PICKAXE_PROFILE).orElse(null);
        int level = pickaxe == null ? 0 : pickaxe.getLevel();
        boolean globallyRemovable = !plugin.getConfigManager().getEnchantsConfig().getBoolean(
                "enchants." + socket.getId() + ".non-removable", false);
        int limitBreakExtra = plugin.getLimitBreakManager() == null
                ? progressionPolicy.getMaximumLimitBreakExtraLevels()
                : plugin.getLimitBreakManager().getMaxExtraLevels(level);
        return ResolvedEnchantmentPolicy.resolve(profile, socket, level, limitBreakExtra,
                globallyRemovable, 1.0);
    }

    public Integer getBookLevel(ItemStack bookItem, EnchantSocket socket) {
        if (bookItem == null || socket == null) return null;
        if (bookItem.getType() != Material.ENCHANTED_BOOK) return null;
        Integer direct = getVanillaOrStoredBookLevel(bookItem, socket);
        if (direct != null) return direct;
        return ecoHook.extractEnchantsFromBook(bookItem).get(socket.getKeyString().toLowerCase(Locale.ROOT));
    }

    public boolean containsManagedEnchantBook(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) return false;
        for (EnchantSocket socket : socketsById.values()) {
            // Disabled policies still belong to InfinityGear's managed set and
            // must not become an anvil/direct-application bypass.
            if (getBookLevel(item, socket) != null) return true;
        }
        return false;
    }

    public record ManagedBookEnchant(EnchantSocket socket, int level) {}

    /** Returns each canonical managed enchantment present on an ordinary book exactly once. */
    public List<ManagedBookEnchant> getManagedBookEnchants(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) return List.of();
        List<ManagedBookEnchant> result = new ArrayList<>();
        for (EnchantSocket socket : socketsById.values()) {
            Integer level = getBookLevel(item, socket);
            if (level != null && level > 0) result.add(new ManagedBookEnchant(socket, level));
        }
        return List.copyOf(result);
    }

    /** Returns true when an anvil result introduces or raises a managed enchantment. */
    public boolean hasManagedEnchantIncrease(ItemStack original, ItemStack result) {
        if (original == null || result == null) return false;
        Map<String, Integer> originalLevels = new HashMap<>();
        Map<String, Integer> resultLevels = new HashMap<>();
        for (EnchantSocket socket : socketsById.values()) {
            Enchantment enchantment = getEnchantment(socket.getKeyString());
            if (enchantment == null) continue;
            originalLevels.put(socket.getKeyString(), original.getEnchantmentLevel(enchantment));
            resultLevels.put(socket.getKeyString(), result.getEnchantmentLevel(enchantment));
        }
        return hasAnyManagedLevelIncrease(originalLevels, resultLevels, socketsByKey.keySet());
    }

    /** Blocks anvils from raising any enchantment on managed gear, including newly discovered/unmanaged keys. */
    public boolean hasAnyEnchantIncrease(ItemStack original, ItemStack result) {
        if (original == null || result == null) return false;
        Map<String, Integer> originalLevels = new HashMap<>(), resultLevels = new HashMap<>();
        for (var entry : result.getEnchantments().entrySet()) {
            String key = entry.getKey().getKey().toString();
            originalLevels.put(key, original.getEnchantmentLevel(entry.getKey()));
            resultLevels.put(key, entry.getValue());
        }
        return hasAnyEnchantmentLevelIncrease(originalLevels, resultLevels);
    }

    static boolean hasAnyEnchantmentLevelIncrease(Map<String, Integer> originalLevels,
                                                   Map<String, Integer> resultLevels) {
        if (resultLevels == null) return false;
        Map<String, Integer> original = originalLevels == null ? Map.of() : originalLevels;
        return resultLevels.entrySet().stream().anyMatch(entry -> entry.getKey() != null
                && entry.getValue() != null && entry.getValue() > original.getOrDefault(entry.getKey(), 0));
    }

    static boolean hasAnyManagedLevelIncrease(Map<String, Integer> originalLevels,
                                              Map<String, Integer> resultLevels,
                                              Collection<String> managedKeys) {
        if (originalLevels == null || resultLevels == null || managedKeys == null) return false;
        for (String key : managedKeys) {
            if (key != null && resultLevels.getOrDefault(key, 0) > originalLevels.getOrDefault(key, 0)) {
                return true;
            }
        }
        return false;
    }

    private Integer getVanillaOrStoredBookLevel(ItemStack bookItem, EnchantSocket socket) {
        if (bookItem == null || socket == null || !bookItem.hasItemMeta()) return null;
        Enchantment enchantment = getEnchantment(socket.getKeyString());
        if (enchantment == null) return null;
        ItemMeta meta = bookItem.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            Integer stored = storageMeta.getStoredEnchants().get(enchantment);
            if (stored != null) return stored;
        }
        return meta.getEnchants().get(enchantment);
    }

    public Enchantment getEnchantment(String keyStr) {
        if (keyStr == null || keyStr.isEmpty()) return null;
        try {
            String[] parts = keyStr.split(":", 2);
            NamespacedKey key = (parts.length == 2) ? new NamespacedKey(parts[0], parts[1]) : NamespacedKey.minecraft(parts[0]);
            return Bukkit.getRegistry(Enchantment.class).get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private void synchronizePolicy(FileConfiguration policy, Collection<EcoEnchant> liveEnchants) {
        List<EnchantPolicySynchronizer.EnchantDescriptor> descriptors = new ArrayList<>(liveEnchants.stream()
                .filter(enchant -> enchant != null && enchant.getEnchantment() != null
                        && enchant.getEnchantment().getKey() != null)
                .map(enchant -> new EnchantPolicySynchronizer.EnchantDescriptor(
                        enchant.getID().toLowerCase(Locale.ROOT),
                        enchant.getEnchantment().getKey().toString().toLowerCase(Locale.ROOT),
                        EcoEnchantsHook.getDefaultDisplayColor(enchant)))
                .toList());
        descriptors.add(new EnchantPolicySynchronizer.EnchantDescriptor("fortune", "minecraft:fortune"));
        descriptors.add(new EnchantPolicySynchronizer.EnchantDescriptor("silk_touch", "minecraft:silk_touch"));
        EnchantPolicySynchronizer.SyncResult result = EnchantPolicySynchronizer.synchronize(policy, descriptors);
        result.added().forEach(id -> plugin.getLogger().info(
                "Added enchantment policy entry for '" + id + "' to enchants.yml."));
        result.updated().forEach(id -> plugin.getLogger().info(
                "Added missing display-color for '" + id + "' to enchants.yml."));
        result.migrated().forEach(id -> plugin.getLogger().warning(
                "Disabled legacy no-op enchantment '" + id
                        + "' because Infinity Pickaxes are unbreakable; administrators may re-enable it explicitly."));
        result.orphaned().forEach(id -> plugin.getLogger().warning(
                "Orphaned enchants.yml entry '" + id
                        + "' has no matching live managed pickaxe enchantment; it was preserved."));
        if (result.changed()) plugin.getConfigManager().saveEnchantsConfig();
    }

    private void registerVanillaSocket(FileConfiguration policy, String id, String key,
                                       String rawDisplayName, List<String> description,
                                       boolean supportsLimitBreak) {
        Enchantment enchantment = getEnchantment(key);
        if (enchantment == null || enchantment.getKey() == null) {
            plugin.getLogger().warning("Could not resolve vanilla enchantment '" + key + "'.");
            return;
        }
        String path = "enchants." + id;
        String canonicalKey = enchantment.getKey().toString().toLowerCase(Locale.ROOT);
        String configuredKey = policy.getString(path + ".key", canonicalKey);
        if (!canonicalKey.equalsIgnoreCase(configuredKey)) {
            plugin.getLogger().warning("Ignoring non-canonical key for vanilla enchantment '" + id
                    + "' in enchants.yml; live key is " + canonicalKey + ".");
        }
        EnchantSocket socket = new EnchantSocket(
                id,
                canonicalKey,
                enchantment.getKey(),
                configuredDisplayName(policy, path, rawDisplayName),
                Material.ENCHANTED_BOOK,
                -1,
                policy.getBoolean(path + ".enabled", true),
                Math.max(0, policy.getInt(path + ".unlock-pickaxe-level", 0)),
                effectiveVanillaMaximum(policy.get(path + ".max-level"), enchantment.getMaxLevel(), id),
                new TreeMap<>(),
                description,
                null,
                new LinkedHashSet<>(policy.getStringList(path + ".additional-conflicts")),
                supportsLimitBreak
        );
        socketsById.put(id, socket);
        socketsByKey.put(canonicalKey, socket);
    }

    static String configuredDisplayName(FileConfiguration policy, String path, String rawDisplayName) {
        String color = policy == null ? "<gray>"
                : policy.getString(path + ".display-color", "<gray>");
        return EcoEnchantsHook.formatDisplayName(rawDisplayName, color);
    }

    private int effectiveMaximum(Object configured, int nativeMaximum, String id) {
        int effective = EnchantPolicySynchronizer.effectiveMaximum(configured, nativeMaximum);
        if (configured != null
                && !"inherit".equalsIgnoreCase(String.valueOf(configured))) {
            try {
                if (Integer.parseInt(String.valueOf(configured)) < 1) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Invalid max-level for managed enchantment '" + id
                        + "'; inheriting its native maximum " + nativeMaximum + ".");
            }
        }
        return effective;
    }

    private int effectiveVanillaMaximum(Object configured, int nativeMaximum, String id) {
        int effective = EnchantPolicySynchronizer.effectiveVanillaMaximum(configured, nativeMaximum);
        if (configured != null && !"inherit".equalsIgnoreCase(String.valueOf(configured))) {
            try {
                if (Integer.parseInt(String.valueOf(configured)) < 1) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Invalid max-level for managed vanilla enchantment '" + id
                        + "'; inheriting its native maximum " + nativeMaximum + ".");
            }
        }
        return effective;
    }

    private void validateAdditionalConflicts() {
        for (EnchantSocket socket : socketsById.values()) {
            for (String configuredConflict : socket.getAdditionalConflicts()) {
                boolean knownSocket = getSocket(configuredConflict) != null
                        || getSocketByKey(configuredConflict) != null;
                boolean knownLiveEnchant = configuredConflict.contains(":")
                        && getEnchantment(configuredConflict) != null;
                if (!knownSocket && !knownLiveEnchant) {
                    plugin.getLogger().warning("Unknown additional-conflict '" + configuredConflict
                            + "' for managed enchantment '" + socket.getId() + "'; policy was preserved.");
                }
            }
        }
    }

    private void validateProfileOverrides() {
        if (plugin.getGearProfiles() == null) return;
        for (GearProfile profile : plugin.getGearProfiles().all()) {
            for (var entry : profile.enchantmentOverrides().entrySet()) {
                EnchantSocket socket = getSocketByKey(entry.getKey());
                if (socket == null) {
                    plugin.getLogger().warning("Unknown enchantment override '" + entry.getKey()
                            + "' in gear profile '" + profile.id() + "'; it was preserved but is inactive.");
                    continue;
                }
                if (entry.getValue().additionalConflicts() == null) continue;
                for (String conflict : entry.getValue().additionalConflicts()) {
                    boolean known = getSocket(conflict) != null || getSocketByKey(conflict) != null
                            || conflict.contains(":") && getEnchantment(conflict) != null;
                    if (!known) plugin.getLogger().warning("Unknown profile additional-conflict '"
                            + conflict + "' for '" + entry.getKey() + "' in profile '"
                            + profile.id() + "'; it was preserved.");
                }
            }
        }
    }
}

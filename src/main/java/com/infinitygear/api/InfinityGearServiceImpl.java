package com.infinitygear.api;

import com.infinitygear.data.GearData;
import com.infinitygear.data.TrackedKind;
import com.infinitygear.data.TrackedArtifactFactory;
import com.infinitygear.gear.GearInstance;
import com.infinitygear.gear.GearManager;
import com.infinitygear.gear.GearProfile;
import com.infinitygear.gear.GearProfileRegistry;
import com.infinitygear.enchant.ResolvedEnchantmentPolicy;
import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.enchant.EnchantSocket;
import com.infinitypickaxes.core.pickaxe.PickaxeData;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Optional;

public final class InfinityGearServiceImpl implements InfinityGearService {
    private final InfinityPickaxes plugin;
    private final GearManager manager;
    private final GearProfileRegistry profiles;

    public InfinityGearServiceImpl(InfinityPickaxes plugin, GearManager manager, GearProfileRegistry profiles) {
        this.plugin = plugin;
        this.manager = manager;
        this.profiles = profiles;
    }

    public boolean isGear(ItemStack item) { return GearData.isGear(item); }

    public Optional<GearSnapshot> inspect(ItemStack item) {
        return manager.inspect(item, true).map(gear -> new GearSnapshot(gear.uuid(), gear.profileId(), gear.level(),
                gear.xp(), gear.blocksMined(), gear.socketCapacity(), usedSockets(item), quarantined(item),
                item.getEnchantments().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().getKey().toString(), java.util.Map.Entry::getValue))));
    }

    public Optional<GearProfile> resolveProfile(ItemStack item) {
        return manager.inspect(item, true).flatMap(gear -> profiles.find(gear.profileId()));
    }

    public OperationResult<ItemStack> createGear(String profileId, int startingLevel) {
        if (!Bukkit.isPrimaryThread()) return OperationResult.failure(FailureReason.NOT_PRIMARY_THREAD, "api.primary-thread");
        try { return OperationResult.success(manager.create(profileId, startingLevel)); }
        catch (IllegalArgumentException failure) { return OperationResult.failure(FailureReason.UNKNOWN_PROFILE, "api.unknown-profile"); }
        catch (IllegalStateException failure) { return OperationResult.failure(FailureReason.PROVIDER_UNAVAILABLE, "api.default-item-provider"); }
    }

    public OperationResult<Integer> applyEnchantment(ItemStack gearItem, ItemStack book, String enchantmentKey) {
        if (!Bukkit.isPrimaryThread()) return OperationResult.failure(FailureReason.NOT_PRIMARY_THREAD, "api.primary-thread");
        ApplicationValidation validation = validateApplication(gearItem, book, enchantmentKey);
        if (!validation.result().success()) return validation.result();
        GearInstance gear = validation.gear();
        var managed = validation.managed();
        var enchantment = validation.enchantment();
        var decision = validation.decision();
        int current = gearItem.getEnchantmentLevel(enchantment);
        var event = new com.infinitygear.api.events.GearEnchantChangeEvent(null, gearItem, gear.profileId(),
                managed.socket().getKeyString(), current, decision.resultingLevel(),
                com.infinitygear.api.events.GearEnchantChangeEvent.Operation.APPLY);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return OperationResult.failure(FailureReason.CANCELLED, "api.cancelled");
        var meta = gearItem.getItemMeta();
        if (meta == null) return OperationResult.failure(FailureReason.INVALID_ITEM, "api.invalid-item");
        ItemStack gearBefore = gearItem.clone(), bookBefore = book.clone();
        try {
            meta.addEnchant(enchantment, decision.resultingLevel(), true);
            gearItem.setItemMeta(meta);
            GearData.save(gear, false, GearData.LEGACY_PICKAXE_PROFILE.equals(gear.profileId()));
            if (book.getAmount() > 1) book.setAmount(book.getAmount() - 1); else book.setAmount(0);
            return OperationResult.success(decision.resultingLevel());
        } catch (RuntimeException failure) {
            restore(gearItem, gearBefore); restore(book, bookBefore);
            return OperationResult.failure(FailureReason.INVALID_ITEM, "api.atomic-mutation-failed");
        }
    }

    @Override
    public OperationResult<Integer> validateEnchantmentApplication(ItemStack gearItem, ItemStack book,
                                                                    String enchantmentKey) {
        if (!Bukkit.isPrimaryThread()) return OperationResult.failure(FailureReason.NOT_PRIMARY_THREAD, "api.primary-thread");
        return validateApplication(gearItem, book, enchantmentKey).result();
    }

    private ApplicationValidation validateApplication(ItemStack gearItem, ItemStack book, String enchantmentKey) {
        if (com.infinitygear.integration.ArchiveBookIdentity.marked(book)) return invalid(FailureReason.INVALID_BOOK, "api.archive-lifecycle-required");
        GearInstance gear = manager.inspect(gearItem, true).orElse(null);
        if (gear == null) return invalid(FailureReason.NOT_GEAR, "api.not-gear");
        if (!plugin.getDuplicateService().isUsable(gearItem)) {
            return invalid(FailureReason.QUARANTINED, "api.quarantined");
        }
        var found = plugin.getEnchantManager().getManagedBookEnchants(book);
        if (found.size() != 1) return invalid(FailureReason.INVALID_BOOK, "api.single-managed-book");
        var managed = found.getFirst();
        if (enchantmentKey != null && !managed.socket().getKeyString().equalsIgnoreCase(enchantmentKey)) {
            return invalid(FailureReason.INVALID_BOOK, "api.wrong-enchantment");
        }
        var enchantment = plugin.getEnchantManager().getEnchantment(managed.socket().getKeyString());
        if (enchantment == null) return invalid(FailureReason.INVALID_BOOK, "api.unknown-enchantment");
        GearProfile profile = profiles.find(gear.profileId()).orElse(null);
        if (profile == null) return invalid(FailureReason.UNKNOWN_PROFILE, "api.unknown-profile");
        ResolvedEnchantmentPolicy policy = resolve(profile, gear.level(), managed.socket(), 1.0);
        int current = gearItem.getEnchantmentLevel(enchantment);
        int used = usedSockets(gearItem);
        boolean conflict = gearItem.getEnchantments().keySet().stream().anyMatch(existing -> {
            if (existing.equals(enchantment)) return false;
            var existingSocket = plugin.getEnchantManager().getSocketByKey(existing.getKey().toString());
            ResolvedEnchantmentPolicy existingPolicy = existingSocket == null ? null
                    : resolve(profile, gear.level(), existingSocket, 1.0);
            boolean configured = policy.additionallyConflictsWith(existing.getKey().toString())
                    || existingPolicy != null && existingPolicy.additionallyConflictsWith(policy.enchantmentKey());
            return configured || existing.conflictsWith(enchantment) || enchantment.conflictsWith(existing)
                    || plugin.getEnchantManager().getEcoHook().conflictsWith(existing, enchantment);
        });
        var decision = com.infinitygear.enchant.EnchantmentApplicationPolicy.evaluate(
                new com.infinitygear.enchant.EnchantmentApplicationPolicy.Request(1, true,
                        policy.enabled(), policy.unlockedAt(gear.level()), current, managed.level(), used,
                        gear.socketCapacity(), policy.socketCost(), policy.standardMaximum(), policy.absoluteMaximum(),
                        conflict, current > 0 || (enchantment != null && (plugin.getEnchantManager().getEcoHook()
                        .findEcoEnchant(enchantment) != null
                        ? plugin.getEnchantManager().getEcoHook().canApply(gearItem, enchantment)
                        : enchantment.canEnchantItem(gearItem)))));
        if (!decision.allowed()) return invalid(FailureReason.POLICY_REJECTED,
                "enchant.application." + decision.failure().name().toLowerCase(java.util.Locale.ROOT));
        return new ApplicationValidation(OperationResult.success(decision.resultingLevel()), gear, managed,
                enchantment, decision);
    }

    private static ApplicationValidation invalid(FailureReason reason, String key) {
        return new ApplicationValidation(OperationResult.failure(reason, key), null, null, null, null);
    }

    private record ApplicationValidation(OperationResult<Integer> result, GearInstance gear,
                                         com.infinitypickaxes.core.enchant.EnchantManager.ManagedBookEnchant managed,
                                         org.bukkit.enchantments.Enchantment enchantment,
                                         com.infinitygear.enchant.EnchantmentApplicationPolicy.Decision decision) {}

    public int usedSockets(ItemStack gear) {
        if (gear == null || gear.getType().isAir()) return 0;
        if (PickaxeData.isInfinityPickaxe(gear)) {
            var pickaxe = PickaxeData.fromItemStack(gear);
            return pickaxe == null ? 0 : plugin.getEnchantManager().countUsedSockets(pickaxe);
        }
        GearInstance instance = manager.inspect(gear, true).orElse(null);
        GearProfile profile = instance == null ? null : profiles.find(instance.profileId()).orElse(null);
        if (instance == null || profile == null) return 0;
        int used = 0;
        for (var enchantment : gear.getEnchantments().keySet()) {
            EnchantSocket socket = plugin.getEnchantManager().getSocketByKey(enchantment.getKey().toString());
            if (socket != null) used = (int) Math.min(Integer.MAX_VALUE,
                    (long) used + resolve(profile, instance.level(), socket, 1.0).socketCost());
        }
        return used;
    }

    public int socketLimit(ItemStack item) {
        return manager.inspect(item, true).map(GearInstance::socketCapacity).orElse(0);
    }

    public boolean quarantined(ItemStack item) { return !plugin.getDuplicateService().isUsable(item); }

    public OperationResult<ItemStack> createTrackedArtifact(TrackedKind kind, String type) {
        if (!Bukkit.isPrimaryThread()) return OperationResult.failure(FailureReason.NOT_PRIMARY_THREAD, "api.primary-thread");
        if (kind == null || kind == TrackedKind.GEAR) return OperationResult.failure(FailureReason.INVALID_ITEM, "api.invalid-artifact");
        try { return OperationResult.success(new TrackedArtifactFactory(plugin).create(kind, type)); }
        catch (RuntimeException invalid) { return OperationResult.failure(FailureReason.PROVIDER_UNAVAILABLE, "api.artifact-provider"); }
    }

    public Collection<EnchantSocket> eligibleEnchantments(String profileId) {
        Optional<GearProfile> profile = profiles.find(profileId).filter(GearProfile::enabled);
        if (profile.isEmpty()) return java.util.List.of();
        // A profile may span multiple native targets (notably helmet, chestplate,
        // leggings and boots). Report the union instead of testing only the
        // configured creation material; application still validates the live item.
        java.util.List<ItemStack> acceptedProbes = profile.get().acceptedMaterials().stream()
                .map(ItemStack::new).toList();
        java.util.List<ItemStack> probes = acceptedProbes.isEmpty()
                ? java.util.List.of(new ItemStack(profile.get().defaultMaterial())) : acceptedProbes;
        return plugin.getEnchantManager().getAllSockets().stream()
                .filter(socket -> {
                    ResolvedEnchantmentPolicy policy = resolve(profile.get(), 0, socket, 1.0);
                    if (!policy.enabled()) return false;
                    var enchantment = plugin.getEnchantManager().getEnchantment(socket.getKeyString());
                    if (enchantment == null) return false;
                    return probes.stream().anyMatch(probe ->
                            plugin.getEnchantManager().getEcoHook().findEcoEnchant(enchantment) != null
                                    ? plugin.getEnchantManager().getEcoHook().canApply(probe, enchantment)
                                    : enchantment.canEnchantItem(probe));
                }).toList();
    }

    @Override
    public Optional<ResolvedEnchantmentPolicy> resolveEnchantmentPolicy(
            String profileId, String enchantmentKey, int gearLevel) {
        GearProfile profile = profiles.find(profileId).filter(GearProfile::enabled).orElse(null);
        EnchantSocket socket = plugin.getEnchantManager().getSocketByKey(enchantmentKey);
        if (socket == null) socket = plugin.getEnchantManager().getSocket(enchantmentKey);
        return profile == null || socket == null ? Optional.empty()
                : Optional.of(resolve(profile, Math.max(0, gearLevel), socket, 1.0));
    }

    private ResolvedEnchantmentPolicy resolve(GearProfile profile, int gearLevel,
                                               EnchantSocket socket, double globalCostWeight) {
        boolean globallyRemovable = !plugin.getConfigManager().getEnchantsConfig().getBoolean(
                "enchants." + socket.getId() + ".non-removable", false);
        int limitBreakExtra = GearData.LEGACY_PICKAXE_PROFILE.equals(profile.id())
                && plugin.getLimitBreakManager() != null
                ? plugin.getLimitBreakManager().getMaxExtraLevels(gearLevel)
                : plugin.getEnchantManager().getProgressionPolicy().getMaximumLimitBreakExtraLevels();
        return ResolvedEnchantmentPolicy.resolve(profile, socket, gearLevel, limitBreakExtra,
                globallyRemovable, globalCostWeight);
    }

    private static void restore(ItemStack target, ItemStack snapshot) {
        target.setType(snapshot.getType());
        target.setAmount(snapshot.getAmount());
        target.setItemMeta(snapshot.getItemMeta());
    }
}

package com.infinitypickaxes.core.duplicate;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.api.events.PickaxeDuplicateDetectedEvent;
import com.infinitypickaxes.api.events.PickaxeRekeyedEvent;
import com.infinitypickaxes.api.events.PickaxeQuarantinedEvent;
import com.infinitypickaxes.core.pickaxe.InfinityPickaxe;
import com.infinitypickaxes.core.pickaxe.PickaxeData;
import com.infinitygear.data.GearData;
import com.infinitygear.data.TrackedItemData;
import com.infinitygear.data.TrackedKind;
import com.infinitygear.integration.IntegrationTasks;
import com.infinitygear.persistence.MariaQuarantineAuthority;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** @deprecated Use GearDuplicateService; retained as a source/binary compatibility facade. */
@Deprecated
public class PickaxeDuplicateService implements AutoCloseable {

    private final InfinityPickaxes plugin;
    private final DuplicateStore store;
    private final boolean mariaRequired;
    private final Set<UUID> restricted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingRestricted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile MariaQuarantineAuthority mariaAuthority;
    private volatile IntegrationTasks integrationTasks;
    private volatile boolean mariaReady;

    public PickaxeDuplicateService(InfinityPickaxes plugin) throws Exception {
        this.plugin = plugin;
        this.mariaRequired = mariaConfigured(plugin);
        if (mariaRequired) {
            this.store = null;
            plugin.getLogger().warning("MariaDB quarantine authority selected; tracked-item use remains fail-closed until the approved import is verified");
        } else {
            Path database = plugin.getDataFolder().toPath().resolve("duplicates.db");
            this.store = new DuplicateStore(database);
            this.restricted.addAll(store.loadRestrictedUuids());
        }
    }

    PickaxeDuplicateService(InfinityPickaxes plugin, DuplicateStore store) throws SQLException {
        this.plugin = plugin;
        this.mariaRequired = false;
        this.store = store;
        this.restricted.addAll(store.loadRestrictedUuids());
    }

    /** Installs one already-migrated and operator-approved MariaDB authority. */
    public void installMariaAuthority(MariaQuarantineAuthority authority, IntegrationTasks tasks,
                                      Collection<DuplicateRecord> initialRestricted) {
        if (!mariaRequired || store != null || mariaReady) throw new IllegalStateException("Invalid quarantine authority transition");
        this.mariaAuthority = java.util.Objects.requireNonNull(authority);
        this.integrationTasks = java.util.Objects.requireNonNull(tasks);
        restricted.clear();
        initialRestricted.forEach(record -> restricted.add(record.uuid()));
        mariaReady = true;
    }

    public boolean authorityReady() { return !mariaRequired || mariaReady; }

    public boolean isRestricted(UUID uuid) {
        return uuid != null && (restricted.contains(uuid) || pendingRestricted.contains(uuid));
    }

    public boolean isUsable(ItemStack item) {
        TrackedItemData.Identity identity = trackedIdentity(item);
        if (identity != null && !authorityReady()) return false;
        if (identity != null && item.getAmount() != 1) {
            quarantineAsync(identity.uuid(), identity.kind().name(), identity.type(),
                    "Tracked singleton item was stacked", "system:stack-validation", List.of("item-stack"))
                    .exceptionally(failure -> { logMutationFailure("stacked tracked item", failure); return null; });
            return false;
        }
        UUID uuid = identity == null ? null : identity.uuid();
        if (isItemQuarantined(item)) {
            if (uuid != null && !isRestricted(uuid)) {
                String kind = identity == null ? TrackedKind.GEAR.name() : identity.kind().name();
                String type = identity == null ? GearData.LEGACY_PICKAXE_PROFILE : identity.type();
                quarantineAsync(uuid, kind, type, "Recovered quarantine from item metadata",
                        "system:pdc-recovery", List.of("item-pdc"))
                        .exceptionally(failure -> { logMutationFailure("item-local quarantine " + uuid, failure); return null; });
            }
            markRestricted(item);
            return false;
        }
        if (!isRestricted(uuid)) return true;
        markRestricted(item);
        return false;
    }

    public DuplicateScanResult scanOnline(String actor) {
        return scanOnline(actor, List.of());
    }

    public DuplicateScanResult scanOnline(String actor, Collection<PhysicalStorageKey> retainedStorages) {
        DuplicateObservations<ItemStack> sightings = new DuplicateObservations<>();
        Set<PhysicalStorageKey> visitedStorages = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            collectInventory(player.getInventory(), "player:" + player.getName(), sightings);
            collectInventory(player.getEnderChest(), "enderchest:" + player.getName(), sightings);
            Inventory top = player.getOpenInventory().getTopInventory();
            collectPhysicalInventory(top, "open-container:" + player.getName(), visitedStorages, sightings);
            unequipPersistentlyRestrictedArmor(player);
        }
        for (PhysicalStorageKey retained : retainedStorages) {
            if (!visitedStorages.add(retained)) continue;
            retained.resolveInventory().ifPresent(inventory ->
                    collectInventory(inventory, "retained-storage:" + retained.value(), sightings));
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Item entity : world.getEntitiesByClass(Item.class)) {
                collectItem(entity.getItemStack(), "dropped-item:" + entity.getUniqueId(), 0, sightings);
            }
        }

        return quarantineDuplicates(sightings, actor);
    }

    public DuplicateScanResult scanPlayer(Player player, String actor) {
        DuplicateObservations<ItemStack> sightings = new DuplicateObservations<>();
        collectInventory(player.getInventory(), "player:" + player.getName(), sightings);
        collectInventory(player.getEnderChest(), "enderchest:" + player.getName(), sightings);
        unequipPersistentlyRestrictedArmor(player);
        return quarantineDuplicates(sightings, actor);
    }

    public CompletableFuture<DuplicateScanResult> scanOnlineAsync(String actor) {
        return scanOnlineAsync(actor, List.of());
    }

    public CompletableFuture<DuplicateScanResult> scanOnlineAsync(String actor,
                                                                   Collection<PhysicalStorageKey> retainedStorages) {
        if (store != null) return CompletableFuture.completedFuture(scanOnline(actor, retainedStorages));
        if (!authorityReady()) return unavailable();
        DuplicateObservations<ItemStack> sightings = new DuplicateObservations<>();
        Set<PhysicalStorageKey> visitedStorages = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            collectInventory(player.getInventory(), "player:" + player.getName(), sightings);
            collectInventory(player.getEnderChest(), "enderchest:" + player.getName(), sightings);
            collectPhysicalInventory(player.getOpenInventory().getTopInventory(),
                    "open-container:" + player.getName(), visitedStorages, sightings);
            unequipPersistentlyRestrictedArmor(player);
        }
        for (PhysicalStorageKey retained : retainedStorages) {
            if (!visitedStorages.add(retained)) continue;
            retained.resolveInventory().ifPresent(inventory -> collectInventory(inventory,
                    "retained-storage:" + retained.value(), sightings));
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) for (Item entity : world.getEntitiesByClass(Item.class))
            collectItem(entity.getItemStack(), "dropped-item:" + entity.getUniqueId(), 0, sightings);
        return quarantineDuplicatesAsync(sightings, actor);
    }

    public CompletableFuture<DuplicateScanResult> scanPlayerAsync(Player player, String actor) {
        if (store != null) return CompletableFuture.completedFuture(scanPlayer(player, actor));
        if (!authorityReady()) return unavailable();
        DuplicateObservations<ItemStack> sightings = new DuplicateObservations<>();
        collectInventory(player.getInventory(), "player:" + player.getName(), sightings);
        collectInventory(player.getEnderChest(), "enderchest:" + player.getName(), sightings);
        unequipPersistentlyRestrictedArmor(player);
        return quarantineDuplicatesAsync(sightings, actor);
    }

    public void quarantine(UUID uuid, String reason, String actor) throws SQLException {
        requireLegacySynchronousAuthority();
        store.quarantine(uuid, reason, actor, List.of("manual"));
        restricted.add(uuid);
        markVisibleCopies(uuid);
        Bukkit.getPluginManager().callEvent(new PickaxeQuarantinedEvent(
                uuid, DuplicateStatus.QUARANTINED, reason, actor));
    }

    public void revoke(UUID uuid, String reason, String actor) throws SQLException {
        requireLegacySynchronousAuthority();
        store.revoke(uuid, reason, actor, null);
        restricted.add(uuid);
        markVisibleCopies(uuid);
        Bukkit.getPluginManager().callEvent(new PickaxeQuarantinedEvent(
                uuid, DuplicateStatus.REVOKED, reason, actor));
    }

    public UUID rekeyHeld(Player administrator) throws SQLException {
        requireLegacySynchronousAuthority();
        ItemStack held = administrator.getInventory().getItemInMainHand();
        if (plugin.getGearManager() != null) plugin.getGearManager().inspect(held, true);
        TrackedItemData.Identity identity = trackedIdentity(held);
        UUID oldUuid = identity == null ? null : identity.uuid();
        if (oldUuid == null) throw new IllegalArgumentException("Hold one tracked InfinityGear item first.");
        validateRekeyAmount(held.getAmount());

        UUID replacement = UUID.randomUUID();
        store.revoke(oldUuid, "Administrator selected a canonical replacement", administrator.getName(), replacement);
        restricted.add(oldUuid);
        if (PickaxeData.isInfinityPickaxe(held)) {
            PickaxeData.setPickaxeUuid(held, replacement);
            PickaxeData.setQuarantined(held, false);
        }
        if (held.hasItemMeta()) {
            var meta = held.getItemMeta();
            var pdc = meta.getPersistentDataContainer();
            pdc.set(GearData.KEY_UUID, org.bukkit.persistence.PersistentDataType.STRING, replacement.toString());
            pdc.remove(GearData.KEY_QUARANTINED);
            held.setItemMeta(meta);
        }
        markVisibleCopies(oldUuid);
        InfinityPickaxe pickaxe = PickaxeData.fromItemStack(held);
        if (pickaxe != null) plugin.getPickaxeManager().syncPickaxe(pickaxe);
        else if (plugin.getGearManager() != null) plugin.getGearManager().refreshPresentation(held);
        Bukkit.getPluginManager().callEvent(new PickaxeRekeyedEvent(administrator, held, oldUuid, replacement));
        return replacement;
    }

    public Optional<DuplicateRecord> find(UUID uuid) throws SQLException {
        requireLegacySynchronousAuthority();
        return store.find(uuid);
    }

    public List<DuplicateRecord> listRestricted() throws SQLException {
        requireLegacySynchronousAuthority();
        return store.listRestricted();
    }

    public CompletableFuture<Optional<DuplicateRecord>> findAsync(UUID uuid) {
        if (store != null) {
            try { return CompletableFuture.completedFuture(store.find(uuid)); }
            catch (SQLException failure) { return CompletableFuture.failedFuture(failure); }
        }
        if (!authorityReady()) return unavailable();
        return fenceOnFailure(integrationTasks.database(() -> mariaAuthority.find(uuid)));
    }

    public CompletableFuture<List<DuplicateRecord>> listRestrictedAsync() {
        if (store != null) {
            try { return CompletableFuture.completedFuture(store.listRestricted()); }
            catch (SQLException failure) { return CompletableFuture.failedFuture(failure); }
        }
        if (!authorityReady()) return unavailable();
        return fenceOnFailure(integrationTasks.database(mariaAuthority::listRestricted));
    }

    public CompletableFuture<Void> quarantineAsync(UUID uuid, String reason, String actor) {
        return quarantineAsync(uuid, TrackedKind.GEAR.name(), GearData.LEGACY_PICKAXE_PROFILE,
                reason, actor, List.of("manual"));
    }

    public CompletableFuture<Void> revokeAsync(UUID uuid, String reason, String actor) {
        if (store != null) {
            try { revoke(uuid, reason, actor); return CompletableFuture.completedFuture(null); }
            catch (SQLException failure) { return CompletableFuture.failedFuture(failure); }
        }
        if (!authorityReady()) return unavailable();
        Instant observed = Instant.now();
        pendingRestricted.add(uuid);
        return fenceOnFailure(integrationTasks.database(() -> {
            mariaAuthority.revoke(uuid, TrackedKind.GEAR.name(), GearData.LEGACY_PICKAXE_PROFILE,
                    reason, actor, null, observed, List.of());
            return null;
        }).thenCompose(ignored -> integrationTasks.server(() -> {
            restricted.add(uuid);
            pendingRestricted.remove(uuid);
            markVisibleCopies(uuid);
            Bukkit.getPluginManager().callEvent(new PickaxeQuarantinedEvent(
                    uuid, DuplicateStatus.REVOKED, reason, actor));
            return null;
        })));
    }

    /** Persists old-identity revocation before changing the one revalidated held item. */
    public CompletableFuture<UUID> rekeyHeldAsync(Player administrator) {
        if (store != null) {
            try { return CompletableFuture.completedFuture(rekeyHeld(administrator)); }
            catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
        }
        if (!authorityReady()) return unavailable();
        ItemStack held = administrator.getInventory().getItemInMainHand();
        if (plugin.getGearManager() != null) plugin.getGearManager().inspect(held, true);
        TrackedItemData.Identity identity = trackedIdentity(held);
        UUID oldUuid = identity == null ? null : identity.uuid();
        if (oldUuid == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Hold one tracked InfinityGear item first."));
        try { validateRekeyAmount(held.getAmount()); }
        catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
        String kind = identity.kind().name(), type = identity.type();
        UUID replacement = UUID.randomUUID();
        Instant observed = Instant.now();
        String actor = administrator.getName();
        pendingRestricted.add(oldUuid);
        return fenceOnFailure(integrationTasks.database(() -> {
            mariaAuthority.revoke(oldUuid, kind, type, "Administrator selected a canonical replacement",
                    actor, replacement, observed, List.of());
            return null;
        }).thenCompose(ignored -> integrationTasks.server(() -> {
            ItemStack current = administrator.getInventory().getItemInMainHand();
            TrackedItemData.Identity currentIdentity = trackedIdentity(current);
            if (currentIdentity == null || !oldUuid.equals(currentIdentity.uuid()) || current.getAmount() != 1) {
                restricted.add(oldUuid);
                markVisibleCopies(oldUuid);
                throw new IllegalStateException("Held custody changed after durable revocation; old UUID remains revoked and no replacement was written");
            }
            restricted.add(oldUuid);
            pendingRestricted.remove(oldUuid);
            if (PickaxeData.isInfinityPickaxe(current)) {
                PickaxeData.setPickaxeUuid(current, replacement);
                PickaxeData.setQuarantined(current, false);
            }
            if (current.hasItemMeta()) {
                var meta = current.getItemMeta();
                var pdc = meta.getPersistentDataContainer();
                pdc.set(GearData.KEY_UUID, org.bukkit.persistence.PersistentDataType.STRING, replacement.toString());
                pdc.remove(GearData.KEY_QUARANTINED);
                current.setItemMeta(meta);
            }
            markVisibleCopies(oldUuid);
            InfinityPickaxe pickaxe = PickaxeData.fromItemStack(current);
            if (pickaxe != null) plugin.getPickaxeManager().syncPickaxe(pickaxe);
            else if (plugin.getGearManager() != null) plugin.getGearManager().refreshPresentation(current);
            Bukkit.getPluginManager().callEvent(new PickaxeRekeyedEvent(administrator, current, oldUuid, replacement));
            return replacement;
        })));
    }

    public boolean isPhysicalStorageInventory(Inventory inventory) {
        return PhysicalStorageKey.from(inventory).isPresent();
    }

    public boolean containsTrackedItem(Inventory inventory) {
        if (inventory == null) return false;
        for (ItemStack item : inventory.getContents()) {
            if (containsTrackedItem(item, 0)) return true;
        }
        return false;
    }

    /** Legacy name retained for integrations; this now covers every tracked kind and gear profile. */
    public boolean containsInfinityPickaxe(Inventory inventory) {
        return containsTrackedItem(inventory);
    }

    public boolean isTracked(ItemStack item) {
        return trackedIdentity(item) != null;
    }

    static boolean isPhysicalStorageHolder(InventoryHolder holder) {
        return PhysicalStorageKey.from(holder).isPresent();
    }

    static void validateRekeyAmount(int amount) {
        if (amount != 1) {
            throw new IllegalArgumentException("Canonical rekeying requires exactly one unstacked tracked item.");
        }
    }

    private boolean containsTrackedItem(ItemStack item, int depth) {
        if (isEmpty(item)) return false;
        if (trackedIdentity(item) != null || PickaxeData.isInfinityPickaxe(item)) return true;

        if (!(item.getItemMeta() instanceof BlockStateMeta blockMeta)
                || !(blockMeta.getBlockState() instanceof TileStateInventoryHolder container)) {
            return false;
        }
        int maxDepth = Math.max(0, plugin.getConfigManager().getConfig()
                .getInt("duplicate-protection.container-recursion-depth", 3));
        if (depth >= maxDepth) return false;
        for (ItemStack nested : container.getInventory().getContents()) {
            if (containsTrackedItem(nested, depth + 1)) return true;
        }
        return false;
    }

    private void collectPhysicalInventory(Inventory inventory, String label,
                                          Set<PhysicalStorageKey> visitedStorages,
                                          DuplicateObservations<ItemStack> sightings) {
        Optional<PhysicalStorageKey> key = PhysicalStorageKey.from(inventory);
        if (key.isEmpty() || !visitedStorages.add(key.get())) return;
        collectInventory(inventory, label + ":storage=" + key.get().value(), sightings);
    }

    private void collectInventory(Inventory inventory, String label,
                                  DuplicateObservations<ItemStack> sightings) {
        if (inventory == null) return;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            collectItem(item, label + ":slot=" + slot, 0, sightings);
        }
    }

    private void collectItem(ItemStack item, String location, int depth,
                             DuplicateObservations<ItemStack> sightings) {
        if (isEmpty(item)) return;
        TrackedItemData.Identity identity = trackedIdentity(item);
        UUID uuid = identity == null ? null : identity.uuid();
        if (uuid != null) {
            int physicalCopies = Math.max(1, item.getAmount());
            sightings.observe(uuid, item, location, physicalCopies);
            if (isRestricted(uuid)) markRestricted(item);
        }

        if (!(item.getItemMeta() instanceof BlockStateMeta blockMeta)
                || !(blockMeta.getBlockState() instanceof TileStateInventoryHolder container)) {
            return;
        }
        int maxDepth = Math.max(0, plugin.getConfigManager().getConfig()
                .getInt("duplicate-protection.container-recursion-depth", 3));
        if (depth >= maxDepth) return;

        ItemStack[] nested = container.getInventory().getContents();
        for (int slot = 0; slot < nested.length; slot++) {
            collectItem(nested[slot], location + "/container-slot=" + slot, depth + 1, sightings);
        }
    }

    private DuplicateScanResult quarantineDuplicates(DuplicateObservations<ItemStack> sightings, String actor) {
        Set<UUID> detected = new HashSet<>();
        for (Map.Entry<UUID, List<DuplicateObservations.Observation<ItemStack>>> entry
                : sightings.entries().entrySet()) {
            if (entry.getValue().size() < 2) continue;
            UUID uuid = entry.getKey();
            boolean newlyDetected = !isRestricted(uuid);
            List<String> locations = entry.getValue().stream()
                    .map(DuplicateObservations.Observation::location).toList();
            try {
                TrackedItemData.Identity identity = trackedIdentity(entry.getValue().getFirst().value());
                String kind = identity == null ? TrackedKind.GEAR.name() : identity.kind().name();
                String type = identity == null ? GearData.LEGACY_PICKAXE_PROFILE : identity.type();
                store.quarantine(uuid, kind, type,
                        "Multiple physical tracked items observed in one scan", actor, locations);
                restricted.add(uuid);
                entry.getValue().forEach(sighting -> markRestricted(sighting.value()));
                unequipVisibleArmorCopies(uuid);
                detected.add(uuid);
                if (newlyDetected) {
                    Bukkit.getPluginManager().callEvent(new PickaxeDuplicateDetectedEvent(uuid, locations));
                    Bukkit.getPluginManager().callEvent(new PickaxeQuarantinedEvent(
                            uuid, DuplicateStatus.QUARANTINED,
                            "Multiple physical tracked items observed in one scan", actor));
                    notifyVisibleOwners(uuid, type);
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not quarantine duplicate tracked item " + uuid, exception);
            }
        }
        return new DuplicateScanResult(sightings.observedCopies(), detected);
    }

    private CompletableFuture<DuplicateScanResult> quarantineDuplicatesAsync(
            DuplicateObservations<ItemStack> sightings, String actor) {
        Set<UUID> detected = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> writes = new java.util.ArrayList<>();
        for (Map.Entry<UUID, List<DuplicateObservations.Observation<ItemStack>>> entry
                : sightings.entries().entrySet()) {
            if (entry.getValue().size() < 2) continue;
            UUID uuid = entry.getKey();
            boolean newlyDetected = !restricted.contains(uuid);
            List<String> locations = entry.getValue().stream()
                    .map(DuplicateObservations.Observation::location).toList();
            TrackedItemData.Identity identity = trackedIdentity(entry.getValue().getFirst().value());
            String kind = identity == null ? TrackedKind.GEAR.name() : identity.kind().name();
            String type = identity == null ? GearData.LEGACY_PICKAXE_PROFILE : identity.type();
            CompletableFuture<Void> write = quarantineAsync(uuid, kind, type,
                    "Multiple physical tracked items observed in one scan", actor, locations)
                    .thenRun(() -> {
                        entry.getValue().forEach(sighting -> markRestricted(sighting.value()));
                        unequipVisibleArmorCopies(uuid);
                        detected.add(uuid);
                        if (newlyDetected) {
                            Bukkit.getPluginManager().callEvent(new PickaxeDuplicateDetectedEvent(uuid, locations));
                            notifyVisibleOwners(uuid, type);
                        }
                    }).exceptionally(failure -> {
                        logMutationFailure("duplicate tracked item " + uuid, failure);
                        return null;
                    });
            writes.add(write);
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new DuplicateScanResult(sightings.observedCopies(), detected));
    }

    private void markVisibleCopies(UUID uuid) {
        Set<PhysicalStorageKey> visitedStorages = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            markInventoryCopies(player.getInventory(), uuid);
            unequipRestrictedArmor(player, uuid);
            markInventoryCopies(player.getEnderChest(), uuid);
            Inventory top = player.getOpenInventory().getTopInventory();
            PhysicalStorageKey.from(top).filter(visitedStorages::add)
                    .ifPresent(ignored -> markInventoryCopies(top, uuid));
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Item entity : world.getEntitiesByClass(Item.class)) {
                TrackedItemData.Identity identity = trackedIdentity(entity.getItemStack());
                if (identity != null && uuid.equals(identity.uuid())) {
                    markRestricted(entity.getItemStack());
                }
            }
        }
    }

    private void unequipVisibleArmorCopies(UUID uuid) {
        for (Player player : Bukkit.getOnlinePlayers()) unequipRestrictedArmor(player, uuid);
    }

    private void unequipRestrictedArmor(Player player, UUID uuid) {
        unequipRestrictedArmor(player, uuid::equals);
    }

    private void unequipPersistentlyRestrictedArmor(Player player) {
        unequipRestrictedArmor(player, this::isRestricted);
    }

    private void unequipRestrictedArmor(Player player, java.util.function.Predicate<UUID> restrictedUuid) {
        if (player == null) return;
        for (org.bukkit.inventory.EquipmentSlot slot : List.of(
                org.bukkit.inventory.EquipmentSlot.HEAD, org.bukkit.inventory.EquipmentSlot.CHEST,
                org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.EquipmentSlot.FEET)) {
            ItemStack equipped = player.getInventory().getItem(slot);
            TrackedItemData.Identity identity = trackedIdentity(equipped);
            if (identity == null || !restrictedUuid.test(identity.uuid())) continue;
            markRestricted(equipped);
            player.getInventory().setItem(slot, null);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(equipped);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private void markInventoryCopies(Inventory inventory, UUID uuid) {
        for (ItemStack item : inventory.getContents()) {
            TrackedItemData.Identity identity = trackedIdentity(item);
            if (identity != null && uuid.equals(identity.uuid())) markRestricted(item);
        }
    }

    private void markRestricted(ItemStack item) {
        if (PickaxeData.isInfinityPickaxe(item)) PickaxeData.setQuarantined(item, true);
        if (item != null && item.hasItemMeta()) {
            var meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(GearData.KEY_QUARANTINED,
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        InfinityPickaxe pickaxe = PickaxeData.fromItemStack(item);
        if (pickaxe != null && plugin.getPickaxeManager() != null) {
            plugin.getPickaxeManager().syncPickaxe(pickaxe);
        } else if (plugin.getGearManager() != null && GearData.isGear(item)) {
            plugin.getGearManager().refreshPresentation(item);
        }
    }

    private void notifyVisibleOwners(UUID uuid, String profile) {
        if (plugin.getMessageManager() == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean ownsVisibleCopy = containsUuid(player.getInventory(), uuid)
                    || containsUuid(player.getEnderChest(), uuid)
                    || containsUuid(player.getOpenInventory().getTopInventory(), uuid);
            if (ownsVisibleCopy) plugin.getMessageManager().sendMessage(player,
                    "messages.gear-duplicate-detected",
                    "%uuid%", uuid.toString(), "%profile%", profile);
        }
    }

    private boolean containsUuid(Inventory inventory, UUID uuid) {
        if (inventory == null) return false;
        ItemStack[] contents = inventory.getContents();
        if (contents == null) return false;
        for (ItemStack item : contents) {
            TrackedItemData.Identity identity = trackedIdentity(item);
            if (identity != null && uuid.equals(identity.uuid())) return true;
        }
        return false;
    }

    private static boolean isEmpty(ItemStack item) {
        if (item == null || item.getAmount() <= 0) return true;
        Material type = item.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    private static TrackedItemData.Identity trackedIdentity(ItemStack item) {
        TrackedItemData.Identity identity = TrackedItemData.readRaw(item);
        if (identity != null) return identity;
        UUID legacy = PickaxeData.getPickaxeUuid(item);
        return legacy == null ? null : new TrackedItemData.Identity(legacy, TrackedKind.GEAR,
                GearData.LEGACY_PICKAXE_PROFILE, 0, PickaxeData.isQuarantined(item));
    }

    private static boolean isItemQuarantined(ItemStack item) {
        TrackedItemData.Identity identity = trackedIdentity(item);
        return identity != null && identity.quarantined();
    }

    private CompletableFuture<Void> quarantineAsync(UUID uuid, String kind, String type, String reason,
                                                     String actor, List<String> locations) {
        if (store != null) {
            try {
                store.quarantine(uuid, kind, type, reason, actor, locations);
                restricted.add(uuid);
                markVisibleCopies(uuid);
                Bukkit.getPluginManager().callEvent(new PickaxeQuarantinedEvent(
                        uuid, DuplicateStatus.QUARANTINED, reason, actor));
                return CompletableFuture.completedFuture(null);
            } catch (SQLException failure) { return CompletableFuture.failedFuture(failure); }
        }
        if (!authorityReady()) return unavailable();
        Instant observed = Instant.now();
        String operation = "runtime:" + UUID.randomUUID();
        List<MariaQuarantineAuthority.Sighting> sightings = new java.util.ArrayList<>();
        for (int index = 0; index < locations.size(); index++) {
            String location = locations.get(index);
            sightings.add(new MariaQuarantineAuthority.Sighting(
                    sha256(uuid + "\n" + operation + "\n" + index + "\n" + location), observed,
                    location, actor, operation));
        }
        pendingRestricted.add(uuid);
        return fenceOnFailure(integrationTasks.database(() -> {
            mariaAuthority.quarantine(uuid, kind, type, reason, actor, observed, sightings);
            return null;
        }).thenCompose(ignored -> integrationTasks.server(() -> {
            restricted.add(uuid);
            pendingRestricted.remove(uuid);
            markVisibleCopies(uuid);
            Bukkit.getPluginManager().callEvent(new PickaxeQuarantinedEvent(
                    uuid, DuplicateStatus.QUARANTINED, reason, actor));
            return null;
        })));
    }

    private void requireLegacySynchronousAuthority() throws SQLException {
        if (store == null) throw new SQLException("MariaDB quarantine operations are asynchronous; use the async operation path");
    }

    private <T> CompletableFuture<T> unavailable() {
        return CompletableFuture.failedFuture(new IllegalStateException(
                "MariaDB quarantine authority is not ready; tracked-item mutation remains disabled"));
    }

    private <T> CompletableFuture<T> fenceOnFailure(CompletableFuture<T> future) {
        return future.whenComplete((ignored, failure) -> {
            if (failure != null) mariaReady = false;
        });
    }

    private void logMutationFailure(String operation, Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        plugin.getLogger().log(Level.SEVERE, "Could not durably quarantine " + operation
                + " (" + cause.getClass().getSimpleName() + ")");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean mariaConfigured(InfinityPickaxes plugin) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "database.yml");
        if (!file.isFile()) return false;
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        return "mariadb".equalsIgnoreCase(config.getString("quarantine-authority", "sqlite"));
    }

    @Override
    public void close() throws Exception {
        mariaReady = false;
        if (store != null) store.close();
    }
}

package com.infinitygear.integration;

import com.axl.custodian.api.AuthorityHandle;
import com.axl.custodian.api.BridgeHandle;
import com.axl.custodian.api.CustodianApi;
import com.axl.custodian.api.DuplicateAssessment;
import com.axl.custodian.api.PhysicalInstance;
import com.axl.custodian.api.PhysicalPresence;
import com.axl.custodian.api.ProcessEpoch;
import com.axl.custodian.api.RegistrationResult;
import com.axl.custodian.api.ScannerScope;
import com.axl.custodian.api.ScopeContribution;
import com.axl.custodian.api.ShadowContributor;
import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.duplicate.PhysicalStorageKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

/** Settled, read-only comparison scanner. Legacy duplicate enforcement remains authoritative. */
public final class CustodianSettledShadowScanner
        implements Consumer<Collection<PhysicalStorageKey>>, AutoCloseable {
    private static final String AUTHORITY_ID = "infinitygear";

    private final InfinityPickaxes plugin;
    private final CustodianShadowAdapter identities;
    private final ShadowContributor contributor;
    private final BridgeHandle bridge;
    private final Clock clock;
    private final BukkitTask heartbeatTask;
    private boolean closed;

    public static Optional<CustodianSettledShadowScanner> connect(InfinityPickaxes plugin) {
        var services = plugin.getServer().getServicesManager();
        RegisteredServiceProvider<CustodianApi> api = services.getRegistration(CustodianApi.class);
        RegisteredServiceProvider<ShadowContributor> shadow = services.getRegistration(ShadowContributor.class);
        if (api == null || shadow == null) {
            plugin.getLogger().info("Custodian shadow comparison is unavailable; legacy duplicate protection remains authoritative.");
            return Optional.empty();
        }
        String serverId = plugin.getConfigManager().getConfig()
                .getString("custodian-shadow.server-id", "local");
        if (serverId == null || serverId.isBlank()) serverId = "local";
        long heartbeatTicks = Math.max(1L, plugin.getConfigManager().getConfig()
                .getLong("custodian-shadow.heartbeat-ticks", 200L));
        try {
            return Optional.of(new CustodianSettledShadowScanner(plugin, api.getProvider(), shadow.getProvider(),
                    AuthorityHandle.issuedByHost(AUTHORITY_ID), serverId, Clock.systemUTC(), heartbeatTicks));
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Custodian shadow comparison could not start; legacy duplicate protection is unchanged.", failure);
            return Optional.empty();
        }
    }

    CustodianSettledShadowScanner(
            InfinityPickaxes plugin,
            CustodianApi custodian,
            ShadowContributor contributor,
            AuthorityHandle authority,
            String serverId,
            Clock clock,
            long heartbeatTicks) {
        this.plugin = plugin;
        this.identities = new CustodianShadowAdapter(custodian, authority, serverId);
        this.contributor = contributor;
        this.clock = clock;
        this.bridge = contributor.startBridgeEpoch(authority, serverId);
        this.heartbeatTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::heartbeatSafely, heartbeatTicks, heartbeatTicks);
    }

    @Override
    public void accept(Collection<PhysicalStorageKey> retainedStorages) {
        if (closed) return;
        try {
            Set<PhysicalStorageKey> visited = new LinkedHashSet<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                scanInventory(player.getInventory(), new Scope(
                        "player:" + player.getUniqueId() + ":inventory",
                        "player:" + player.getUniqueId()));
                scanInventory(player.getEnderChest(), new Scope(
                        "player:" + player.getUniqueId() + ":ender",
                        "player:" + player.getUniqueId()));
                scanPhysical(player.getOpenInventory().getTopInventory(), visited);
            }
            for (PhysicalStorageKey retained : retainedStorages) {
                retained.resolveInventory().ifPresent(inventory -> scanPhysical(inventory, visited));
            }
            for (World world : plugin.getServer().getWorlds()) {
                for (Item item : world.getEntitiesByClass(Item.class)) scanDrop(item);
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Custodian settled shadow snapshot failed; legacy duplicate protection is unchanged.", failure);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        heartbeatTask.cancel();
    }

    private void scanPhysical(Inventory inventory, Set<PhysicalStorageKey> visited) {
        Optional<PhysicalStorageKey> key = PhysicalStorageKey.from(inventory);
        if (key.isEmpty() || !visited.add(key.get())) return;
        Scope scope = physicalScope(inventory);
        if (scope == null) return;
        scanInventory(inventory, scope);
    }

    private void scanInventory(Inventory inventory, Scope scope) {
        if (inventory == null) return;
        Map<UUID, List<PhysicalPresence>> byIdentity = new LinkedHashMap<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            UUID identity = adopt(contents[slot]).orElse(null);
            if (identity == null) continue;
            byIdentity.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(presence(
                    identity, scope.id() + ":slot:" + slot, scope.location() + "/slot/" + slot));
        }
        byIdentity.forEach((identity, presences) -> submit(identity, scope.id(), presences));
    }

    private void scanDrop(Item item) {
        UUID identity = adopt(item.getItemStack()).orElse(null);
        if (identity == null) return;
        String scope = "drop:" + item.getUniqueId();
        submit(identity, scope, List.of(presence(identity, scope + ":item", location(item.getLocation()))));
    }

    private Optional<UUID> adopt(ItemStack item) {
        return identities.adopt(item, 0)
                .filter(result -> result.status() != RegistrationResult.Status.CONFLICT)
                .map(result -> result.identity().identity());
    }

    private PhysicalPresence presence(UUID identity, String instance, String location) {
        Instant now = clock.instant();
        ProcessEpoch opaqueEpoch = new ProcessEpoch(bridge.epochId(), "opaque", now, now);
        return new PhysicalPresence(identity, opaqueEpoch, new PhysicalInstance(instance), location, now);
    }

    private void submit(UUID identity, String scope, List<PhysicalPresence> presences) {
        try {
            DuplicateAssessment assessment = contributor.contribute(
                    bridge, new ScopeContribution(new ScannerScope(scope), presences));
            plugin.getLogger().info("Custodian shadow assessment identity=" + identity
                    + " scope=" + scope + " status=" + assessment.status()
                    + " active-presences=" + assessment.activePresences().size());
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Custodian shadow contribution failed for identity " + identity
                            + "; legacy duplicate protection is unchanged.", failure);
        }
    }

    private void heartbeatSafely() {
        if (closed) return;
        try {
            contributor.heartbeat(bridge);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Custodian shadow heartbeat failed; legacy duplicate protection is unchanged.", failure);
        }
    }

    private static Scope physicalScope(Inventory inventory) {
        if (inventory == null) return null;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest chest) {
            String left = blockLocation(chest.getLeftSide());
            String right = blockLocation(chest.getRightSide());
            if (left == null || right == null) return null;
            String first = left.compareTo(right) <= 0 ? left : right;
            String second = left.compareTo(right) <= 0 ? right : left;
            return new Scope("double-block:" + first + ":" + second, first + ":" + second);
        }
        if (holder instanceof Entity entity && supportedEntity(entity)) {
            return new Scope("entity:" + entity.getUniqueId(), location(entity.getLocation()));
        }
        if (!nativeBlockType(inventory.getType()) || inventory.getLocation() == null) return null;
        String location = location(inventory.getLocation());
        return new Scope("block:" + location, location);
    }

    private static boolean supportedEntity(Entity entity) {
        return entity instanceof StorageMinecart || entity instanceof HopperMinecart || entity instanceof ChestedHorse;
    }

    private static boolean nativeBlockType(InventoryType type) {
        return type == InventoryType.CHEST || type == InventoryType.DISPENSER || type == InventoryType.DROPPER
                || type == InventoryType.FURNACE || type == InventoryType.BREWING || type == InventoryType.HOPPER
                || type == InventoryType.SHULKER_BOX || type == InventoryType.BARREL
                || type == InventoryType.BLAST_FURNACE || type == InventoryType.SMOKER;
    }

    private static String blockLocation(InventoryHolder holder) {
        return holder == null || holder.getInventory().getLocation() == null
                ? null : location(holder.getInventory().getLocation());
    }

    private static String location(Location location) {
        return location.getWorld() == null ? "unknown" : location.getWorld().getUID() + ":"
                + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private record Scope(String id, String location) { }
}

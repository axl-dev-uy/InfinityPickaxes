package com.infinitypickaxes.listeners;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.duplicate.PhysicalStorageKey;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collection;
import java.util.function.Consumer;

public final class DuplicateDetectionListener implements Listener {
    private final InfinityPickaxes plugin;
    private final Consumer<Collection<PhysicalStorageKey>> shadowScan;
    private final ScanDebouncer debouncer = new ScanDebouncer();
    private final Set<PhysicalStorageKey> pendingStorages = new LinkedHashSet<>();
    private BukkitTask periodicScan;
    private BukkitTask pendingScan;

    public DuplicateDetectionListener(InfinityPickaxes plugin) {
        this(plugin, null);
    }

    public DuplicateDetectionListener(
            InfinityPickaxes plugin, Consumer<Collection<PhysicalStorageKey>> shadowScan) {
        this.plugin = plugin;
        this.shadowScan = shadowScan;
        start();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleScan("automatic:join:" + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (plugin.getDuplicateService().isPhysicalStorageInventory(event.getInventory())
                && plugin.getDuplicateService().containsTrackedItem(event.getInventory())) {
            PhysicalStorageKey.from(event.getInventory())
                    .ifPresent(pendingStorages::add);
            scheduleScan("automatic:storage-open:" + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (plugin.getDuplicateService().isPhysicalStorageInventory(event.getInventory())
                && plugin.getDuplicateService().containsTrackedItem(event.getInventory())) {
            PhysicalStorageKey.from(event.getInventory()).ifPresent(pendingStorages::add);
            scheduleScan("automatic:storage-close:" + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player player
                && plugin.getDuplicateService().isTracked(event.getItem().getItemStack())) {
            scheduleScan("automatic:pickup:" + player.getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.getDuplicateService().isTracked(event.getItemDrop().getItemStack())) {
            scheduleScan("automatic:drop:" + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (plugin.getDuplicateService().isTracked(event.getCurrentItem())
                || plugin.getDuplicateService().isTracked(event.getCursor())) {
            scheduleScan("automatic:creative:" + event.getWhoClicked().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (plugin.getDuplicateService().isTracked(event.getCurrentItem())
                || plugin.getDuplicateService().isTracked(event.getCursor())) {
            scheduleScan("automatic:inventory-click:" + event.getWhoClicked().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (plugin.getDuplicateService().isTracked(event.getOldCursor())) {
            scheduleScan("automatic:inventory-drag:" + event.getWhoClicked().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (plugin.getDuplicateService().isTracked(event.getItem())) {
            scheduleScan("automatic:interact:" + event.getPlayer().getName());
        }
    }

    private void scheduleScan(String actor) {
        if (!debouncer.request(actor)) return;
        long delay = Math.max(1L, plugin.getConfigManager().getConfig()
                .getLong("duplicate-protection.debounce-ticks", 10L));
        pendingScan = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            String scanActor = debouncer.consume();
            var retainedStorages = new ArrayList<>(pendingStorages);
            pendingStorages.clear();
            pendingScan = null;
            plugin.getDuplicateService().scanOnlineAsync(scanActor, retainedStorages);
            if (shadowScan != null) shadowScan.accept(retainedStorages);
        }, delay);
    }

    public void stop() {
        if (periodicScan != null) periodicScan.cancel();
        if (pendingScan != null) pendingScan.cancel();
        periodicScan = null;
        pendingScan = null;
        debouncer.clear();
        pendingStorages.clear();
    }

    public void reload() {
        stop();
        start();
    }

    private void start() {
        long interval = Math.max(200L, plugin.getConfigManager().getConfig()
                .getLong("duplicate-protection.scan-interval-ticks", 1200L));
        periodicScan = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> plugin.getDuplicateService().scanOnlineAsync("automatic:periodic"), interval, interval);
    }
}

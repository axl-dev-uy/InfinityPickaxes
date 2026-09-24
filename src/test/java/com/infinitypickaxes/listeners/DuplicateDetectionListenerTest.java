package com.infinitypickaxes.listeners;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.config.ConfigManager;
import com.infinitypickaxes.core.duplicate.DuplicateScanResult;
import com.infinitypickaxes.core.duplicate.PhysicalStorageKey;
import com.infinitypickaxes.core.duplicate.PickaxeDuplicateService;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class DuplicateDetectionListenerTest {

    @Test
    void cursorClickAndDragOnlyScheduleDebouncedScansWhichRemainSlotBased() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration config = mock(FileConfiguration.class);
        PickaxeDuplicateService duplicateService = mock(PickaxeDuplicateService.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask periodicTask = mock(BukkitTask.class);
        BukkitTask delayedTask = mock(BukkitTask.class);
        Player player = mock(Player.class);
        ItemStack cursorGear = mock(ItemStack.class);
        when(player.getName()).thenReturn("cursor");
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getConfig()).thenReturn(config);
        when(plugin.getDuplicateService()).thenReturn(duplicateService);
        when(config.getLong("duplicate-protection.scan-interval-ticks", 1200L)).thenReturn(1200L);
        when(config.getLong("duplicate-protection.debounce-ticks", 10L)).thenReturn(10L);
        when(duplicateService.isTracked(cursorGear)).thenReturn(true);
        when(duplicateService.scanOnlineAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(
                new DuplicateScanResult(0, Map.of(), Set.of())));
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getCursor()).thenReturn(cursorGear);
        when(click.getWhoClicked()).thenReturn(player);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getOldCursor()).thenReturn(cursorGear);
        when(drag.getWhoClicked()).thenReturn(player);
        ArgumentCaptor<Runnable> settled = ArgumentCaptor.forClass(Runnable.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1200L), eq(1200L)))
                    .thenReturn(periodicTask);
            when(scheduler.runTaskLater(eq(plugin), settled.capture(), eq(10L))).thenReturn(delayedTask);
            DuplicateDetectionListener listener = new DuplicateDetectionListener(plugin);

            listener.onInventoryClick(click);
            listener.onInventoryDrag(drag);
            settled.getValue().run();

            verify(duplicateService).scanOnlineAsync("automatic:inventory-drag:cursor", java.util.List.of());
            verify(scheduler, times(1)).runTaskLater(eq(plugin), any(Runnable.class), eq(10L));
            listener.stop();
        }
    }

    @Test
    void closingStorageAfterInsertionRetainsKeyAndRequestsScan() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration config = mock(FileConfiguration.class);
        PickaxeDuplicateService duplicateService = mock(PickaxeDuplicateService.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask periodicTask = mock(BukkitTask.class);
        BukkitTask delayedTask = mock(BukkitTask.class);
        @SuppressWarnings("unchecked")
        BiConsumer<Collection<PhysicalStorageKey>, CompletionStage<DuplicateScanResult>> shadowScan =
                mock(BiConsumer.class);
        CompletableFuture<DuplicateScanResult> legacyResult = CompletableFuture.completedFuture(
                new DuplicateScanResult(0, Map.of(), Set.of()));
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getConfig()).thenReturn(config);
        when(plugin.getDuplicateService()).thenReturn(duplicateService);
        when(duplicateService.scanOnlineAsync(any(), any())).thenReturn(legacyResult);
        when(config.getLong("duplicate-protection.scan-interval-ticks", 1200L)).thenReturn(1200L);
        when(config.getLong("duplicate-protection.debounce-ticks", 10L)).thenReturn(10L);

        UUID worldUuid = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldUuid);
        TileStateInventoryHolder holder = mock(TileStateInventoryHolder.class);
        when(holder.getWorld()).thenReturn(world);
        when(holder.getLocation()).thenReturn(new Location(world, 8, 72, -3));
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder);
        when(duplicateService.isPhysicalStorageInventory(inventory)).thenReturn(true);
        when(duplicateService.containsTrackedItem(inventory)).thenReturn(true);

        HumanEntity player = mock(HumanEntity.class);
        when(player.getName()).thenReturn("builder");
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(view.getPlayer()).thenReturn(player);
        InventoryCloseEvent event = new InventoryCloseEvent(view);

        ArgumentCaptor<Runnable> delayedScan = ArgumentCaptor.forClass(Runnable.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1200L), eq(1200L)))
                    .thenReturn(periodicTask);
            when(scheduler.runTaskLater(eq(plugin), delayedScan.capture(), eq(10L)))
                    .thenReturn(delayedTask);

            DuplicateDetectionListener listener = new DuplicateDetectionListener(plugin, shadowScan);
            listener.onInventoryClose(event);
            listener.onInventoryClose(event);
            delayedScan.getValue().run();

            ArgumentCaptor<Collection<PhysicalStorageKey>> retained = ArgumentCaptor.forClass(Collection.class);
            verify(duplicateService).scanOnlineAsync(eq("automatic:storage-close:builder"), retained.capture());
            assertEquals(1, retained.getValue().size());
            assertTrue(retained.getValue().contains(new PhysicalStorageKey(
                    "block:" + worldUuid + ":8:72:-3")));
            var order = inOrder(duplicateService, shadowScan);
            order.verify(duplicateService).scanOnlineAsync(eq("automatic:storage-close:builder"), any());
            order.verify(shadowScan).accept(any(), same(legacyResult));
            verify(scheduler, times(1)).runTaskLater(eq(plugin), any(Runnable.class), eq(10L));
            listener.stop();
        }
    }

    @Test
    void absentCustodianLeavesLegacyDebouncedScanUnchanged() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration config = mock(FileConfiguration.class);
        PickaxeDuplicateService duplicateService = mock(PickaxeDuplicateService.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("alice");
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getConfig()).thenReturn(config);
        when(plugin.getDuplicateService()).thenReturn(duplicateService);
        when(config.getLong("duplicate-protection.scan-interval-ticks", 1200L)).thenReturn(1200L);
        when(config.getLong("duplicate-protection.debounce-ticks", 10L)).thenReturn(10L);
        ArgumentCaptor<Runnable> settled = ArgumentCaptor.forClass(Runnable.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1200L), eq(1200L)))
                    .thenReturn(task);
            when(scheduler.runTaskLater(eq(plugin), settled.capture(), eq(10L))).thenReturn(task);
            DuplicateDetectionListener listener = new DuplicateDetectionListener(plugin);

            listener.onJoin(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.text("joined")));
            settled.getValue().run();

            verify(duplicateService).scanOnlineAsync("automatic:join:alice", java.util.List.of());
            listener.stop();
        }
    }
}

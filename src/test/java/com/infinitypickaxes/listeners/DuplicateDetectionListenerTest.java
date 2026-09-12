package com.infinitypickaxes.listeners;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.config.ConfigManager;
import com.infinitypickaxes.core.duplicate.PhysicalStorageKey;
import com.infinitypickaxes.core.duplicate.PickaxeDuplicateService;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Collection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuplicateDetectionListenerTest {

    @Test
    void closingStorageAfterInsertionRetainsKeyAndRequestsScan() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration config = mock(FileConfiguration.class);
        PickaxeDuplicateService duplicateService = mock(PickaxeDuplicateService.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask periodicTask = mock(BukkitTask.class);
        BukkitTask delayedTask = mock(BukkitTask.class);
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getConfig()).thenReturn(config);
        when(plugin.getDuplicateService()).thenReturn(duplicateService);
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

            DuplicateDetectionListener listener = new DuplicateDetectionListener(plugin);
            listener.onInventoryClose(event);
            delayedScan.getValue().run();

            ArgumentCaptor<Collection<PhysicalStorageKey>> retained = ArgumentCaptor.forClass(Collection.class);
            verify(duplicateService).scanOnlineAsync(eq("automatic:storage-close:builder"), retained.capture());
            assertEquals(1, retained.getValue().size());
            assertTrue(retained.getValue().contains(new PhysicalStorageKey(
                    "block:" + worldUuid + ":8:72:-3")));
            listener.stop();
        }
    }
}

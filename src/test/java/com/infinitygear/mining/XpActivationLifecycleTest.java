package com.infinitygear.mining;

import com.infinitygear.integration.IntegrationTasks;
import com.infinitygear.persistence.MariaMiningXpLedger;
import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class XpActivationLifecycleTest {
    @Test void loginInventoryLoadAndRestartScheduleReceiptInspectionWithoutAutomaticAdoption() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        IntegrationTasks tasks = mock(IntegrationTasks.class);
        MariaMiningXpLedger ledger = mock(MariaMiningXpLedger.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        org.bukkit.inventory.PlayerInventory personal = mock(org.bukkit.inventory.PlayerInventory.class);
        when(personal.getContents()).thenReturn(new ItemStack[0]);
        Inventory ender = emptyInventory(); Inventory opened = emptyInventory();
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(personal);
        when(player.getEnderChest()).thenReturn(ender);
        PlayerJoinEvent join = mock(PlayerJoinEvent.class); when(join.getPlayer()).thenReturn(player);
        InventoryOpenEvent open = mock(InventoryOpenEvent.class); when(open.getInventory()).thenReturn(opened);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run(); return null;
            });
            var service = new XpActivationService(plugin, tasks, ledger);
            service.onJoin(join);
            service.onOpen(open);
            service.recoverLoadedOnStart();
        }

        verify(scheduler, times(3)).runTask(eq(plugin), any(Runnable.class));
        verifyNoInteractions(ledger);
        verifyNoInteractions(tasks);
    }

    private static Inventory emptyInventory() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[0]);
        return inventory;
    }
}

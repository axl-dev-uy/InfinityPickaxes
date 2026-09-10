package com.infinitygear.integration;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/** Prevents operation-scoped custody evidence from being moved or used between durable phases. */
public final class BookLifecycleCustodyListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        int hotbar = event.getHotbarButton();
        if (BookLifecycleItems.hasCustodyMarker(event.getCurrentItem())
                || BookLifecycleItems.hasCustodyMarker(event.getCursor())
                || event.getClick().isKeyboardClick() && hotbar >= 0
                && BookLifecycleItems.hasCustodyMarker(event.getWhoClicked().getInventory().getItem(hotbar))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (BookLifecycleItems.hasCustodyMarker(event.getOldCursor())
                || BookLifecycleItems.hasCustodyMarker(event.getCursor())
                || event.getRawSlots().stream().anyMatch(slot ->
                BookLifecycleItems.hasCustodyMarker(event.getView().getItem(slot)))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (BookLifecycleItems.hasCustodyMarker(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (BookLifecycleItems.hasCustodyMarker(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (BookLifecycleItems.hasCustodyMarker(event.getMainHandItem())
                || BookLifecycleItems.hasCustodyMarker(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (BookLifecycleItems.hasCustodyMarker(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (BookLifecycleItems.hasCustodyMarker(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (BookLifecycleItems.hasCustodyMarker(
                event.getPlayer().getInventory().getItemInMainHand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(PlayerItemDamageEvent event) {
        if (BookLifecycleItems.hasCustodyMarker(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        boolean custody = Arrays.stream(event.getPlayer().getInventory().getContents())
                .anyMatch(BookLifecycleItems::hasCustodyMarker);
        if (!custody) return;
        // A per-item keep-inventory facility does not exist. Preserving the complete inventory is
        // the only non-destructive choice that keeps exact operation slots restart-recoverable.
        event.setKeepInventory(true);
        event.getDrops().clear();
    }
}

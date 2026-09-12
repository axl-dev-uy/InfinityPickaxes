package com.infinitygear.integration;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.mockito.Mockito.*;

class BookLifecycleCustodyListenerTest {
    @Test void custodyItemCannotMoveThroughInventoryClick() {
        ItemStack item = mock(ItemStack.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getCurrentItem()).thenReturn(item);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        try (var markers = mockStatic(BookLifecycleItems.class)) {
            markers.when(() -> BookLifecycleItems.hasCustodyMarker(item)).thenReturn(true);
            new BookLifecycleCustodyListener().onClick(event);
        }
        verify(event).setCancelled(true);
    }

    @Test void deathPreservesExactSlotsWhileCustodyIsDurable() {
        ItemStack marked = mock(ItemStack.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[]{marked});
        Player player = mock(Player.class); when(player.getInventory()).thenReturn(inventory);
        @SuppressWarnings("unchecked")
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getPlayer()).thenReturn(player);
        var drops = new ArrayList<ItemStack>(); drops.add(mock(ItemStack.class));
        when(event.getDrops()).thenReturn(drops);
        try (var markers = mockStatic(BookLifecycleItems.class)) {
            markers.when(() -> BookLifecycleItems.hasCustodyMarker(marked)).thenReturn(true);
            new BookLifecycleCustodyListener().onDeath(event);
        }
        verify(event).setKeepInventory(true);
        org.junit.jupiter.api.Assertions.assertTrue(drops.isEmpty());
    }
}

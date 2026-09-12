package com.infinitygear.integration;

import com.infinitygear.enchant.*;
import com.infinitygear.api.InfinityGearServiceImpl;
import com.infinitygear.gear.*;
import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

class TrackedBookGuardTest {
    @Test void malformedOrTrackedProvenanceCannotBeStrippedByLegacyOperations() {
        var item = mock(ItemStack.class); var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
        when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc); when(pdc.has(ArchiveBookIdentity.ID)).thenReturn(true);
        var enchants = mock(EnchantManager.class);
        doCallRealMethod().when(enchants).handleSocketUpgrade(null, null, null, item);
        assertFalse(new FusionBookService(enchants).pair(item, item).allowed());
        assertThrows(IllegalArgumentException.class, () -> new EnchantmentItemTransforms().remove(item, null, true));
        assertFalse(enchants.handleSocketUpgrade(null, null, null, item));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var plugin = mock(InfinityPickaxes.class);
            var service = new InfinityGearServiceImpl(plugin, mock(GearManager.class), mock(GearProfileRegistry.class));
            assertFalse(service.validateEnchantmentApplication(null, item, "minecraft:fortune").success());
            verifyNoInteractions(plugin);
        }
        verify(item, never()).setAmount(anyInt()); verify(item, never()).setItemMeta(any());
    }

    @Test void lifecycleCustodyRejectsCompetingGearAndXpWriters() {
        var item = mock(ItemStack.class); var meta = mock(ItemMeta.class);
        var pdc = mock(PersistentDataContainer.class);
        when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(BookLifecycleItems.OPERATION)).thenReturn(true);
        var gear = new GearInstance(item, UUID.randomUUID(), "infinitygear:pickaxe", 1, 2, 3, 4);
        assertThrows(IllegalStateException.class, () -> com.infinitygear.data.GearData.save(gear, false, true));
        var receipt = mock(com.infinitygear.mining.XpProjectionReceipt.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertEquals(com.infinitygear.mining.MiningXpItemProjection.Result.CONFLICT,
                    new com.infinitygear.mining.MiningXpItemProjection().apply(item, receipt));
        }
        verify(item, never()).setItemMeta(any());
    }
}

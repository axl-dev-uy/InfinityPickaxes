package com.infinitypickaxes.listeners;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.config.*;
import com.infinitypickaxes.core.pickaxe.*;
import com.infinitypickaxes.core.level.LevelManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class MiningXpListenerTest {
    @Test void ownedReplacementNeverReachesLegacyXpEvenWithoutManagedTool() {
        var plugin = mock(InfinityPickaxes.class); var server = mock(Server.class); var services = mock(org.bukkit.plugin.ServicesManager.class);
        when(plugin.getServer()).thenReturn(server); when(server.getServicesManager()).thenReturn(services);
        var owner = mock(com.infinitygear.api.v1.MiningEventOwner.class);
        var registration = new org.bukkit.plugin.RegisteredServiceProvider<>(com.infinitygear.api.v1.MiningEventOwner.class, owner, org.bukkit.plugin.ServicePriority.Normal, plugin);
        when(services.getRegistrations(com.infinitygear.api.v1.MiningEventOwner.class)).thenReturn(java.util.List.of(registration));
        var block = mock(Block.class); when(block.getType()).thenReturn(mock(Material.class));
        var event = new BlockBreakEvent(block, mock(Player.class)); when(owner.owns(event)).thenReturn(true);
        var listener = new BlockBreakListener(plugin, null); listener.capture(event); listener.onBlockBreak(event);
        verify(plugin, never()).getPickaxeManager(); verify(plugin, never()).getLevelManager();
    }
    @Test void veinOriginalNestedThenOuterAwardsOnceAndNeverForAir() {
        var plugin = mock(InfinityPickaxes.class); var config = mock(ConfigManager.class);
        when(plugin.getConfigManager()).thenReturn(config);
        when(config.getConfig()).thenReturn(new YamlConfiguration());
        var blocks = new YamlConfiguration(); blocks.set("default-xp", 1.0); blocks.set("blocks.DIAMOND_ORE", 7.0);
        when(config.getBlocksConfig()).thenReturn(blocks);
        var player = mock(Player.class); var inventory = mock(PlayerInventory.class); var tool = mock(ItemStack.class);
        when(player.getInventory()).thenReturn(inventory); when(inventory.getItemInMainHand()).thenReturn(tool);
        when(player.hasPermission("infinitypickaxes.use")).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        var manager = mock(PickaxeManager.class); var pickaxe = mock(InfinityPickaxe.class);
        when(plugin.getPickaxeManager()).thenReturn(manager); when(manager.getOrCreatePickaxe(tool, player)).thenReturn(pickaxe);
        var level = mock(LevelManager.class); when(plugin.getLevelManager()).thenReturn(level);
        when(plugin.getMessageManager()).thenReturn(mock(MessageManager.class));
        var placed = mock(BlockPlaceListener.class); var listener = new BlockBreakListener(plugin, placed);
        var ore = mock(Material.class); when(ore.name()).thenReturn("DIAMOND_ORE");
        var air = mock(Material.class); when(air.isAir()).thenReturn(true);
        var block = mock(Block.class); when(block.getType()).thenReturn(ore);
        var outer = new BlockBreakEvent(block, player); var nested = new BlockBreakEvent(block, player);
        listener.capture(outer); listener.capture(nested); listener.onBlockBreak(nested);
        when(block.getType()).thenReturn(air); listener.onBlockBreak(outer);
        verify(level, times(1)).addXp(pickaxe, 7.0, player); verify(pickaxe, times(1)).incrementBlocksMined();
        listener.onBlockBreak(nested); verifyNoMoreInteractions(level);
    }
    @Test void placedSnapshotSurvivesLaterMarkerChanges() {
        var plugin = mock(InfinityPickaxes.class); var placed = mock(BlockPlaceListener.class);
        var block = mock(Block.class); var player = mock(Player.class);
        when(block.getType()).thenReturn(mock(Material.class)); when(placed.isPlacedByPlayer(null)).thenReturn(true);
        var listener = new BlockBreakListener(plugin, placed); var event = new BlockBreakEvent(block, player);
        listener.capture(event); when(placed.isPlacedByPlayer(null)).thenReturn(false); listener.onBlockBreak(event);
        verifyNoInteractions(plugin);
    }
}

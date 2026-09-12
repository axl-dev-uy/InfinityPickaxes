package com.infinitypickaxes.listeners;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.pickaxe.InfinityPickaxe;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

public class BlockBreakListener implements Listener {

    private final InfinityPickaxes plugin;
    private final BlockPlaceListener placeListener;
    private final java.util.Map<BlockBreakEvent, Snapshot> attempts = new java.util.WeakHashMap<>();
    private record Snapshot(org.bukkit.Material material, boolean placed) {}

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void capture(BlockBreakEvent event) {
        attempts.put(event, new Snapshot(event.getBlock().getType(),
                placeListener != null && placeListener.isPlacedByPlayer(event.getBlock().getLocation())));
    }

    public BlockBreakListener(InfinityPickaxes plugin, BlockPlaceListener placeListener) {
        this.plugin = plugin;
        this.placeListener = placeListener;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Snapshot snapshot = attempts.remove(event);
        // Nested Vein breaks may have removed the original before the outer MONITOR callback.
        // This is a legacy XP safeguard, NOT an authoritative successful-break/generation notification.
        if (snapshot == null || snapshot.material().isAir() || event.getBlock().getType().isAir() || snapshot.placed()) return;
        var server = plugin.getServer();
        if (server != null) {
            for (var registration : server.getServicesManager().getRegistrations(com.infinitygear.api.v1.MiningEventOwner.class)) {
                try { if (registration.getProvider().owns(event)) return; }
                catch (RuntimeException unavailable) { return; } // No raw-event fallback when ownership cannot be checked.
            }
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("infinitypickaxes.use")) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        // An opted-in DB account must never receive a second award through legacy event acceptance.
        if (com.infinitygear.mining.MiningXpItemProjection.isManaged(held)) return;

        InfinityPickaxe pickaxe = plugin.getPickaxeManager().getOrCreatePickaxe(held, player);
        if (pickaxe == null) return;

        FileConfiguration config = plugin.getConfigManager().getConfig();

        // 1. Creative check
        if (player.getGameMode() == GameMode.CREATIVE && config.getBoolean("anti-exploit.ignore-creative", true)) {
            return;
        }

        Block block = event.getBlock();

        // 2. Anti-exploit placed block check
        if (placeListener != null && placeListener.isPlacedByPlayer(block.getLocation())) {
            return;
        }

        // 3. Determine XP reward from blocks.yml
        FileConfiguration blocksConfig = plugin.getConfigManager().getBlocksConfig();
        double defaultXp = blocksConfig.getDouble("default-xp", 1.0);
        String matName = snapshot.material().name();
        double xp = blocksConfig.getDouble("blocks." + matName, defaultXp);

        // 4. Add XP & update pickaxe progression
        if (!Double.isFinite(xp) || xp <= 0) return;
        pickaxe.incrementBlocksMined();
        plugin.getLevelManager().addXp(pickaxe, xp, player);

        // 5. Send real-time Action Bar with progress & XP
        plugin.getMessageManager().sendMiningActionbar(player, pickaxe, xp);
    }
}

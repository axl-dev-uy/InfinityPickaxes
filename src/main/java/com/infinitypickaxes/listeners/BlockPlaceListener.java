package com.infinitypickaxes.listeners;

import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.Location;
import org.bukkit.event.Listener;

public class BlockPlaceListener implements Listener {

    /** eco owns persistent placement, piston movement and cleanup; no competing LRU remains. */
    public BlockPlaceListener(InfinityPickaxes plugin) {}

    public boolean isPlacedByPlayer(Location location) {
        return com.willfp.eco.util.BlockUtils.isPlayerPlaced(location.getBlock());
    }
}

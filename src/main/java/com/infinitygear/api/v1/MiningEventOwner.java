package com.infinitygear.api.v1;

import org.bukkit.event.block.BlockBreakEvent;

/** Server-thread query. Owned events must bypass legacy pre-removal XP, even when credit is unavailable. */
public interface MiningEventOwner {
    boolean owns(BlockBreakEvent event);
    String unavailableReason();
}

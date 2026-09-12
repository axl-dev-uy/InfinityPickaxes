package com.infinitypickaxes.listeners;

import com.willfp.eco.util.BlockUtils;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PlacementAuthorityTest {
    @Test void repeatedQueriesAndAdapterRestartDoNotConsumeOrEvictProviderState() {
        try (var eco = mockStatic(BlockUtils.class)) {
            var block = mock(Block.class); var location = mock(Location.class); when(location.getBlock()).thenReturn(block);
            eco.when(() -> BlockUtils.isPlayerPlaced(block)).thenReturn(true);
            var first = new BlockPlaceListener(null);
            assertTrue(first.isPlacedByPlayer(location)); assertTrue(first.isPlacedByPlayer(location));
            assertTrue(new BlockPlaceListener(null).isPlacedByPlayer(location));
            // Movement/reset is authoritative in eco/provider, never inferred from a local cache.
            eco.when(() -> BlockUtils.isPlayerPlaced(block)).thenReturn(false);
            assertFalse(first.isPlacedByPlayer(location));
            eco.verify(() -> BlockUtils.isPlayerPlaced(block), times(4));
        }
    }
}

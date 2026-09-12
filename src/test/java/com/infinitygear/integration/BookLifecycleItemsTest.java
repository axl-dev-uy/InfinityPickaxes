package com.infinitygear.integration;

import com.infinitygear.api.v1.BookLifecycleRequest;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class BookLifecycleItemsTest {
    @Test void exactUsesNativeSemanticEqualityWhenSerializationBytesDiffer() {
        ItemStack live = mock(ItemStack.class);
        ItemStack decodedArtifact = mock(ItemStack.class);
        when(live.getAmount()).thenReturn(1);
        when(decodedArtifact.getAmount()).thenReturn(1);
        when(live.isSimilar(decodedArtifact)).thenReturn(true);
        var image = new BookLifecycleRequest.ItemImage(new byte[]{1, 2, 3});

        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(image.serializedItem())).thenReturn(decodedArtifact);
            assertTrue(BookLifecycleItems.exact(live, image));
        }
    }
}

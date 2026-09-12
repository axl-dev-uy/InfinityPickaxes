package com.infinitygear.integration;

import com.infinitypickaxes.core.duplicate.PickaxeDuplicateService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArchiveIntegrationBootstrapTest {
    @Test
    void bookApplicationRegistrationRequiresAnActiveMariaDbQuarantineAuthority() {
        var duplicates = mock(PickaxeDuplicateService.class);
        when(duplicates.authorityReady()).thenReturn(true); // Healthy SQLite authority is insufficient.
        when(duplicates.mariaAuthorityReady()).thenReturn(false);
        assertFalse(ArchiveIntegrationBootstrap.bookApplicationQuarantineReady(duplicates));

        when(duplicates.mariaAuthorityReady()).thenReturn(true);
        assertTrue(ArchiveIntegrationBootstrap.bookApplicationQuarantineReady(duplicates));
    }
}

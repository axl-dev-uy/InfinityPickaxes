package com.infinitygear.integration;

import com.infinitygear.api.v1.ArchiveIntegrationService;
import com.infinitygear.api.InfinityGearService;
import com.infinitygear.enchant.ResolvedEnchantmentPolicy;
import com.infinitygear.gear.*;
import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.enchant.*;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveDiscoveryTest {
    @Test void enumeratesProfilesAndExcludesUnavailableEnchantmentsWithContentRevision() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var plugin = mock(InfinityPickaxes.class); var registry = mock(GearProfileRegistry.class);
            var enchants = mock(EnchantManager.class); var hook = mock(EcoEnchantsHook.class);
            var service = mock(InfinityGearService.class); var profile = mock(GearProfile.class);
            when(plugin.getGearProfiles()).thenReturn(registry); when(plugin.getGearService()).thenReturn(service);
            when(plugin.getEnchantManager()).thenReturn(enchants); when(enchants.getEcoHook()).thenReturn(hook);
            when(registry.all()).thenReturn(List.of(profile)); when(profile.id()).thenReturn("infinitygear:pickaxe");
            when(profile.displayName()).thenReturn("Pickaxe"); when(profile.enabled()).thenReturn(true);
            when(profile.compatibleTargets()).thenReturn(Set.of("PICKAXE"));
            var socket = mock(EnchantSocket.class);
            when(socket.getKeyString()).thenReturn("minecraft:fortune"); when(socket.getDisplayName()).thenReturn("Fortune");
            when(service.eligibleEnchantments(profile.id())).thenReturn(List.of(socket));
            when(enchants.getAllSockets()).thenReturn(List.of(socket));
            var discovery = new ArchiveDiscoveryService(plugin);
            var a = discovery.snapshot(0); var b = discovery.snapshot(0);
            assertEquals(a.revision(), b.revision());
            assertTrue(a.profiles().getFirst().enchantments().isEmpty());
            assertTrue(a.profiles().getFirst().enabled());
            when(profile.enabled()).thenReturn(false); when(service.eligibleEnchantments(profile.id())).thenReturn(List.of());
            var disabled = discovery.snapshot(0); assertNotEquals(a.revision(), disabled.revision());
            assertFalse(disabled.profiles().getFirst().enabled());
        }
    }
    @Test void serviceCanBeRegisteredByPublicInterface() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var manager = mock(org.bukkit.plugin.PluginManager.class);
            var server = mock(org.bukkit.Server.class); when(server.getPluginManager()).thenReturn(manager);
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            var services = new org.bukkit.plugin.SimpleServicesManager();
            var plugin = mock(org.bukkit.plugin.Plugin.class); var service = mock(ArchiveIntegrationService.class);
            services.register(ArchiveIntegrationService.class, service, plugin, org.bukkit.plugin.ServicePriority.Normal);
            assertSame(service, services.load(ArchiveIntegrationService.class));
            services.unregisterAll(plugin); assertNull(services.load(ArchiveIntegrationService.class));
        }
    }
}

package com.infinitygear.integration;

import com.axl.custodian.api.CustodianApi;
import com.axl.custodian.api.ShadowContributor;
import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.config.ConfigManager;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustodianShadowConnectionTest {
    @Test
    void unavailableServicesLeaveShadowScannerInactive() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(server.getServicesManager()).thenReturn(services);

        assertTrue(CustodianSettledShadowScanner.connect(plugin).isEmpty());
        verify(services).getRegistration(CustodianApi.class);
        verify(services).getRegistration(ShadowContributor.class);
    }

    @Test
    void providerRejectedServerIdLeavesShadowScannerInactive() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration config = mock(FileConfiguration.class);
        Logger logger = mock(Logger.class);
        CustodianApi api = mock(CustodianApi.class);
        ShadowContributor shadow = mock(ShadowContributor.class);
        @SuppressWarnings("unchecked")
        RegisteredServiceProvider<CustodianApi> apiRegistration = mock(RegisteredServiceProvider.class);
        @SuppressWarnings("unchecked")
        RegisteredServiceProvider<ShadowContributor> shadowRegistration = mock(RegisteredServiceProvider.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getConfig()).thenReturn(config);
        when(config.getString("custodian-shadow.server-id", "local")).thenReturn("paper-beta");
        when(config.getLong("custodian-shadow.heartbeat-ticks", 200L)).thenReturn(200L);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getScheduler()).thenReturn(scheduler);
        when(services.getRegistration(CustodianApi.class)).thenReturn(apiRegistration);
        when(services.getRegistration(ShadowContributor.class)).thenReturn(shadowRegistration);
        when(apiRegistration.getProvider()).thenReturn(api);
        when(shadowRegistration.getProvider()).thenReturn(shadow);
        when(shadow.startBridgeEpoch(any(), eq("paper-beta")))
                .thenThrow(new IllegalArgumentException("server mismatch"));

        assertTrue(CustodianSettledShadowScanner.connect(plugin).isEmpty());
        verify(scheduler, never()).runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong());
    }
}

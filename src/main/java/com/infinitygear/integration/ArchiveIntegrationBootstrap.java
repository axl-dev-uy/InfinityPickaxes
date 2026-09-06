package com.infinitygear.integration;

import com.infinitygear.api.v1.BookIssuanceService;
import com.infinitygear.persistence.*;
import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import java.io.File;
import java.util.concurrent.*;

public final class ArchiveIntegrationBootstrap implements AutoCloseable {
    private final IntegrationTasks tasks;
    private volatile boolean closed;
    public ArchiveIntegrationBootstrap(InfinityPickaxes plugin) {
        tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(Thread.ofPlatform().name("InfinityGear-journal").factory()),
                task -> plugin.getServer().getScheduler().runTask(plugin, task));
        File file = new File(plugin.getDataFolder(), "database.yml");
        if (!file.exists()) plugin.saveResource("database.yml", false);
        var config = YamlConfiguration.loadConfiguration(file);
        if (!config.getBoolean("enabled")) return;
        final MariaBookLedger ledger;
        try {
            ledger = new MariaBookLedger(new DriverDataSource(config.getString("url"),
                    config.getString("username", ""), config.getString("password", "")));
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().severe("Archive issuance unavailable: invalid MariaDB bootstrap configuration");
            return;
        }
        tasks.database(() -> { ledger.migrate(); return null; }).thenCompose(ignored -> tasks.server(() -> {
                    if (!closed) plugin.getServer().getServicesManager().register(BookIssuanceService.class,
                            new CanonicalIssuanceService(plugin, ledger, ledger, tasks), plugin, ServicePriority.Normal);
                    return null;
                })).whenComplete((ignored, unavailable) -> {
            if (unavailable != null && !closed) {
                // JDBC exception text may contain connection credentials. Do not log it.
                plugin.getLogger().severe("Archive issuance unavailable: MariaDB initialization failed (" + unavailable.getClass().getSimpleName() + ")");
            }
        });
    }
    @Override public void close() { closed = true; tasks.close(); }
}

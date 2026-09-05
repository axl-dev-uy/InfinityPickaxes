package com.infinitygear.integration;

import com.infinitygear.api.v1.BookIssuanceService;
import com.infinitygear.persistence.*;
import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import java.io.File;
import java.util.concurrent.*;

public final class ArchiveIntegrationBootstrap implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("InfinityGear-journal").factory());
    private volatile boolean closed;
    public ArchiveIntegrationBootstrap(InfinityPickaxes plugin) {
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
        executor.submit(() -> {
            try {
                ledger.migrate();
                if (closed) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!closed) plugin.getServer().getServicesManager().register(BookIssuanceService.class,
                            new CanonicalIssuanceService(plugin, ledger, executor), plugin, ServicePriority.Normal);
                });
            } catch (Exception unavailable) {
                // JDBC exception text may contain connection credentials. Do not log it.
                plugin.getLogger().severe("Archive issuance unavailable: MariaDB initialization failed (" + unavailable.getClass().getSimpleName() + ")");
            }
        });
    }
    @Override public void close() { closed = true; executor.shutdownNow(); }
}

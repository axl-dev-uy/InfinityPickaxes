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
        final MariaMiningJournal miningJournal;
        final MariaMiningXpLedger xpLedger;
        try {
            var source = new DriverDataSource(config.getString("url"), config.getString("username", ""), config.getString("password", ""));
            ledger = new MariaBookLedger(source);
            miningJournal = new MariaMiningJournal(source);
            xpLedger = new MariaMiningXpLedger(source);
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().severe("Archive issuance unavailable: invalid MariaDB bootstrap configuration");
            return;
        }
        tasks.database(() -> { ledger.migrate(); miningJournal.migrate(); xpLedger.migrate(); return null; }).thenCompose(ignored -> tasks.server(() -> {
                    if (!closed) {
                        var activation = new com.infinitygear.mining.XpActivationService(plugin, tasks, xpLedger);
                        plugin.setXpActivation(activation);
                        plugin.getServer().getPluginManager().registerEvents(activation, plugin);
                        activation.recoverLoadedOnStart();
                    }
                    if (!closed) plugin.getServer().getServicesManager().register(com.infinitygear.api.v1.MiningIncidentService.class,
                            new com.infinitygear.mining.MiningIncidentRecorder(tasks, miningJournal,
                                    new com.infinitygear.mining.MiningIncidentAlerts(
                                            () -> com.infinitygear.mining.MiningIncidentAlerts.Settings.read(plugin.getConfigManager().getConfig()),
                                            () -> plugin.getServer().getOnlinePlayers(), System::currentTimeMillis)), plugin, ServicePriority.Normal);
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

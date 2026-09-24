package com.infinitygear.integration;

import com.infinitygear.api.v1.BookIssuanceService;
import com.infinitygear.api.v1.BookApplicationService;
import com.infinitygear.api.v1.MiningAuthority;
import com.infinitygear.mining.*;
import com.infinitygear.persistence.*;
import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.Executors;

/** Owns the migrated MariaDB integration and its complete fail-closed lifecycle. */
public final class ArchiveIntegrationBootstrap implements AutoCloseable {
    private final InfinityPickaxes plugin;
    private final IntegrationTasks tasks;
    private volatile boolean closed;
    private volatile boolean ready;
    private MiningCompletionReceiver completionReceiver;
    private TrackedBookApplicationService bookApplication;
    private MiningCreditConsumer creditConsumer;
    private MiningNotificationDispatcher dispatcher;
    private MiningCreditDeliveryProvider archiveDeliveryProvider;
    private MariaSingleServerCustody custody;
    private BukkitTask dispatchTask;
    private volatile boolean mariaQuarantineReady;

    public ArchiveIntegrationBootstrap(InfinityPickaxes plugin) {
        this.plugin = plugin;
        tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(Thread.ofPlatform().name("InfinityGear-journal").factory()),
                task -> plugin.getServer().getScheduler().runTask(plugin, task));
        File file = new File(plugin.getDataFolder(), "database.yml");
        if (!file.exists()) plugin.saveResource("database.yml", false);
        var config = YamlConfiguration.loadConfiguration(file);
        if (!config.getBoolean("enabled")) return;
        String serverId = config.getString("server-id", "");
        String quarantineMode = config.getString("quarantine-authority", "sqlite");
        boolean mariaQuarantine = "mariadb".equalsIgnoreCase(quarantineMode);
        if (!mariaQuarantine && !"sqlite".equalsIgnoreCase(quarantineMode)) {
            plugin.getLogger().severe("Archive integration unavailable: quarantine-authority must be sqlite or mariadb");
            return;
        }
        String approvedImportSource = config.getString("quarantine-approved-import-source-id", "");
        boolean archiveDeliveryEnabled = config.getBoolean("mining-delivery.archive-contract-enabled", false);
        final MariaBookLedger ledger;
        final MariaMiningJournal miningJournal;
        final MariaMiningXpLedger xpLedger;
        final MariaMiningCreditInbox inbox;
        final MariaBookLifecycleTransaction bookLifecycle;
        final MariaQuarantineAuthority quarantineAuthority;
        final MariaMiningArchiveDelivery archiveDelivery;
        try {
            var source = new DriverDataSource(config.getString("url"), config.getString("username", ""), config.getString("password", ""));
            custody = new MariaSingleServerCustody(source, serverId);
            ledger = new MariaBookLedger(source);
            miningJournal = new MariaMiningJournal(source);
            archiveDelivery = archiveDeliveryEnabled ? new MariaMiningArchiveDelivery(source) : null;
            xpLedger = new MariaMiningXpLedger(source, archiveDelivery);
            inbox = new MariaMiningCreditInbox(source);
            bookLifecycle = new MariaBookLifecycleTransaction(source);
            quarantineAuthority = new MariaQuarantineAuthority(source, custody);
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().severe("Archive integration unavailable: invalid MariaDB bootstrap configuration");
            return;
        }
        int batchSize = Math.max(1, Math.min(1000, config.getInt("mining-delivery.batch-size", 100)));
        long interval = Math.max(1, config.getLong("mining-delivery.interval-ticks", 100));
        long timeout = Math.max(1, config.getLong("mining-delivery.acceptance-timeout-millis", 5000));
        tasks.database(() -> {
            ledger.migrate(); miningJournal.migrate(); xpLedger.migrate(); inbox.migrate(); bookLifecycle.migrate();
            if (archiveDelivery != null) archiveDelivery.requireSchema();
            custody.migrateAndClaimDeployment();
            if (mariaQuarantine) {
                quarantineAuthority.migrate();
                quarantineAuthority.requireAcceptedImport(approvedImportSource);
                return quarantineAuthority.listRestricted();
            }
            return java.util.List.<com.infinitypickaxes.core.duplicate.DuplicateRecord>of();
        }).thenCompose(ignored -> tasks.server(() -> {
            if (closed) return null;
            if (mariaQuarantine) {
                plugin.getDuplicateService().installMariaAuthority(quarantineAuthority, tasks, ignored);
                mariaQuarantineReady = bookApplicationQuarantineReady(plugin.getDuplicateService());
                if (!mariaQuarantineReady) throw new IllegalStateException(
                        "Approved MariaDB quarantine authority did not become active");
            }
            var activation = new XpActivationService(plugin, tasks, xpLedger);
            plugin.setXpActivation(activation);
            plugin.getServer().getPluginManager().registerEvents(activation, plugin);
            var incidents = new MiningIncidentRecorder(tasks, miningJournal,
                    new MiningIncidentAlerts(
                            () -> MiningIncidentAlerts.Settings.read(plugin.getConfigManager().getConfig()),
                            () -> plugin.getServer().getOnlinePlayers(), System::currentTimeMillis));
            plugin.getServer().getServicesManager().register(com.infinitygear.api.v1.MiningIncidentService.class,
                    incidents, plugin, ServicePriority.Normal);
            plugin.getServer().getServicesManager().register(BookIssuanceService.class,
                    new CanonicalIssuanceService(plugin, ledger, ledger, tasks), plugin, ServicePriority.Normal);
            if (mariaQuarantineReady) {
                bookApplication = new TrackedBookApplicationService(plugin, ledger, ledger, bookLifecycle, custody, tasks,
                        this::bookApplicationInfrastructureReady);
                plugin.getServer().getServicesManager().register(BookApplicationService.class,
                        bookApplication, plugin, ServicePriority.Normal);
            } else {
                plugin.getLogger().warning("Archive book application remains unavailable until an approved MariaDB quarantine authority is active");
            }
            creditConsumer = new MiningCreditConsumer(tasks, inbox);
            dispatcher = new MiningNotificationDispatcher(tasks, miningJournal, creditConsumer,
                    batchSize, Duration.ofMillis(timeout));
            if (archiveDelivery != null) {
                archiveDeliveryProvider = new MiningCreditDeliveryProvider(tasks, archiveDelivery, Duration.ofMillis(timeout));
                plugin.getServer().getServicesManager().register(com.infinitygear.api.v1.MiningCreditDeliveryService.class,
                        archiveDeliveryProvider, plugin, ServicePriority.Normal);
            }
            completionReceiver = new MiningCompletionReceiver(plugin, tasks, xpLedger, incidents,
                    this::infrastructureReady);
            plugin.getServer().getServicesManager().register(MiningAuthority.Receiver.class,
                    completionReceiver, plugin, ServicePriority.Normal);
            dispatchTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drainOutbox, 1L, interval);
            ready = true;
            activation.recoverLoadedOnStart();
            drainOutbox();
            return null;
        })).whenComplete((ignored, unavailable) -> {
            if (unavailable != null && !closed) {
                ready = false;
                // JDBC exception text may contain connection credentials. Do not log it.
                plugin.getLogger().severe("Archive integration unavailable: MariaDB initialization failed ("
                        + unavailable.getClass().getSimpleName() + ")");
            }
        });
    }

    private void drainOutbox() {
        MiningNotificationDispatcher current = dispatcher;
        if (!infrastructureReady() || current == null) return;
        current.drain().whenComplete((batch, failure) -> {
            if (failure != null) {
                if (!closed) plugin.getLogger().warning("Mining credit outbox drain failed; credits remain pending ("
                        + failure.getClass().getSimpleName() + ")");
            } else if (batch.deferred() > 0 || !batch.failed().isEmpty()) {
                plugin.getLogger().warning("Mining credit outbox retained " + batch.deferred()
                        + " deferred and " + batch.failed().size() + " failed credit(s); operator inspection required");
            }
        });
        MiningCreditDeliveryProvider archive = archiveDeliveryProvider;
        if (archive != null) archive.drain().whenComplete((batch, failure) -> {
            if (failure != null && !closed)
                plugin.getLogger().warning("Archive credit delivery retained its pending head ("
                        + failure.getClass().getSimpleName() + ")");
        });
    }

    public void beginReload() {
        ready = false;
        if (completionReceiver != null) completionReceiver.reload();
    }

    public void finishReload() {
        if (closed || completionReceiver == null || dispatcher == null || creditConsumer == null) return;
        ready = true;
        if (!infrastructureReady()) { ready = false; return; }
        drainOutbox();
    }

    public boolean miningPipelineActive() { return infrastructureReady(); }

    private boolean bookApplicationInfrastructureReady() {
        if (closed || !ready || bookApplication == null || !mariaQuarantineReady
                || !bookApplicationQuarantineReady(plugin.getDuplicateService())) return false;
        try {
            return plugin.getServer().getServicesManager().getRegistrations(BookApplicationService.class).stream()
                    .anyMatch(registration -> registration.getProvider() == bookApplication)
                    && plugin.getServer().getServicesManager().load(BookApplicationService.PolicyAuthority.class) != null;
        } catch (RuntimeException unavailable) { return false; }
    }

    static boolean bookApplicationQuarantineReady(
            com.infinitypickaxes.core.duplicate.PickaxeDuplicateService duplicates) {
        return duplicates != null && duplicates.mariaAuthorityReady();
    }

    private boolean infrastructureReady() {
        if (closed || !ready || completionReceiver == null || dispatcher == null || creditConsumer == null
                || !plugin.getDuplicateService().authorityReady()
                || !completionReceiver.active() || !creditConsumer.active() || dispatchTask == null
                || dispatchTask.isCancelled()) return false;
        try {
            return plugin.getServer().getServicesManager().getRegistrations(MiningAuthority.Receiver.class).stream()
                    .anyMatch(registration -> registration.getProvider() == completionReceiver);
        } catch (RuntimeException unavailable) { return false; }
    }

    @Override public void close() {
        if (closed) return;
        ready = false;
        closed = true;
        mariaQuarantineReady = false;
        if (bookApplication != null) {
            plugin.getServer().getServicesManager().unregister(BookApplicationService.class, bookApplication);
            bookApplication.close();
        }
        if (completionReceiver != null) {
            plugin.getServer().getServicesManager().unregister(MiningAuthority.Receiver.class, completionReceiver);
            completionReceiver.close();
        }
        if (dispatchTask != null) dispatchTask.cancel();
        if (dispatcher != null) dispatcher.close();
        if (creditConsumer != null) creditConsumer.close();
        plugin.setXpActivation(null);
        if (archiveDeliveryProvider == null) tasks.close();
        else {
            plugin.getServer().getServicesManager().unregister(
                    com.infinitygear.api.v1.MiningCreditDeliveryService.class, archiveDeliveryProvider);
            archiveDeliveryProvider.shutdown().whenComplete((ignored, failure) -> tasks.close());
        }
    }
}

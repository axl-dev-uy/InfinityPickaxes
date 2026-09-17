package com.infinitypickaxes;

import com.infinitypickaxes.commands.InfinityPickaxeCommand;
import com.infinitypickaxes.config.ConfigManager;
import com.infinitypickaxes.config.MessageManager;
import com.infinitypickaxes.core.enchant.EnchantManager;
import com.infinitypickaxes.core.duplicate.PickaxeDuplicateService;
import com.infinitypickaxes.core.level.LevelManager;
import com.infinitypickaxes.core.limitbreak.LimitBreakManager;
import com.infinitypickaxes.core.pickaxe.PickaxeManager;
import com.infinitypickaxes.gui.CustomGui;
import com.infinitypickaxes.gui.GuiManager;
import com.infinitypickaxes.hooks.PlaceholderAPIHook;
import com.infinitypickaxes.listeners.*;
import com.infinitypickaxes.utils.TextUtil;
import com.infinitygear.config.LegacyDataFolderMigrator;
import com.infinitygear.gear.GearManager;
import com.infinitygear.gear.GearProfileRegistry;
import com.infinitygear.api.InfinityGearService;
import com.infinitygear.api.InfinityGearServiceImpl;
import com.infinitygear.station.StationManager;
import com.infinitygear.station.StationListener;
import com.infinitygear.cost.CostRegistry;
import com.infinitygear.cost.MoneyGateway;
import com.infinitygear.cost.UnavailableMoneyGateway;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/** @deprecated Bootstrap compatibility type. The deployed main class is InfinityGearPlugin. */
@Deprecated
public class InfinityPickaxes extends JavaPlugin {

    private static InfinityPickaxes instance;

    private ConfigManager configManager;
    private MessageManager messageManager;
    private LevelManager levelManager;
    private EnchantManager enchantManager;
    private LimitBreakManager limitBreakManager;
    private PickaxeManager pickaxeManager;
    private GuiManager guiManager;
    private PickaxeHeldListener heldListener;
    private PlaceholderAPIHook papiHook;
    private PickaxeDuplicateService duplicateService;
    private DuplicateDetectionListener duplicateListener;
    private GearProfileRegistry gearProfiles;
    private GearManager gearManager;
    private InfinityGearService gearService;
    private StationManager stationManager;
    private CostRegistry costRegistry;
    private MoneyGateway moneyGateway;
    private com.infinitygear.integration.ArchiveIntegrationBootstrap archiveIntegration;
    private com.infinitygear.mining.XpActivationService xpActivation;
    private com.infinitygear.integration.CustodianSettledShadowScanner custodianShadow;

    @Override
    public void onEnable() {
        instance = this;
        long start = System.currentTimeMillis();
        ConsoleCommandSender console = Bukkit.getConsoleSender();

        org.bukkit.plugin.Plugin legacy = getServer().getPluginManager().getPlugin("InfinityPickaxes");
        if (legacy != null && legacy != this && legacy.isEnabled()) {
            getLogger().severe("InfinityGear cannot run while the legacy InfinityPickaxes plugin is active. "
                    + "Remove the old jar after backing it up; InfinityGear provides the legacy identity.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            java.io.File parent = getDataFolder().getParentFile();
            if (parent != null) {
                var migration = LegacyDataFolderMigrator.migrate(
                        parent.toPath().resolve("InfinityPickaxes"), getDataFolder().toPath(), java.time.Clock.systemUTC());
                if (migration.migrated()) getLogger().info("Migrated " + migration.copiedFiles()
                        + " missing legacy data files after backup to " + migration.backup() + '.');
            }
        } catch (Exception migrationFailure) {
            getLogger().severe("Legacy data-folder migration failed safely; InfinityGear will not enable: "
                    + migrationFailure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Print header banner
        console.sendMessage(TextUtil.parse(""));
        console.sendMessage(TextUtil.parse("<gradient:#00E5FF:#0077FE>  ██╗███╗   ██╗███████╗██╗███╗   ██╗██╗████████╗██╗   ██╗</gradient>"));
        console.sendMessage(TextUtil.parse("<gradient:#00E5FF:#0077FE>  ██║████╗  ██║██╔════╝██║████╗  ██║██║╚══██╔══╝╚██╗ ██╔╝</gradient>"));
        console.sendMessage(TextUtil.parse("<gradient:#00E5FF:#0077FE>  ██║██╔██╗ ██║█████╗  ██║██╔██╗ ██║██║   ██║    ╚████╔╝ </gradient>"));
        console.sendMessage(TextUtil.parse("<gradient:#00E5FF:#0077FE>  ██║██║╚██╗██║██╔══╝  ██║██║╚██╗██║██║   ██║     ╚██╔╝  </gradient>"));
        console.sendMessage(TextUtil.parse("<gradient:#00E5FF:#0077FE>  ██║██║ ╚████║██║     ██║██║ ╚████║██║   ██║      ██║   </gradient>"));
        console.sendMessage(TextUtil.parse("<gradient:#0077FE:#00E5FF>  ╚═╝╚═╝  ╚═══╝╚═╝     ╚═╝╚═╝  ╚═══╝╚═╝   ╚═╝      ╚═╝   </gradient>"));
        console.sendMessage(TextUtil.parse("<gradient:#00FF88:#00E5FF><b>       ⚒ INFINITY GEAR </b></gradient><gray>v<yellow>" + getDescription().getVersion() + "</yellow> <dark_gray>┃</dark_gray> <gray>Paper 26.2"));
        console.sendMessage(TextUtil.parse("<dark_gray>  ─────────────────────────────────────────────────────────────</dark_gray>"));

        // 1. Initialize Configuration & Locales
        this.configManager = new ConfigManager(this);
        this.messageManager = new MessageManager(this);
        this.gearProfiles = new GearProfileRegistry();
        this.gearProfiles.load(configManager.getProfilesConfig(), getLogger());
        this.costRegistry = new CostRegistry();
        this.costRegistry.load(configManager.getCostsConfig(), getLogger());
        this.moneyGateway = createMoneyGateway();
        validateCostProviders();
        console.sendMessage(TextUtil.parse("<green>  ✔ <dark_gray>[1/6]</dark_gray> <white>Configuration & Locales:</white> <green>Loaded (Default: " + configManager.getCurrentLanguage() + ")</green>"));

        // 2. Initialize Core Subsystems & LimitBreak
        this.levelManager = new LevelManager(this);
        this.enchantManager = new EnchantManager(this);
        this.limitBreakManager = new LimitBreakManager(this);
        try {
            this.duplicateService = new com.infinitygear.duplicate.GearDuplicateService(this);
        } catch (Exception exception) {
            getLogger().severe("Could not initialize duplicate protection: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.pickaxeManager = new PickaxeManager(this);
        this.gearManager = new GearManager(this, gearProfiles);
        this.gearService = new InfinityGearServiceImpl(this, gearManager, gearProfiles);
        getServer().getServicesManager().register(InfinityGearService.class, gearService, this,
                org.bukkit.plugin.ServicePriority.Normal);
        getServer().getServicesManager().register(com.infinitygear.api.v1.ArchiveIntegrationService.class,
                new com.infinitygear.integration.ArchiveDiscoveryService(this), this,
                org.bukkit.plugin.ServicePriority.Normal);
        this.guiManager = new GuiManager(this);
        this.stationManager = new StationManager(this);
        this.archiveIntegration = new com.infinitygear.integration.ArchiveIntegrationBootstrap(this);
        if (getServer().getPluginManager().isPluginEnabled("Custodian")) {
            try {
                this.custodianShadow = com.infinitygear.integration.CustodianSettledShadowScanner
                        .connect(this).orElse(null);
            } catch (LinkageError unavailable) {
                getLogger().warning("Custodian API is unavailable; legacy duplicate protection remains authoritative.");
            }
        }

        int socketsCount = enchantManager.getAllSockets().size();
        boolean ecoPresent = enchantManager.getEcoHook().isEcoEnchantsPresent();
        console.sendMessage(TextUtil.parse("<green>  ✔ <dark_gray>[2/6]</dark_gray> <white>EcoEnchants & LimitBreak:</white> " +
                (ecoPresent ? "<green>Connected </green>" : "<red>Unavailable </red>") +
                "<dark_gray>(" + socketsCount + " sockets, LimitBreak +" + limitBreakManager.getMaxExtraLevels() + ")</dark_gray>"));

        console.sendMessage(TextUtil.parse("<green>  ✔ <dark_gray>[3/6]</dark_gray> <white>Leveling System:</white> <green>Ready </green><dark_gray>(Max Level: " + levelManager.getMaxLevel() + ")</dark_gray>"));
        console.sendMessage(TextUtil.parse("<green>  ✔ <dark_gray>[4/6]</dark_gray> <white>Duplicate Protection:</white> <green>Ready</green>"));

        // 3. Register Event Listeners
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new QuarantineListener(this), this);
        pm.registerEvents(new com.infinitygear.gear.GearAutoConvertListener(this), this);
        BlockPlaceListener placeListener = new BlockPlaceListener(this);
        pm.registerEvents(placeListener, this);
        pm.registerEvents(new BlockBreakListener(this, placeListener), this);
        pm.registerEvents(new PickaxeInteractListener(this), this);
        pm.registerEvents(this.guiManager, this);
        pm.registerEvents(new StationListener(this, stationManager), this);
        pm.registerEvents(new com.infinitygear.integration.BookLifecycleCustodyListener(), this);
        if (getServer().getPluginManager().isPluginEnabled("Nexo")) {
            pm.registerEvents(new com.infinitygear.nexo.NexoStationListener(this, stationManager), this);
        }

        this.heldListener = new PickaxeHeldListener(this);
        pm.registerEvents(this.heldListener, this);
        this.duplicateListener = new DuplicateDetectionListener(this,
                custodianShadow == null ? null : custodianShadow::accept);
        pm.registerEvents(this.duplicateListener, this);

        // 4. Register PlaceholderAPI Hook if present
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.papiHook = new PlaceholderAPIHook(this);
            this.papiHook.register();
            console.sendMessage(TextUtil.parse("<green>  ✔ <dark_gray>[5/6]</dark_gray> <white>PlaceholderAPI Bridge:</white> <green>Expansion registered for zMenu/DeluxeMenus.</green>"));
        } else {
            console.sendMessage(TextUtil.parse("<yellow>  ℹ <dark_gray>[5/6]</dark_gray> <white>PlaceholderAPI Bridge:</white> <dark_gray>Not detected (Optional).</dark_gray>"));
        }

        // 5. Register Commands
        InfinityPickaxeCommand executor = new InfinityPickaxeCommand(this);
        for (String commandName : java.util.List.of("infinitygear", "infinitypickaxes")) {
            PluginCommand cmd = getCommand(commandName);
            if (cmd == null) continue;
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
            console.sendMessage(TextUtil.parse("<green>  ✔ <dark_gray>[6/6]</dark_gray> <white>Commands & Events:</white> <green>Registered (/igear plus legacy aliases).</green>"));
        }

        long elapsed = System.currentTimeMillis() - start;
        console.sendMessage(TextUtil.parse("<dark_gray>  ─────────────────────────────────────────────────────────────</dark_gray>"));
        console.sendMessage(TextUtil.parse("<gradient:#00FF88:#00E5FF><b>  ✨ InfinityGear enabled and ready in " + elapsed + "ms! ✨</b></gradient>"));
        console.sendMessage(TextUtil.parse(""));
    }

    /**
     * Completely safe reload method without memory leaks or duplicate tasks.
     */
    public void reloadPlugin(CommandSender sender) {
        long start = System.currentTimeMillis();
        if (archiveIntegration != null) archiveIntegration.beginReload();

        // 1. Close open CustomGui inventories
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof CustomGui) {
                p.closeInventory();
            }
        }

        // 2. Reload configurations and locales
        this.configManager.reload();
        this.gearProfiles.load(configManager.getProfilesConfig(), getLogger());
        this.costRegistry.load(configManager.getCostsConfig(), getLogger());
        this.moneyGateway = createMoneyGateway();
        validateCostProviders();
        this.stationManager.reload();

        // 3. Reload core subsystems
        this.levelManager.loadConfig();
        this.enchantManager.loadConfig();
        if (this.duplicateListener != null) this.duplicateListener.reload();

        // 4. Refresh all pickaxes currently held by players
        if (this.heldListener != null) {
            this.heldListener.refreshAllHeldPickaxes();
        }
        // Apply changed generic profile lore immediately. PlayerInventory contents
        // include storage, armor and offhand slots; ordinary items are never converted here.
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                this.gearManager.refreshPresentation(item);
            }
        }

        // 5. Ensure PlaceholderAPI hook is registered
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") && papiHook == null) {
            this.papiHook = new PlaceholderAPIHook(this);
            this.papiHook.register();
        }
        if (archiveIntegration != null) archiveIntegration.finishReload();

        long elapsed = System.currentTimeMillis() - start;
        messageManager.sendMessage(sender, "messages.reload-success");
        getLogger().info("InfinityGear reloaded in " + elapsed + "ms. Active language: " + configManager.getCurrentLanguage());
    }

    @Override
    public void onDisable() {
        if (archiveIntegration != null) archiveIntegration.close();
        if (duplicateListener != null) duplicateListener.stop();
        if (custodianShadow != null) custodianShadow.close();

        // 1. Close any open CustomGui inventories
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof CustomGui) {
                p.closeInventory();
            }
        }

        // 2. Cancel all bukkit scheduler tasks for this plugin
        Bukkit.getScheduler().cancelTasks(this);

        // 3. Unregister PlaceholderAPI
        if (papiHook != null) {
            try {
                papiHook.unregister();
            } catch (Throwable ignored) {}
            papiHook = null;
        }
        if (duplicateService != null) {
            try {
                duplicateService.close();
            } catch (Exception exception) {
                getLogger().warning("Could not close duplicate registry cleanly: " + exception.getMessage());
            }
        }

        ConsoleCommandSender console = Bukkit.getConsoleSender();
        console.sendMessage(TextUtil.parse("<dark_gray>  ─────────────────────────────────────────────────────────────</dark_gray>"));
        console.sendMessage(TextUtil.parse("<red>  ✖ </red><gradient:#FF5555:#FF0055><b>INFINITY GEAR</b></gradient> <dark_gray>»</dark_gray> <gray>Plugin disabled safely and all tasks terminated.</gray>"));
        console.sendMessage(TextUtil.parse("<dark_gray>  ─────────────────────────────────────────────────────────────</dark_gray>"));
        instance = null;
    }

    public static InfinityPickaxes getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public LevelManager getLevelManager() {
        return levelManager;
    }

    public EnchantManager getEnchantManager() {
        return enchantManager;
    }

    public LimitBreakManager getLimitBreakManager() {
        return limitBreakManager;
    }

    public PickaxeManager getPickaxeManager() {
        return pickaxeManager;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public PickaxeDuplicateService getDuplicateService() {
        return duplicateService;
    }
    public com.infinitygear.duplicate.GearDuplicateService getGearDuplicateService() {
        return duplicateService instanceof com.infinitygear.duplicate.GearDuplicateService service ? service : null;
    }

    public GearProfileRegistry getGearProfiles() { return gearProfiles; }
    public GearManager getGearManager() { return gearManager; }
    public InfinityGearService getGearService() { return gearService; }
    public StationManager getStationManager() { return stationManager; }
    public CostRegistry getCostRegistry() { return costRegistry; }
    public MoneyGateway getMoneyGateway() { return moneyGateway; }
    public com.infinitygear.mining.XpActivationService getXpActivation() { return xpActivation; }
    public void setXpActivation(com.infinitygear.mining.XpActivationService service) { this.xpActivation = service; }
    public boolean isStrictMiningDeliveryActive() {
        return archiveIntegration != null && archiveIntegration.miningPipelineActive();
    }

    private MoneyGateway createMoneyGateway() {
        if (!getServer().getPluginManager().isPluginEnabled("Vault")) return new UnavailableMoneyGateway();
        try {
            var registration = getServer().getServicesManager()
                    .getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (registration != null) return new com.infinitygear.vault.VaultMoneyGateway(registration.getProvider());
        } catch (LinkageError unavailable) {
            getLogger().severe("Vault is enabled but its Economy API could not be linked; money options are disabled.");
        }
        return new UnavailableMoneyGateway();
    }

    private void validateCostProviders() {
        com.infinitygear.nexo.NexoProvider nexo = getServer().getPluginManager().isPluginEnabled("Nexo")
                ? new com.infinitygear.nexo.NexoProvider() : null;
        costRegistry.disableUnavailableProviders(moneyGateway, nexo,
                configManager.getItemsConfig(), getLogger());
    }

    public void refreshCostProviders() {
        costRegistry.load(configManager.getCostsConfig(), getLogger());
        moneyGateway = createMoneyGateway();
        validateCostProviders();
    }
}

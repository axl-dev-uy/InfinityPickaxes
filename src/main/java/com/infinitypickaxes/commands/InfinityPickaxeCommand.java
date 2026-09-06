package com.infinitypickaxes.commands;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.enchant.EnchantSocket;
import com.infinitypickaxes.core.duplicate.DuplicateRecord;
import com.infinitypickaxes.core.duplicate.DuplicateScanResult;
import com.infinitypickaxes.core.pickaxe.InfinityPickaxe;
import com.infinitypickaxes.gui.MainPickaxeGui;
import com.infinitygear.api.GearSnapshot;
import com.infinitygear.data.TrackedKind;
import com.infinitygear.gui.StationGui;
import com.infinitygear.station.StationType;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;

public class InfinityPickaxeCommand implements CommandExecutor, TabCompleter {

    private final InfinityPickaxes plugin;

    public InfinityPickaxeCommand(InfinityPickaxes plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        boolean gearCommand = command.getName().equalsIgnoreCase("infinitygear");
        if (args.length == 0) {
            if (gearCommand) {
                if (!(sender instanceof Player player)) { sendHelp(sender); return true; }
                if (!hasUse(player)) { plugin.getMessageManager().sendMessage(player, "messages.no-permission"); return true; }
                GearSnapshot snapshot = plugin.getGearService().inspect(player.getInventory().getItemInMainHand()).orElse(null);
                if (snapshot == null) { player.sendMessage("§cHold an InfinityGear item to inspect it."); return true; }
                player.sendMessage("§bInfinityGear §8» §f" + snapshot.profileId() + " §7Lv." + snapshot.level());
                player.sendMessage("§7UUID: §f" + snapshot.uuid() + " §8| §7Sockets: §f"
                        + snapshot.usedSockets() + "/" + snapshot.socketCapacity());
                return true;
            }
            if (sender instanceof Player player) {
                if (!hasUse(player)) {
                    plugin.getMessageManager().sendMessage(player, "messages.no-permission");
                    return true;
                }
                InfinityPickaxe pickaxe = plugin.getPickaxeManager().getHeldPickaxe(player);
                if (pickaxe != null) {
                    new MainPickaxeGui(plugin, player, pickaxe).open();
                    return true;
                } else {
                    plugin.getMessageManager().sendMessage(player, "messages.must-hold-pickaxe");
                    return true;
                }
            }
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (gearCommand && sub.equals("give")) return handleGearGive(sender, label, args);
        if (gearCommand && (sub.equals("artifact") || sub.equals("giveartifact"))) {
            return handleArtifactGive(sender, label, args);
        }
        if (gearCommand && sub.equals("station")) return handleStation(sender, label, args);
        if (gearCommand && sub.equals("migration")) {
            if (!hasPermissionOrAdmin(sender, "infinitygear.admin.migration")) { plugin.getMessageManager().sendMessage(sender, "messages.no-permission"); return true; }
            java.nio.file.Path marker = plugin.getDataFolder().toPath()
                    .resolve(com.infinitygear.config.LegacyDataFolderMigrator.MARKER);
            sender.sendMessage(java.nio.file.Files.exists(marker)
                    ? "§aLegacy data-folder migration marker is present: §f" + marker
                    : "§eNo legacy data-folder migration marker is present.");
            return true;
        }

        switch (sub) {
            case "reload" -> {
                if (!(gearCommand ? hasPermissionOrAdmin(sender, "infinitygear.admin.reload") : hasAdmin(sender))) {
                    plugin.getMessageManager().sendMessage(sender, "messages.no-permission");
                    return true;
                }
                plugin.reloadPlugin(sender);
            }

            case "give" -> {
                if (!hasAdmin(sender)) {
                    plugin.getMessageManager().sendMessage(sender, "messages.no-permission");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /" + label + " give <player> [level]");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    plugin.getMessageManager().sendMessage(sender, "messages.player-not-found");
                    return true;
                }
                int level = 0;
                if (args.length >= 3) {
                    try {
                        level = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ignored) {}
                }
                ItemStack item = plugin.getPickaxeManager().createPickaxe(level);
                target.getInventory().addItem(item);

                plugin.getMessageManager().sendMessage(sender, "messages.pickaxe-given",
                        "%level%", String.valueOf(level),
                        "%player%", target.getName());
                plugin.getMessageManager().sendMessage(target, "messages.pickaxe-received",
                        "%level%", String.valueOf(level));
            }

            case "book", "limitbreak" -> {
                if (!hasAdmin(sender)) {
                    plugin.getMessageManager().sendMessage(sender, "messages.no-permission");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /" + label + " book <enchant|universal> [amount] [player]");
                    return true;
                }

                String enchantArg = args[1].toLowerCase();
                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Math.max(1, Integer.parseInt(args[2]));
                    } catch (NumberFormatException ignored) {}
                }

                Player target = (sender instanceof Player p) ? p : null;
                if (args.length >= 4) {
                    target = Bukkit.getPlayer(args[3]);
                }

                if (target == null) {
                    plugin.getMessageManager().sendMessage(sender, "messages.player-not-found");
                    return true;
                }

                ItemStack bookItem;
                if (enchantArg.equalsIgnoreCase("universal") || enchantArg.equalsIgnoreCase("super")) {
                    bookItem = plugin.getLimitBreakManager().createUniversalBook(amount);
                    plugin.getMessageManager().sendMessage(target, "messages.limitbreak-universal-received",
                            "%amount%", String.valueOf(amount));
                    if (!target.equals(sender)) {
                        plugin.getMessageManager().sendMessage(sender, "messages.limitbreak-book-given",
                                "%amount%", String.valueOf(amount),
                                "%enchant%", "Universal Super Book",
                                "%player%", target.getName());
                    }
                } else {
                    EnchantSocket socket = plugin.getEnchantManager().getSocket(enchantArg);
                    if (socket == null) {
                        socket = plugin.getEnchantManager().getSocketByKey(enchantArg);
                    }
                    if (socket == null || !socket.isEnabled() || !socket.supportsLimitBreak()) {
                        sender.sendMessage("§cEnchantment '" + enchantArg + "' was not found.");
                        return true;
                    }
                    bookItem = plugin.getLimitBreakManager().createSpecificBook(socket, amount);
                    plugin.getMessageManager().sendMessage(target, "messages.limitbreak-book-received",
                            "%amount%", String.valueOf(amount),
                            "%enchant%", socket.getDisplayName());
                    if (!target.equals(sender)) {
                        plugin.getMessageManager().sendMessage(sender, "messages.limitbreak-book-given",
                                "%amount%", String.valueOf(amount),
                                "%enchant%", socket.getDisplayName(),
                                "%player%", target.getName());
                    }
                }

                target.getInventory().addItem(bookItem);
            }

            case "menu", "gui" -> {
                if (!(sender instanceof Player player)) {
                    plugin.getMessageManager().sendMessage(sender, "messages.player-only");
                    return true;
                }
                if (!hasUse(player)) {
                    plugin.getMessageManager().sendMessage(player, "messages.no-permission");
                    return true;
                }
                InfinityPickaxe pickaxe = plugin.getPickaxeManager().getHeldPickaxe(player);
                if (pickaxe == null) {
                    plugin.getMessageManager().sendMessage(player, "messages.must-hold-pickaxe");
                    return true;
                }
                new MainPickaxeGui(plugin, player, pickaxe).open();
            }

            case "duplicate", "duplicates" -> handleDuplicate(sender, label, args);

            case "setlevel" -> {
                if (!(gearCommand ? hasPermissionOrAdmin(sender, "infinitygear.admin.setlevel") : hasAdmin(sender))) {
                    plugin.getMessageManager().sendMessage(sender, "messages.no-permission");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /" + label + " setlevel <player> <level>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    plugin.getMessageManager().sendMessage(sender, "messages.player-not-found");
                    return true;
                }
                if (com.infinitygear.mining.MiningXpItemProjection.isManaged(target.getInventory().getItemInMainHand())) {
                    sender.sendMessage("§cThis item's XP and level are managed by the mining ledger; legacy progression commands are unavailable.");
                    return true;
                }
                if (gearCommand) {
                    try {
                        int targetLevel = Integer.parseInt(args[2]);
                        var gear = plugin.getGearManager().inspect(target.getInventory().getItemInMainHand(), true).orElse(null);
                        var profile = gear == null ? null : plugin.getGearProfiles().find(gear.profileId()).orElse(null);
                        if (gear == null || profile == null) { sender.sendMessage("§cPlayer is not holding InfinityGear."); return true; }
                        gear.level(Math.min(profile.maximumLevel(), Math.max(0, targetLevel)));
                        com.infinitygear.data.GearData.save(gear, plugin.getDuplicateService().isRestricted(gear.uuid()),
                                com.infinitygear.data.GearData.LEGACY_PICKAXE_PROFILE.equals(gear.profileId()));
                        plugin.getGearManager().refreshPresentation(gear);
                        sender.sendMessage("§aGear level set to §f" + gear.level() + "§a.");
                    } catch (NumberFormatException invalid) { sender.sendMessage("§cInvalid level."); }
                    return true;
                }
                InfinityPickaxe pickaxe = plugin.getPickaxeManager().getHeldPickaxe(target);
                if (pickaxe == null) {
                    sender.sendMessage("§cPlayer is not holding an Infinity Pickaxe.");
                    return true;
                }
                try {
                    int targetLevel = Integer.parseInt(args[2]);
                    pickaxe.setLevel(targetLevel);
                    pickaxe.saveAndSync();
                    plugin.getMessageManager().sendMessage(sender, "messages.set-level-success",
                            "%level%", String.valueOf(targetLevel));
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cThe specified level is not a valid number.");
                }
            }

            case "addxp" -> {
                if (!(gearCommand ? hasPermissionOrAdmin(sender, "infinitygear.admin.addxp") : hasAdmin(sender))) {
                    plugin.getMessageManager().sendMessage(sender, "messages.no-permission");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /" + label + " addxp <player> <amount>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    plugin.getMessageManager().sendMessage(sender, "messages.player-not-found");
                    return true;
                }
                if (com.infinitygear.mining.MiningXpItemProjection.isManaged(target.getInventory().getItemInMainHand())) {
                    sender.sendMessage("§cThis item's XP and level are managed by the mining ledger; legacy progression commands are unavailable.");
                    return true;
                }
                if (gearCommand) {
                    try {
                        double amount = Double.parseDouble(args[2]);
                        var gear = plugin.getGearManager().inspect(target.getInventory().getItemInMainHand(), true).orElse(null);
                        var profile = gear == null ? null : plugin.getGearProfiles().find(gear.profileId()).orElse(null);
                        if (gear == null || profile == null) { sender.sendMessage("§cPlayer is not holding InfinityGear."); return true; }
                        if (profile.progressionMode() != com.infinitygear.gear.GearProgressionMode.EXPERIENCE) {
                            sender.sendMessage("§cThat profile does not use EXPERIENCE progression."); return true;
                        }
                        gear.xp(gear.xp() + Math.max(0, amount));
                        com.infinitygear.data.GearData.save(gear, plugin.getDuplicateService().isRestricted(gear.uuid()),
                                com.infinitygear.data.GearData.LEGACY_PICKAXE_PROFILE.equals(gear.profileId()));
                        plugin.getGearManager().refreshPresentation(gear);
                        sender.sendMessage("§aAdded §f" + amount + "§a gear XP.");
                    } catch (NumberFormatException invalid) { sender.sendMessage("§cInvalid XP amount."); }
                    return true;
                }
                InfinityPickaxe pickaxe = plugin.getPickaxeManager().getHeldPickaxe(target);
                if (pickaxe == null) {
                    sender.sendMessage("§cPlayer is not holding an Infinity Pickaxe.");
                    return true;
                }
                try {
                    double amount = Double.parseDouble(args[2]);
                    plugin.getLevelManager().addXp(pickaxe, amount, target);
                    plugin.getMessageManager().sendMessage(sender, "messages.add-xp-success",
                            "%xp%", String.format("%.0f", amount),
                            "%player%", target.getName());
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cThe specified XP amount is not a valid number.");
                }
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        sender.sendMessage("§b§lInfinityGear §7- Available Commands:");
        sender.sendMessage("§e/ipickaxe §7- Opens the menu for your held pickaxe.");
        if (hasAdmin(sender)) {
            sender.sendMessage("§e/ipickaxe give <player> [level] §7- Gives an Infinity Pickaxe.");
            sender.sendMessage("§e/ipickaxe book <enchant|universal> [amount] [player] §7- Gives LimitBreak books.");
            sender.sendMessage("§e/ipickaxe setlevel <player> <level> §7- Sets pickaxe level.");
            sender.sendMessage("§e/ipickaxe addxp <player> <amount> §7- Adds XP to pickaxe.");
            sender.sendMessage("§e/ipickaxe reload §7- Reloads configurations and menus.");
            sender.sendMessage("§e/ipickaxe duplicate §7- Duplicate detection and quarantine administration.");
        }
        sender.sendMessage("§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
    }

    private void handleDuplicate(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e/" + label + " duplicate <list|inspect|scan|quarantine|revoke|resolve|rekey-held>");
            return;
        }

        try {
            switch (args[1].toLowerCase()) {
                case "list" -> {
                    require(sender, "infinitypickaxes.admin.duplicates.view");
                    List<DuplicateRecord> records = plugin.getDuplicateService().listRestricted();
                    sender.sendMessage("§6Restricted tracked-item UUIDs: §f" + records.size());
                    records.stream().limit(20).forEach(record -> sender.sendMessage(
                            "§8- §f" + record.uuid() + " §7[§c" + record.status() + "§7] §8" + record.reason()));
                }
                case "inspect" -> {
                    require(sender, "infinitypickaxes.admin.duplicates.view");
                    UUID uuid = requireUuid(args, 2);
                    DuplicateRecord record = plugin.getDuplicateService().find(uuid).orElse(null);
                    if (record == null) {
                        sender.sendMessage("§aThat UUID has no duplicate restriction.");
                    } else {
                        sender.sendMessage("§6UUID: §f" + record.uuid());
                        sender.sendMessage("§6Status: §f" + record.status());
                        sender.sendMessage("§6Reason: §f" + record.reason());
                        sender.sendMessage("§6Last update: §f" + record.lastUpdated());
                        sender.sendMessage("§6Replacement: §f" + (record.replacementUuid() == null ? "none" : record.replacementUuid()));
                    }
                }
                case "scan" -> {
                    require(sender, "infinitypickaxes.admin.duplicates.scan");
                    DuplicateScanResult result;
                    if (args.length >= 3 && !args[2].equalsIgnoreCase("online")) {
                        Player target = Bukkit.getPlayer(args[2]);
                        if (target == null) throw new IllegalArgumentException("Player is not online.");
                        result = plugin.getDuplicateService().scanPlayer(target, sender.getName());
                    } else {
                        result = plugin.getDuplicateService().scanOnline(sender.getName());
                    }
                    sender.sendMessage("§aScanned §f" + result.itemsScanned() + "§a tracked items; detected §f"
                            + result.duplicatesDetected().size() + "§a compromised UUID(s).");
                }
                case "quarantine" -> {
                    require(sender, "infinitypickaxes.admin.duplicates.quarantine");
                    UUID uuid = requireUuid(args, 2);
                    plugin.getDuplicateService().quarantine(uuid, "Manual administrator quarantine", sender.getName());
                    sender.sendMessage("§eQuarantined tracked UUID §f" + uuid);
                }
                case "revoke" -> {
                    require(sender, "infinitypickaxes.admin.duplicates.resolve");
                    UUID uuid = requireUuid(args, 2);
                    plugin.getDuplicateService().revoke(uuid, "Manual administrator revocation", sender.getName());
                    sender.sendMessage("§cPermanently revoked tracked UUID §f" + uuid);
                }
                case "resolve", "rekey-held" -> {
                    require(sender, "infinitypickaxes.admin.duplicates.resolve");
                    if (!(sender instanceof Player player)) throw new IllegalArgumentException("A player must hold the canonical pickaxe.");
                    if (args[1].equalsIgnoreCase("resolve")
                            && (args.length < 3 || !args[2].equalsIgnoreCase("keep-held"))) {
                        throw new IllegalArgumentException("Use /" + label + " duplicate resolve keep-held while holding the canonical item.");
                    }
                    UUID replacement = plugin.getDuplicateService().rekeyHeld(player);
                    sender.sendMessage("§aThe held pickaxe is now canonical with UUID §f" + replacement
                            + "§a. Its previous UUID is permanently revoked.");
                }
                default -> sender.sendMessage("§cUnknown duplicate subcommand.");
            }
        } catch (SecurityException exception) {
            plugin.getMessageManager().sendMessage(sender, "messages.no-permission");
        } catch (Exception exception) {
            sender.sendMessage("§c" + exception.getMessage());
        }
    }

    private void require(CommandSender sender, String permission) {
        String generalized = permission.replace("infinitypickaxes", "infinitygear");
        if (!sender.hasPermission(permission) && !sender.hasPermission(generalized)
                && !hasAdmin(sender)) throw new SecurityException(permission);
    }

    private boolean hasUse(CommandSender sender) {
        return sender.hasPermission("infinitygear.use") || sender.hasPermission("infinitypickaxes.use");
    }

    private boolean hasAdmin(CommandSender sender) {
        return sender.hasPermission("infinitygear.admin") || sender.hasPermission("infinitypickaxes.admin");
    }

    private boolean hasPermissionOrAdmin(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || hasAdmin(sender);
    }

    private boolean handleGearGive(CommandSender sender, String label, String[] args) {
        if (!hasPermissionOrAdmin(sender, "infinitygear.admin.give")) { plugin.getMessageManager().sendMessage(sender, "messages.no-permission"); return true; }
        if (args.length < 3) { sender.sendMessage("§cUsage: /" + label + " give <profile> <player> [level]"); return true; }
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) { plugin.getMessageManager().sendMessage(sender, "messages.player-not-found"); return true; }
        int level = 0;
        if (args.length > 3) try { level = Integer.parseInt(args[3]); } catch (NumberFormatException ignored) { }
        try {
            ItemStack item = plugin.getGearManager().create(args[1], level);
            var leftovers = target.getInventory().addItem(item);
            if (!leftovers.isEmpty()) { sender.sendMessage("§cTarget inventory is full; no gear was issued."); return true; }
            sender.sendMessage("§aIssued §f" + args[1] + "§a to §f" + target.getName() + "§a.");
        } catch (IllegalArgumentException failure) { sender.sendMessage("§c" + failure.getMessage()); }
        return true;
    }

    private boolean handleArtifactGive(CommandSender sender, String label, String[] args) {
        if (!hasPermissionOrAdmin(sender, "infinitygear.admin.artifact")) { plugin.getMessageManager().sendMessage(sender, "messages.no-permission"); return true; }
        if (args.length < 3) { sender.sendMessage("§cUsage: /" + label + " artifact <runic_eraser|runic_conduit|runic_rivet> <player>"); return true; }
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) { plugin.getMessageManager().sendMessage(sender, "messages.player-not-found"); return true; }
        try {
            TrackedKind kind = TrackedKind.valueOf(args[1].toUpperCase(java.util.Locale.ROOT));
            var result = plugin.getGearService().createTrackedArtifact(kind, args[1].toLowerCase());
            if (!result.success()) { sender.sendMessage("§cCould not create artifact: " + result.reason()); return true; }
            if (!target.getInventory().addItem(result.value()).isEmpty()) { sender.sendMessage("§cTarget inventory is full."); return true; }
            sender.sendMessage("§aIssued tracked artifact §f" + kind + "§a to §f" + target.getName() + "§a.");
        } catch (IllegalArgumentException invalid) { sender.sendMessage("§cUnknown artifact kind."); }
        return true;
    }

    private boolean handleStation(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
        if (args.length < 2) { sender.sendMessage("§cUsage: /" + label + " station <type|bind type|unbind|status>"); return true; }
        try {
            String action = args[1].toLowerCase(java.util.Locale.ROOT);
            if (action.equals("bind")) {
                if (!hasPermissionOrAdmin(sender, "infinitygear.admin.station")) {
                    plugin.getMessageManager().sendMessage(sender, "messages.no-permission"); return true;
                }
                if (args.length < 3) { sender.sendMessage("§cUsage: /" + label + " station bind <type>"); return true; }
                StationType type = StationType.valueOf(args[2].replace('-', '_').toUpperCase(java.util.Locale.ROOT));
                org.bukkit.block.Block target = player.getTargetBlockExact(8);
                boolean bound = target != null && plugin.getStationManager().bind(type, player, target);
                if (!bound) bound = plugin.getStationManager().bindTargetedFurniture(type, player);
                if (!bound) {
                    if (plugin.getStationManager().beginFurnitureBinding(type, player)) {
                        sender.sendMessage("§eRight-click the matching Nexo furniture within 30 seconds to bind it.");
                        return true;
                    }
                    sender.sendMessage("§cTarget a block matching that station's configured provider/material.");
                    return true;
                }
                sender.sendMessage("§aBound this exact station instance as §f" + type.configKey() + "§a.");
                return true;
            }
            if (action.equals("unbind") || action.equals("status")) {
                if (!hasPermissionOrAdmin(sender, "infinitygear.admin.station")) {
                    plugin.getMessageManager().sendMessage(sender, "messages.no-permission"); return true;
                }
                org.bukkit.block.Block target = player.getTargetBlockExact(8);
                if (action.equals("status")) {
                    java.util.Optional<StationType> binding = target == null ? java.util.Optional.empty()
                            : plugin.getStationManager().boundType(target);
                    if (binding.isEmpty()) binding = plugin.getStationManager().targetedFurnitureBinding(player);
                    sender.sendMessage(binding
                            .map(type -> "§aBound InfinityGear station: §f" + type.configKey())
                            .orElse("§eThat target is not a registered InfinityGear station."));
                } else {
                    java.util.Optional<StationType> removed = target == null ? java.util.Optional.empty()
                            : plugin.getStationManager().unbind(target);
                    if (removed.isEmpty()) removed = plugin.getStationManager().unbindTargetedFurniture(player);
                    sender.sendMessage(removed
                            .map(type -> "§aUnbound InfinityGear station §f" + type.configKey() + "§a.")
                            .orElse("§eThat target was not registered."));
                }
                return true;
            }
            StationType type = StationType.valueOf(action.replace('-', '_').toUpperCase(java.util.Locale.ROOT));
            if (!hasPermissionOrAdmin(sender, "infinitygear.admin.station")
                    && !plugin.getStationManager().hasBypass(type, player)) {
                plugin.getMessageManager().sendMessage(sender, "messages.no-permission");
                return true;
            }
            new StationGui(plugin, player, type).open();
        } catch (IllegalArgumentException invalid) { sender.sendMessage("§cUnknown station."); }
        return true;
    }

    private UUID requireUuid(String[] args, int index) {
        if (args.length <= index) throw new IllegalArgumentException("A pickaxe UUID is required.");
        try {
            return UUID.fromString(args[index]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid pickaxe UUID.");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        boolean gearCommand = command.getName().equalsIgnoreCase("infinitygear");
        if (args.length == 1) {
            List<String> list = new ArrayList<>(gearCommand ? List.of() : Arrays.asList("menu", "gui"));
            if (hasAdmin(sender)) {
                list.addAll(gearCommand
                        ? Arrays.asList("give", "book", "artifact", "station", "reload", "setlevel", "addxp", "migration")
                        : Arrays.asList("give", "book", "reload", "setlevel", "addxp"));
                list.add("duplicate");
            }
            return list.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (gearCommand && args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return plugin.getGearProfiles().all().stream().map(com.infinitygear.gear.GearProfile::id)
                    .filter(id -> id.startsWith(args[1].toLowerCase())).toList();
        }
        if (gearCommand && args.length == 2 && args[0].equalsIgnoreCase("artifact")) {
            return List.of("runic_eraser", "runic_conduit", "runic_rivet");
        }
        if (gearCommand && args.length == 2 && args[0].equalsIgnoreCase("station")) {
            return List.of("runic-table", "fusion-altar", "gear-forge", "bind", "unbind", "status");
        }
        if (gearCommand && args.length == 3 && args[0].equalsIgnoreCase("station")
                && args[1].equalsIgnoreCase("bind")) {
            return List.of("runic-table", "fusion-altar", "gear-forge");
        }
        if (gearCommand && args.length == 3
                && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("artifact"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("duplicate")) {
            return Arrays.asList("list", "inspect", "scan", "quarantine", "revoke", "resolve", "rekey-held");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("duplicate") && args[1].equalsIgnoreCase("scan")) {
            List<String> targets = new ArrayList<>();
            targets.add("online");
            Bukkit.getOnlinePlayers().forEach(player -> targets.add(player.getName()));
            return targets;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("duplicate") && args[1].equalsIgnoreCase("resolve")) {
            return List.of("keep-held");
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("setlevel") || args[0].equalsIgnoreCase("addxp")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(p -> p.getName())
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("book") || args[0].equalsIgnoreCase("limitbreak")) {
                List<String> enchants = new ArrayList<>();
                enchants.add("universal");
                for (EnchantSocket s : plugin.getEnchantManager().getAllSockets()) {
                    if (s.isEnabled() && s.supportsLimitBreak()) enchants.add(s.getId());
                }
                return enchants.stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("give")) {
                return Arrays.asList("0", "10", "25", "50", "100");
            }
            if (args[0].equalsIgnoreCase("book") || args[0].equalsIgnoreCase("limitbreak")) {
                return Arrays.asList("1", "5", "10", "32", "64");
            }
        }
        if (args.length == 4 && (args[0].equalsIgnoreCase("book") || args[0].equalsIgnoreCase("limitbreak"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(p -> p.getName())
                    .filter(n -> n.toLowerCase().startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}

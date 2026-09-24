package com.infinitygear.acceptance;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.api.v1.MiningCreditDeliveryService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fixture-only no-reward subscriber. Its fsynced credit-ID files are durable decisions. */
public final class ArchiveCreditProbe extends JavaPlugin implements CommandExecutor {
    private enum Mode { ACCEPT, HOLD, DEFER, ERROR, HANG }

    private volatile Mode mode = Mode.ACCEPT;
    private ExecutorService worker;
    private MiningCreditDeliveryService service;
    private MiningCreditDeliveryService.Registration registration;
    private final ConcurrentHashMap<UUID, CompletableFuture<Boolean>> holds = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    @Override public void onEnable() {
        saveDefaultConfig();
        String world = getConfig().getString("fixture-world-uuid", "");
        UUID worldId;
        try { worldId = UUID.fromString(world); }
        catch (IllegalArgumentException invalid) { worldId = null; }
        if (!getConfig().getBoolean("enabled") || worldId == null || Bukkit.getWorld(worldId) == null) {
            getLogger().severe("Disabled: exact disposable world UUID and explicit enablement required");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ArchiveCreditProbe-decisions").factory());
        Objects.requireNonNull(getCommand("creditprobe")).setExecutor(this);
        getLogger().warning("DISPOSABLE CREDIT PROBE ACTIVE; decisions are durable NO_REWARD records only");
    }

    @Override public void onDisable() {
        stopped = true;
        holds.forEach((id, stage) -> stage.complete(false));
        holds.clear();
        if (registration != null) registration.unregister().whenComplete((ignored, failure) ->
                getLogger().info("CREDIT_PROBE_UNREGISTER_FENCE " + (failure == null ? "COMPLETE" : "FAILED")));
        registration = null;
        if (worker != null) worker.shutdown();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "register" -> {
                    if (registration != null) { sender.sendMessage("Already registered"); return true; }
                    service = getServer().getServicesManager().load(MiningCreditDeliveryService.class);
                    if (service == null) { sender.sendMessage("InfinityGear delivery service unavailable"); return true; }
                    registration = service.register(this::accept);
                    sender.sendMessage("CREDIT_PROBE_REGISTERED; activation is separate");
                }
                case "activate" -> {
                    if (service == null) service = getServer().getServicesManager().load(MiningCreditDeliveryService.class);
                    if (service == null) { sender.sendMessage("InfinityGear delivery service unavailable"); return true; }
                    service.activate().whenComplete((ignored, failure) ->
                            getLogger().info("CREDIT_PROBE_ACTIVATION " + (failure == null ? "COMMITTED" : "FAILED " + failure.getClass().getSimpleName())));
                    sender.sendMessage("Activation queued; wait for CREDIT_PROBE_ACTIVATION COMMITTED");
                }
                case "unregister" -> {
                    if (registration == null) { sender.sendMessage("Not registered"); return true; }
                    var old = registration; registration = null;
                    old.unregister().whenComplete((ignored, failure) ->
                            getLogger().info("CREDIT_PROBE_UNREGISTER_FENCE " + (failure == null ? "COMPLETE" : "FAILED")));
                    sender.sendMessage("Unregister queued; wait for fence log");
                }
                case "mode" -> {
                    if (args.length != 2) return usage(sender);
                    mode = Mode.valueOf(args[1].toUpperCase(Locale.ROOT));
                    sender.sendMessage("CREDIT_PROBE_MODE " + mode);
                }
                case "release" -> {
                    int count = holds.size();
                    holds.forEach((id, stage) -> { if (holds.remove(id, stage)) stage.complete(true); });
                    sender.sendMessage("Released " + count + " held acceptance(s)");
                }
                case "status" -> {
                    long count = Files.isDirectory(decisionDir())
                            ? countDecisions() : 0;
                    sender.sendMessage("CREDIT_PROBE_STATUS registered=" + (registration != null)
                            + " mode=" + mode + " decisions=" + count + " held=" + holds.size());
                }
                default -> { return usage(sender); }
            }
        } catch (RuntimeException | IOException failure) {
            sender.sendMessage("Credit probe command failed: " + failure.getClass().getSimpleName());
            getLogger().warning("CREDIT_PROBE_COMMAND_FAILED " + failure.getClass().getSimpleName());
        }
        return true;
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage("/creditprobe <register|activate|unregister|mode accept|hold|defer|error|hang|release|status>");
        return true;
    }

    private CompletionStage<Boolean> accept(MiningCredit credit) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Probe callback must run on the server thread");
        if (stopped) return CompletableFuture.completedFuture(false);
        Mode selected = mode;
        getLogger().info("CREDIT_PROBE_RECEIVED " + credit.creditId() + " mode=" + selected);
        return switch (selected) {
            case DEFER -> CompletableFuture.completedFuture(false);
            case ERROR -> CompletableFuture.failedFuture(new IllegalStateException("Intentional fixture error"));
            case HANG -> new CompletableFuture<>();
            case ACCEPT, HOLD -> CompletableFuture.supplyAsync(() -> {
                try {
                    boolean inserted = persist(credit);
                    getLogger().info("CREDIT_PROBE_DECISION_COMMITTED " + credit.creditId()
                            + " inserted=" + inserted + " NO_REWARD");
                    return true;
                } catch (IOException | RuntimeException failure) {
                    getLogger().warning("CREDIT_PROBE_DECISION_FAILED " + credit.creditId()
                            + " " + failure.getClass().getSimpleName());
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, worker).thenCompose(saved -> {
                if (stopped) return CompletableFuture.completedFuture(false);
                if (selected == Mode.ACCEPT) return CompletableFuture.completedFuture(true);
                var hold = new CompletableFuture<Boolean>();
                holds.put(credit.creditId(), hold);
                getLogger().info("CREDIT_PROBE_ACCEPTANCE_HELD " + credit.creditId());
                return hold;
            });
        };
    }

    private boolean persist(MiningCredit credit) throws IOException {
        Path dir = decisionDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(credit.creditId() + ".bin");
        byte[] bytes = encode(credit);
        if (Files.exists(target)) {
            if (!Arrays.equals(bytes, Files.readAllBytes(target)))
                throw new IllegalArgumentException("Credit-ID payload conflict: " + credit.creditId());
            return false;
        }
        Path temporary = Files.createTempFile(dir, credit.creditId() + "-", ".pending");
        try {
            try (var out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            try (var directory = FileChannel.open(dir, StandardOpenOption.READ)) { directory.force(true); }
            return true;
        } finally { Files.deleteIfExists(temporary); }
    }

    private static byte[] encode(MiningCredit credit) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.write("ArchiveCreditProbe/NO_REWARD/v1".getBytes(StandardCharsets.US_ASCII));
        uuid(out, credit.creditId()); uuid(out, credit.instanceId()); uuid(out, credit.playerId());
        uuid(out, credit.pickaxeId()); string(out, credit.profileId()); uuid(out, credit.worldId());
        out.writeInt(credit.x()); out.writeInt(credit.y()); out.writeInt(credit.z());
        string(out, credit.originalBlockData()); string(out, credit.source().name());
        string(out, credit.generation()); out.writeByte(credit.legitimate() ? 1 : 0);
        out.writeByte(credit.successful() ? 1 : 0);
        out.flush();
        return bytes.toByteArray();
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static void string(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private Path decisionDir() { return getDataFolder().toPath().resolve("decisions"); }
    private long countDecisions() throws IOException {
        try (var files = Files.list(decisionDir())) { return files.filter(p -> p.getFileName().toString().endsWith(".bin")).count(); }
    }
}

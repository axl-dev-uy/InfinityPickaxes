package com.infinitygear.mining;

import com.infinitygear.api.v1.MiningAuthority;
import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.api.v1.MiningIncidentService;
import com.infinitygear.gear.GearProgressionMode;
import com.infinitygear.integration.IntegrationTasks;
import com.infinitygear.persistence.MariaMiningXpLedger;
import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Production, nonblocking server-thread handoff from an authoritative mining producer. */
public final class MiningCompletionReceiver implements MiningAuthority.Receiver, AutoCloseable {
    record Loaded(MiningXpPlan.Account account, MariaMiningXpLedger.Adoption adoption,
                  MariaMiningXpLedger.Receipt receipt) { }
    record Prepared(MiningCredit credit, MiningXpPlan plan, MiningCompletion facts,
                    MiningIncidentService.Evidence evidence) { }
    private static final class Omission extends RuntimeException {
        private final String stage;
        Omission(String stage, String message) { super(message); this.stage = stage; }
    }

    private final InfinityPickaxes plugin;
    private final IntegrationTasks tasks;
    private final MariaMiningXpLedger ledger;
    private final MiningIncidentService incidents;
    private final XpItemCustody custody;
    private final MiningXpParticipant participant;
    private final BooleanSupplier integrationActive;
    private final AtomicLong epoch = new AtomicLong();
    private volatile boolean closed;

    public MiningCompletionReceiver(InfinityPickaxes plugin, IntegrationTasks tasks, MariaMiningXpLedger ledger,
                                    MiningIncidentService incidents, BooleanSupplier integrationActive) {
        this.plugin = Objects.requireNonNull(plugin);
        this.tasks = Objects.requireNonNull(tasks);
        this.ledger = Objects.requireNonNull(ledger);
        this.incidents = Objects.requireNonNull(incidents);
        this.integrationActive = Objects.requireNonNull(integrationActive);
        custody = new XpItemCustody(plugin);
        participant = new MiningXpParticipant(tasks, ledger, custody::projectable, incidents);
    }

    /** Accept means that this one-shot completion was queued. It does not promise an XP award. */
    @Override public boolean accept(com.infinitygear.api.v1.MiningCompletion completion) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Mining completion handoff requires the server thread");
        Objects.requireNonNull(completion);
        if (closed || !integrationActive.getAsBoolean() || !completion.confirmed() || !producerCurrent(completion)) return false;
        long acceptedEpoch = epoch.get();
        CompletableFuture<Loaded> load = tasks.database(() -> new Loaded(
                ledger.findAccount(completion.itemId()).orElse(null),
                ledger.findAdoption(completion.itemId()).orElse(null),
                ledger.findReceipt(completion.completionId()).orElse(null)));
        if (load.isCompletedExceptionally()) return false;
        load.thenCompose(loaded -> loaded.receipt() == null
                        ? tasks.server(() -> prepare(completion, loaded, acceptedEpoch))
                            .thenCompose(prepared -> participant.complete(prepared.credit(), prepared.plan(),
                                    prepared.facts(), prepared.evidence()).thenApply(ignored -> true))
                        : tasks.server(() -> validateReceiptReplay(completion, loaded.receipt(), acceptedEpoch))
                            .thenCompose(receipt -> participant.recoverOperation(receipt.credit(), receipt.plan()))
                            .thenApply(ignored -> true))
                .whenComplete((outcome, failure) -> {
                    if (failure == null) return;
                    Throwable cause = unwrap(failure);
                    if (cause instanceof Omission omission) record(completion, omission.stage, omission.getMessage());
                    else plugin.getLogger().warning("Confirmed mining credit " + completion.completionId()
                            + " failed closed (" + cause.getClass().getSimpleName() + "); inspect mining incidents");
                });
        return true;
    }

    private MariaMiningXpLedger.Receipt validateReceiptReplay(com.infinitygear.api.v1.MiningCompletion completion,
                                                               MariaMiningXpLedger.Receipt receipt, long acceptedEpoch) {
        if (closed || !integrationActive.getAsBoolean() || epoch.get() != acceptedEpoch || !producerCurrent(completion))
            throw new Omission("RELOAD_OR_SHUTDOWN", "Integration or producer changed before receipt recovery");
        MiningCredit replay = credit(completion, receipt.credit().profileId());
        if (!receipt.credit().equals(replay))
            throw new Omission("CONFLICTING_REPLAY", "Existing credit receipt has different immutable completion data");
        return receipt;
    }

    Prepared prepare(com.infinitygear.api.v1.MiningCompletion completion, Loaded loaded, long acceptedEpoch) {
        if (closed || !integrationActive.getAsBoolean() || epoch.get() != acceptedEpoch)
            throw new Omission("RELOAD_OR_SHUTDOWN", "Integration lifecycle changed after completion handoff");
        if (!producerCurrent(completion))
            throw new Omission("PRODUCER_DRIFT", "Producer service, revision, or supported path changed after handoff");
        if (loaded.account() == null || loaded.adoption() == null || !loaded.adoption().account().equals(loaded.account()))
            throw new Omission("ACCOUNT", "No matching explicitly adopted XP account");

        XpItemCustody.Located located = custody.locate(completion.itemId());
        if (located.owner() == null || !located.owner().getUniqueId().equals(completion.playerId())
                || !located.uniqueMainHand(located.owner(), completion.itemId()))
            throw new Omission("CUSTODY", "Completion item is not the player's one uniquely held visible copy");
        if (located.quarantined() || plugin.getDuplicateService().isRestricted(completion.itemId()))
            throw new Omission("CUSTODY", "Completion item is quarantined or restricted");
        if (!MiningXpItemProjection.matchesAccount(located.item(), loaded.account()))
            throw new Omission("REVISION", "Physical item does not match the authoritative account revision");
        if (!located.owner().hasPermission("infinitypickaxes.use"))
            throw new Omission("PLAYER_POLICY", "Player lacks the mining progression permission");
        if (located.owner().getGameMode() == GameMode.CREATIVE && plugin.getConfigManager().getConfig()
                .getBoolean("anti-exploit.ignore-creative", true))
            throw new Omission("PLAYER_POLICY", "Creative mining progression is disabled");

        var gear = plugin.getGearManager().inspect(located.item(), false).orElse(null);
        var profile = gear == null ? null : plugin.getGearProfiles().find(gear.profileId()).orElse(null);
        if (gear == null || profile == null || !profile.enabled()
                || profile.progressionMode() != GearProgressionMode.EXPERIENCE
                || !gear.uuid().equals(completion.itemId()) || !profile.id().equals(loaded.account().profileId()))
            throw new Omission("PROFILE", "Completion item/profile is unavailable or not XP-managed");

        Material material = material(completion.originalBlockData());
        if (material == null || material.isAir()) throw new Omission("XP_POLICY", "Original block data has no supported material");
        double base = plugin.getConfigManager().getBlocksConfig().getDouble("blocks." + material.name(),
                plugin.getConfigManager().getBlocksConfig().getDouble("default-xp", 1.0));
        Double multiplier = profile.xpSources().get("BLOCK_BREAK");
        double amount = multiplier == null ? Double.NaN : base * multiplier;
        if (!Double.isFinite(amount) || amount <= 0)
            throw new Omission("XP_POLICY", "Profile has no positive finite BLOCK_BREAK XP policy");
        if (completion.generationLiteral().length() > 512)
            throw new Omission("ATTRIBUTION", "Mine-generation attribution exceeds the durable credit limit");
        List<Double> requirements = new ArrayList<>(profile.maximumLevel());
        for (int level = 0; level < profile.maximumLevel(); level++) {
            double required = plugin.getLevelManager().getRequiredXp(level);
            if (!Double.isFinite(required) || required <= 0)
                throw new Omission("XP_POLICY", "Progression requirement is invalid at level " + level);
            requirements.add(required);
        }
        MiningCredit credit = credit(completion, profile.id());
        return new Prepared(credit, new MiningXpPlan(completion.itemId(), profile.id(), loaded.account().revision(),
                loaded.account().progress(), amount, requirements), facts(completion, true),
                evidence(completion, "XP_SUBMISSION", "AUTHORITATIVE_COMPLETION"));
    }

    static MiningCredit credit(com.infinitygear.api.v1.MiningCompletion completion, String profileId) {
        return new MiningCredit(completion.completionId(), completion.instanceId(), completion.playerId(),
                completion.itemId(), profileId, completion.worldId(), completion.x(), completion.y(), completion.z(),
                completion.originalBlockData(), completion.source(), completion.generationLiteral(), true, completion.confirmed());
    }

    static MiningCompletion facts(com.infinitygear.api.v1.MiningCompletion completion, boolean authoritative) {
        return new MiningCompletion(authoritative, completion.returnedSuccess(), completion.acceptedReplacement(),
                completion.finalAir(), completion.generationMatches(), completion.placementMatches(),
                completion.mutationUnchanged(), completion.producerException());
    }

    static MiningIncidentService.Evidence evidence(com.infinitygear.api.v1.MiningCompletion completion,
                                                    String stage, String reason) {
        String producer = "operation=" + completion.operationId() + "; instance=" + completion.instanceId()
                + "; source=" + completion.source() + "; return=" + completion.returnedSuccess()
                + "; replacement=" + completion.acceptedReplacement() + "; finalAir=" + completion.finalAir()
                + "; generationMatches=" + completion.generationMatches() + "; placementMatches="
                + completion.placementMatches() + "; mutationUnchanged=" + completion.mutationUnchanged()
                + "; producerException=" + completion.producerException();
        return new MiningIncidentService.Evidence(completion.completionId(), completion.playerId(), completion.itemId(),
                completion.mine(), completion.generationId(), completion.worldId(), completion.x(), completion.y(),
                completion.z(), completion.originalBlockData(), completion.placement(), producer, stage, reason,
                completion.observedAt(), completion.versions(), completion.configurationRevision());
    }

    private boolean producerCurrent(com.infinitygear.api.v1.MiningCompletion completion) {
        try {
            MiningAuthority authority = plugin.getServer().getServicesManager().load(MiningAuthority.class);
            MiningAuthority.Path path = path(completion.source());
            MiningAuthority.Capability capability = authority == null || path == null ? null : authority.capabilities().get(path);
            return capability != null && capability.supported()
                    && completion.configurationRevision().equals(authority.providerRevision());
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static MiningAuthority.Path path(MiningCredit.Source source) {
        return switch (source) {
            case NORMAL -> MiningAuthority.Path.ORDINARY;
            case BLAST_MINING -> MiningAuthority.Path.BLAST_MINING;
            case DYNAMITE -> MiningAuthority.Path.DYNAMITE;
            case VEIN_MINER -> MiningAuthority.Path.VEIN_MINER;
            case OTHER -> null;
        };
    }

    private static Material material(String blockData) {
        String key = blockData.split("\\[", 2)[0];
        return Material.matchMaterial(key);
    }

    private void record(com.infinitygear.api.v1.MiningCompletion completion, String stage, String reason) {
        try {
            incidents.recordVoided(evidence(completion, stage, reason)).exceptionally(failure -> {
                plugin.getLogger().warning("Could not persist omitted mining completion " + completion.completionId());
                return false;
            });
        } catch (RuntimeException unavailable) {
            plugin.getLogger().warning("Could not enqueue incident for omitted mining completion " + completion.completionId());
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }

    /** Invalidates completions already queued across an InfinityGear reload boundary. */
    public void reload() { epoch.incrementAndGet(); }
    public boolean active() { return !closed; }
    @Override public void close() { closed = true; epoch.incrementAndGet(); }
}

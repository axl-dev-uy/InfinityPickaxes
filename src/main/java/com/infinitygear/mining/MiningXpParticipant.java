package com.infinitygear.mining;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.integration.IntegrationTasks;
import com.infinitygear.persistence.MariaMiningXpLedger;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Internal commit/projection orchestration, not a production mining service.
 * The server-thread resolver must find the unique, currently held-in-custody item or return null.
 * A successful DB commit followed by absent/conflicting item state remains recoverable by revision.
 * No notification or additional XP mutation is performed during projection/recovery. */
public final class MiningXpParticipant {
    public record Outcome(MariaMiningXpLedger.Receipt receipt, MiningXpItemProjection.Result projection) { }
    private final IntegrationTasks tasks;
    private final MariaMiningXpLedger ledger;
    private final Function<UUID, ItemStack> itemResolver;
    private final MiningXpItemProjection projection = new MiningXpItemProjection();
    private final com.infinitygear.api.v1.MiningIncidentService incidents;

    public MiningXpParticipant(IntegrationTasks tasks, MariaMiningXpLedger ledger, Function<UUID, ItemStack> itemResolver) {
        this(tasks, ledger, itemResolver, null);
    }
    public MiningXpParticipant(IntegrationTasks tasks, MariaMiningXpLedger ledger, Function<UUID, ItemStack> itemResolver,
                               com.infinitygear.api.v1.MiningIncidentService incidents) {
        this.tasks = Objects.requireNonNull(tasks); this.ledger = Objects.requireNonNull(ledger);
        this.itemResolver = Objects.requireNonNull(itemResolver);
        this.incidents = incidents;
    }
    /** Internal trusted-confirmation entry. Never call for raw events or diagnostic observations. */
    public CompletableFuture<Outcome> apply(MiningCredit credit, MiningXpPlan plan) {
        return commit(() -> ledger.apply(credit, plan), credit, plan).thenCompose(this::project);
    }
    public CompletableFuture<Optional<Outcome>> complete(MiningCredit credit, MiningXpPlan plan, MiningCompletion completion,
                                                         com.infinitygear.api.v1.MiningIncidentService.Evidence evidence) {
        if (!completion.confirmed() || !credit.successful() || !credit.legitimate()) {
            if (incidents == null) return CompletableFuture.failedFuture(new IllegalStateException("Incident recorder required"));
            return incidents.recordVoided(evidence).handle((saved, failure) -> Optional.<Outcome>empty()).toCompletableFuture();
        }
        return commit(() -> ledger.apply(credit, plan, evidence), credit, plan).handle((receipt, failure) -> {
            if (failure == null) return project(receipt).thenApply(Optional::of);
            if (incidents == null) return CompletableFuture.<Optional<Outcome>>failedFuture(failure);
            return incidents.recordVoided(evidence).handle((saved, auditFailure) -> {
                throw new java.util.concurrent.CompletionException(failure);
            }).thenApply(unused -> Optional.<Outcome>empty()).toCompletableFuture();
        }).thenCompose(result -> result);
    }
    private CompletableFuture<MariaMiningXpLedger.Receipt> commit(java.util.concurrent.Callable<MariaMiningXpLedger.Receipt> work,
                                                                  MiningCredit credit, MiningXpPlan plan) {
        return tasks.database(() -> {
            try { return work.call(); }
            catch (Exception failure) {
                // A lost commit response is resolved only by a receipt lookup, NEVER by apply.
                var receipt = ledger.recoverOperation(credit, plan);
                if (receipt.isPresent()) return receipt.get();
                throw failure;
            }
        });
    }
    public CompletableFuture<Optional<Outcome>> recoverOperation(MiningCredit credit, MiningXpPlan plan) {
        return tasks.database(() -> ledger.recoverOperation(credit, plan)).thenCompose(receipt ->
                receipt.isEmpty() ? CompletableFuture.completedFuture(Optional.empty()) : project(receipt.get()).thenApply(Optional::of));
    }
    public CompletableFuture<Optional<Outcome>> recover(UUID pickaxeId, long receiptRevision) {
        return tasks.database(() -> ledger.findReceipt(pickaxeId, receiptRevision)).thenCompose(receipt ->
                receipt.isEmpty() ? CompletableFuture.completedFuture(Optional.empty())
                        : project(receipt.get()).thenApply(Optional::of));
    }
    private CompletableFuture<Outcome> project(MariaMiningXpLedger.Receipt receipt) {
        return tasks.server(() -> new Outcome(receipt,
                projection.apply(itemResolver.apply(receipt.account().pickaxeId()), receipt)));
    }
}

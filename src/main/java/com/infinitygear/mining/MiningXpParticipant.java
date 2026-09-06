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

    public MiningXpParticipant(IntegrationTasks tasks, MariaMiningXpLedger ledger, Function<UUID, ItemStack> itemResolver) {
        this.tasks = Objects.requireNonNull(tasks); this.ledger = Objects.requireNonNull(ledger);
        this.itemResolver = Objects.requireNonNull(itemResolver);
    }
    public CompletableFuture<Outcome> apply(MiningCredit credit, MiningXpPlan plan) {
        return tasks.database(() -> ledger.apply(credit, plan)).thenCompose(this::project);
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

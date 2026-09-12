package com.infinitygear.integration;

import com.infinitygear.api.v1.*;
import java.util.concurrent.CompletionStage;

/** Separates database recovery from Bukkit materialization so saved rewards survive provider outages. */
public final class IssuanceRecovery {
    public interface Materializer {
        /** Called on the server thread for NEW issuance only. */
        void validateNew(BookLedger.Issue request) throws Exception;
        /** Called on the server thread only if no artifact was saved before the interruption. */
        byte[] create(BookLedger.Receipt receipt) throws Exception;
    }
    private final BookLedger ledger;
    private final BookArtifacts artifacts;
    private final IntegrationTasks tasks;
    private final Materializer materializer;
    public IssuanceRecovery(BookLedger ledger, BookArtifacts artifacts, IntegrationTasks tasks, Materializer materializer) {
        this.ledger = ledger; this.artifacts = artifacts; this.tasks = tasks; this.materializer = materializer;
    }
    public CompletionStage<BookIssuanceService.IssuedBook> issue(BookLedger.Issue request,
                                                               BookIssuanceService.ProvenanceAuthority authority) {
        return tasks.database(() -> ledger.find(request.operationId(), request.rewardId())).thenCompose(existing -> {
            if (existing.isPresent()) return recover(request, existing.get());
            if (authority == null) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Provenance authority unavailable for new issuance"));
            return tasks.server(() -> { materializer.validateNew(request); return null; })
                    .thenCompose(ignored -> tasks.database(() -> {
                        if (!authority.validate(request)) throw new IllegalArgumentException("Provenance rejected");
                        return ledger.issue(request);
                    })).thenCompose(receipt -> recover(request, receipt));
        });
    }
    private CompletionStage<BookIssuanceService.IssuedBook> recover(BookLedger.Issue request, BookLedger.Receipt receipt) {
        if (!request.equals(receipt.issue()) || receipt.consumed())
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Issuance payload mismatch or consumed identity"));
        return tasks.database(() -> artifacts.load(receipt.bookId())).thenCompose(saved -> {
            if (saved.isPresent()) return java.util.concurrent.CompletableFuture.completedFuture(new BookIssuanceService.IssuedBook(receipt, saved.get()));
            return tasks.server(() -> materializer.create(receipt))
                    .thenCompose(bytes -> tasks.database(() -> artifacts.saveFirst(receipt, bytes)))
                    .thenApply(bytes -> new BookIssuanceService.IssuedBook(receipt, bytes));
        });
    }
}

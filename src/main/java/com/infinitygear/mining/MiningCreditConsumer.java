package com.infinitygear.mining;

import com.infinitygear.api.v1.CreditedBlockEvent;
import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.integration.IntegrationTasks;
import com.infinitygear.persistence.MariaMiningCreditInbox;
import org.bukkit.Bukkit;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Durable local consumer boundary. The Bukkit event is observation after inbox commit, never the acknowledgement. */
public final class MiningCreditConsumer implements MiningNotificationDispatcher.Receiver, AutoCloseable {
    private final IntegrationTasks tasks;
    private final MariaMiningCreditInbox inbox;
    private volatile boolean closed;

    public MiningCreditConsumer(IntegrationTasks tasks, MariaMiningCreditInbox inbox) {
        this.tasks = Objects.requireNonNull(tasks);
        this.inbox = Objects.requireNonNull(inbox);
    }

    @Override public CompletionStage<Boolean> accept(MiningCredit credit) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Credit delivery requires the server thread");
        if (closed) return java.util.concurrent.CompletableFuture.completedFuture(false);
        return tasks.database(() -> {
            if (closed) throw new IllegalStateException("Mining credit consumer stopped");
            return inbox.accept(credit); // acceptance cannot become true before this transaction commits
        }).thenCompose(accepted -> {
            if (!accepted.inserted()) return java.util.concurrent.CompletableFuture.completedFuture(true);
            return tasks.server(() -> {
                if (closed) throw new IllegalStateException("Mining credit consumer stopped after commit");
                Bukkit.getPluginManager().callEvent(new CreditedBlockEvent(accepted.credit()));
                return true;
            });
        });
    }

    public boolean active() { return !closed; }
    @Override public void close() { closed = true; }
}

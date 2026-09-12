package com.infinitygear.mining;

import com.infinitygear.api.v1.MiningIncidentService;
import com.infinitygear.integration.IntegrationTasks;
import com.infinitygear.persistence.MariaMiningJournal;
import java.util.Objects;
import java.util.concurrent.*;

public final class MiningIncidentRecorder implements MiningIncidentService {
    private final IntegrationTasks tasks;
    private final MariaMiningJournal journal;
    private final MiningIncidentAlerts alerts;
    public MiningIncidentRecorder(IntegrationTasks tasks, MariaMiningJournal journal, MiningIncidentAlerts alerts) {
        this.tasks = Objects.requireNonNull(tasks); this.journal = Objects.requireNonNull(journal); this.alerts = Objects.requireNonNull(alerts);
    }
    @Override public CompletionStage<Boolean> recordVoided(Evidence evidence) {
        return tasks.database(() -> {
            journal.recordVoided(evidence);
            var saved = journal.incident(evidence.operationId()).orElseThrow();
            if (!saved.evidence().equals(evidence)) throw new IllegalArgumentException("Incident replay evidence mismatch");
            return journal.claimIncidentAlert(evidence.operationId()) ? saved : null;
        }).thenCompose(incident -> incident == null ? CompletableFuture.completedFuture(true)
                : tasks.server(() -> { alerts.persisted(incident); return true; }).exceptionally(ignored -> true));
    }
}

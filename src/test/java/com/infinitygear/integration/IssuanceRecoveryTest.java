package com.infinitygear.integration;

import com.infinitygear.api.v1.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IssuanceRecoveryTest {
    private BookLedger.Issue request() {
        return new BookLedger.Issue(UUID.randomUUID(), UUID.randomUUID(), "minecraft:fortune", 1, "archive:reward", BigDecimal.ONE);
    }
    @Test void savedArtifactRecoversWithoutNativeMetadataOrProvenanceProvider() throws Exception {
        var ledger = mock(BookLedger.class); var artifacts = mock(BookArtifacts.class);
        var materializer = mock(IssuanceRecovery.Materializer.class);
        var request = request(); var receipt = new BookLedger.Receipt(UUID.randomUUID(), request, false);
        when(ledger.find(request.operationId(), request.rewardId())).thenReturn(Optional.of(receipt));
        when(artifacts.load(receipt.bookId())).thenReturn(Optional.of(new byte[]{1,2,3}));
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), Runnable::run)) {
            var result = new IssuanceRecovery(ledger, artifacts, tasks, materializer).issue(request, null).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(receipt, result.receipt()); assertArrayEquals(new byte[]{1,2,3}, result.serializedItem());
            verifyNoInteractions(materializer); verify(ledger, never()).issue(any());
        }
    }
    @Test void missingArtifactFinishesExistingIdentityAndReturnsPersistedWinner() throws Exception {
        var ledger = mock(BookLedger.class); var artifacts = mock(BookArtifacts.class);
        var materializer = mock(IssuanceRecovery.Materializer.class);
        var request = request(); var receipt = new BookLedger.Receipt(UUID.randomUUID(), request, false);
        when(ledger.find(request.operationId(), request.rewardId())).thenReturn(Optional.of(receipt));
        when(artifacts.load(receipt.bookId())).thenReturn(Optional.empty());
        byte[] candidate = {3}; when(materializer.create(receipt)).thenReturn(candidate);
        when(artifacts.saveFirst(receipt, candidate)).thenReturn(new byte[]{7});
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), Runnable::run)) {
            var result = new IssuanceRecovery(ledger, artifacts, tasks, materializer).issue(request, null).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{7}, result.serializedItem());
            verify(materializer, never()).validateNew(any()); verify(ledger, never()).issue(any());
        }
    }
    @Test void newIssuanceRequiresAuthorityAndPersistsBeforeReturning() throws Exception {
        var ledger = mock(BookLedger.class); var artifacts = mock(BookArtifacts.class);
        var materializer = mock(IssuanceRecovery.Materializer.class);
        var authority = mock(BookIssuanceService.ProvenanceAuthority.class);
        var request = request(); var receipt = new BookLedger.Receipt(UUID.randomUUID(), request, false);
        when(ledger.find(request.operationId(), request.rewardId())).thenReturn(Optional.empty());
        when(authority.validate(request)).thenReturn(true); when(ledger.issue(request)).thenReturn(receipt);
        when(artifacts.load(receipt.bookId())).thenReturn(Optional.empty());
        byte[] bytes = {5}; when(materializer.create(receipt)).thenReturn(bytes); when(artifacts.saveFirst(receipt, bytes)).thenReturn(bytes);
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), Runnable::run)) {
            var recovery = new IssuanceRecovery(ledger, artifacts, tasks, materializer);
            assertThrows(ExecutionException.class, () -> recovery.issue(request, null).toCompletableFuture().get(5, TimeUnit.SECONDS));
            verifyNoInteractions(materializer);
            assertArrayEquals(bytes, recovery.issue(request, authority).toCompletableFuture().get(5, TimeUnit.SECONDS).serializedItem());
            var order = inOrder(authority, ledger, materializer, artifacts);
            order.verify(materializer).validateNew(request); order.verify(authority).validate(request);
            order.verify(ledger).issue(request); order.verify(artifacts).load(receipt.bookId());
            order.verify(materializer).create(receipt); order.verify(artifacts).saveFirst(receipt, bytes);
        }
    }
    @Test void consumedOrMismatchedRequestNeverLoadsArtifact() throws Exception {
        var ledger = mock(BookLedger.class); var artifacts = mock(BookArtifacts.class);
        var materializer = mock(IssuanceRecovery.Materializer.class); var request = request();
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), Runnable::run)) {
            var recovery = new IssuanceRecovery(ledger, artifacts, tasks, materializer);
            for (var receipt : List.of(new BookLedger.Receipt(UUID.randomUUID(), request, true),
                    new BookLedger.Receipt(UUID.randomUUID(), request(), false))) {
                when(ledger.find(request.operationId(), request.rewardId())).thenReturn(Optional.of(receipt));
                assertThrows(ExecutionException.class, () -> recovery.issue(request, null).toCompletableFuture().get(5, TimeUnit.SECONDS));
            }
            verifyNoInteractions(artifacts, materializer);
        }
    }
}

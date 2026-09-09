package com.infinitygear.mining;

import com.infinitygear.api.v1.MiningAuthority;
import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.api.v1.MiningIncidentService;
import com.infinitygear.integration.IntegrationTasks;
import com.infinitygear.persistence.MariaMiningXpLedger;
import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiningCompletionReceiverTest {
    private com.infinitygear.api.v1.MiningCompletion completion() {
        return new com.infinitygear.api.v1.MiningCompletion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "acceptance", UUID.randomUUID(),
                3, 64, -2, "minecraft:deepslate[axis=y]", MiningCredit.Source.NORMAL, "NATURAL",
                true, true, true, true, true, true, false, Instant.now(),
                Map.of("AxMines", "1.8.0", "Paper", "26.2"), "axmines:fence:7");
    }

    @Test void mappingPreservesStablePhysicalAndProducerEvidence() {
        var completion = completion();
        MiningCredit credit = MiningCompletionReceiver.credit(completion, "infinitygear:pickaxe");
        assertEquals(completion.completionId(), credit.creditId());
        assertEquals(completion.instanceId(), credit.instanceId());
        assertEquals(completion.itemId(), credit.pickaxeId());
        assertEquals(completion.generationLiteral(), credit.generation());
        assertEquals(completion.source(), credit.source());
        assertEquals(List.of(completion.x(), completion.y(), completion.z()), List.of(credit.x(), credit.y(), credit.z()));
        assertTrue(MiningCompletionReceiver.facts(completion, true).confirmed());
        var evidence = MiningCompletionReceiver.evidence(completion, "XP_SUBMISSION", "AUTHORITATIVE_COMPLETION");
        assertEquals(completion.completionId(), evidence.operationId());
        assertEquals(completion.generationId(), evidence.generationId());
        assertTrue(evidence.producerEvidence().contains(completion.operationId().toString()));
        assertEquals(completion.versions(), evidence.versions());
    }

    @Test void serverThreadAcceptOnlyQueuesAndShutdownVoidsQueuedWorkWithoutXp() throws Exception {
        var worker = new QueuedExecutor();
        var plugin = mock(InfinityPickaxes.class);
        var server = mock(Server.class);
        var services = mock(ServicesManager.class);
        var authority = mock(MiningAuthority.class);
        var ledger = mock(MariaMiningXpLedger.class);
        var incidents = mock(MiningIncidentService.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(services);
        when(services.load(MiningAuthority.class)).thenReturn(authority);
        when(authority.providerRevision()).thenReturn("axmines:fence:7");
        when(authority.capabilities()).thenReturn(Map.of(MiningAuthority.Path.ORDINARY,
                new MiningAuthority.Capability(true, "approved")));
        when(incidents.recordVoided(any())).thenReturn(CompletableFuture.completedFuture(true));
        try (var bukkit = mockStatic(Bukkit.class);
             var tasks = new IntegrationTasks(worker, Runnable::run)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var receiver = new MiningCompletionReceiver(plugin, tasks, ledger, incidents, () -> true);
            assertTrue(receiver.accept(completion()));
            verifyNoInteractions(ledger);
            assertEquals(1, worker.queue.size());
            receiver.close();
            worker.runAll();
            verify(ledger).findAccount(any());
            verify(ledger).findAdoption(any());
            verify(incidents).recordVoided(argThat(e -> e.stage().equals("RELOAD_OR_SHUTDOWN")));
            verify(ledger, never()).apply(any(), any(), any());
        }
    }

    @Test void missingOrDriftedProducerFailsClosedBeforeEnqueue() {
        var plugin = mock(InfinityPickaxes.class); var server = mock(Server.class);
        var services = mock(ServicesManager.class); when(plugin.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(services);
        try (var bukkit = mockStatic(Bukkit.class);
             var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), Runnable::run)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var receiver = new MiningCompletionReceiver(plugin, tasks, mock(MariaMiningXpLedger.class),
                    mock(MiningIncidentService.class), () -> true);
            assertFalse(receiver.accept(completion()));
        }
    }

    @Test void missingAccountAndCustodyAndStaleRevisionAndProfileAllRejectBeforeSubmission() throws Exception {
        var completion = completion();
        var plugin = mock(InfinityPickaxes.class); var server = mock(Server.class);
        var services = mock(ServicesManager.class); var authority = mock(MiningAuthority.class);
        when(plugin.getServer()).thenReturn(server); when(server.getServicesManager()).thenReturn(services);
        when(services.load(MiningAuthority.class)).thenReturn(authority);
        when(authority.providerRevision()).thenReturn(completion.configurationRevision());
        when(authority.capabilities()).thenReturn(Map.of(MiningAuthority.Path.ORDINARY,
                new MiningAuthority.Capability(true, "approved")));
        when(plugin.getDuplicateService()).thenReturn(mock(com.infinitypickaxes.core.duplicate.PickaxeDuplicateService.class));
        var configManager = mock(com.infinitypickaxes.config.ConfigManager.class);
        when(configManager.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        when(plugin.getConfigManager()).thenReturn(configManager);
        var before = new MiningXpPlan.Progress(0, 0, 0);
        var account = new MiningXpPlan.Account(completion.itemId(), "infinitygear:pickaxe", 0, before);
        var adoption = new MariaMiningXpLedger.Adoption(UUID.randomUUID(), completion.playerId(), account, "operator");
        var ledger = mock(MariaMiningXpLedger.class);
        try (var bukkit = mockStatic(Bukkit.class);
             var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), Runnable::run)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            var receiver = new MiningCompletionReceiver(plugin, tasks, ledger, mock(MiningIncidentService.class), () -> true);
            assertTrue(assertThrows(RuntimeException.class,
                    () -> receiver.prepare(completion, new MiningCompletionReceiver.Loaded(null, null, null), 0))
                    .getMessage().contains("adopted"));
            assertTrue(assertThrows(RuntimeException.class,
                    () -> receiver.prepare(completion, new MiningCompletionReceiver.Loaded(account, adoption, null), 0))
                    .getMessage().contains("uniquely held"));

            var plan = new MiningXpPlan(completion.itemId(), account.profileId(), 0, before, 1, List.of(100.0));
            var item = new MiningXpItemProjectionTest.Item(plan);
            when(item.stack.isSimilar(item.stack)).thenReturn(true);
            item.put(MiningXpItemProjection.REVISION, PersistentDataType.LONG, 1L);
            var player = mock(org.bukkit.entity.Player.class); var personal = mock(PlayerInventory.class);
            var empty = mock(Inventory.class); var view = mock(InventoryView.class);
            when(player.getUniqueId()).thenReturn(completion.playerId()); when(player.getInventory()).thenReturn(personal);
            when(personal.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[]{item.stack});
            when(personal.getItemInMainHand()).thenReturn(item.stack); when(player.getEnderChest()).thenReturn(empty);
            when(empty.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[0]); when(player.getOpenInventory()).thenReturn(view);
            when(view.getTopInventory()).thenReturn(empty); bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            var revisionFailure = assertThrows(RuntimeException.class,
                    () -> receiver.prepare(completion, new MiningCompletionReceiver.Loaded(account, adoption, null), 0));
            assertTrue(revisionFailure.getMessage().contains("revision"), revisionFailure.getMessage());

            item.put(MiningXpItemProjection.REVISION, PersistentDataType.LONG, 0L);
            when(player.hasPermission("infinitypickaxes.use")).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(plugin.getGearManager()).thenReturn(mock(com.infinitygear.gear.GearManager.class));
            assertTrue(assertThrows(RuntimeException.class,
                    () -> receiver.prepare(completion, new MiningCompletionReceiver.Loaded(account, adoption, null), 0))
                    .getMessage().contains("profile"));
        }
        verify(ledger, never()).apply(any(), any(), any());
    }

    @Test void mainHandCustodyUsesResolvedSlotAndContentNotWrapperIdentity() {
        UUID id = UUID.randomUUID();
        var plan = new MiningXpPlan(id, "infinitygear:pickaxe", 0,
                new MiningXpPlan.Progress(0, 0, 0), 1, List.of(100.0));
        var resolved = new MiningXpItemProjectionTest.Item(plan);
        var held = new MiningXpItemProjectionTest.Item(plan);
        when(resolved.stack.isSimilar(held.stack)).thenReturn(true);
        var player = mock(org.bukkit.entity.Player.class);
        var inventory = mock(PlayerInventory.class);
        when(player.getName()).thenReturn("Officer_Ray");
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getHeldItemSlot()).thenReturn(2);
        when(inventory.getItemInMainHand()).thenReturn(held.stack);

        var located = new XpItemCustody.Located(resolved.stack, player,
                "player:Officer_Ray:2", 1, false);
        assertTrue(located.uniqueMainHand(player, id));
        assertFalse(new XpItemCustody.Located(resolved.stack, player,
                "player:Officer_Ray:1", 1, false).uniqueMainHand(player, id));
        assertFalse(new XpItemCustody.Located(resolved.stack, player,
                "player:Officer_Ray:2", 2, false).uniqueMainHand(player, id));
    }

    static final class QueuedExecutor extends AbstractExecutorService {
        final Queue<Runnable> queue = new ArrayDeque<>(); boolean stopped;
        public void execute(Runnable command) { if (stopped) throw new RejectedExecutionException(); queue.add(command); }
        void runAll() { while (!queue.isEmpty()) queue.remove().run(); }
        public void shutdown() { stopped = true; }
        public List<Runnable> shutdownNow() { stopped = true; var result = List.copyOf(queue); queue.clear(); return result; }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped && queue.isEmpty(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}

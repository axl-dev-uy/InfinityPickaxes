package com.infinitygear.integration;

import com.axl.custodian.api.AuthorityHandle;
import com.axl.custodian.api.BridgeHandle;
import com.axl.custodian.api.CustodianApi;
import com.axl.custodian.api.DuplicateAssessment;
import com.axl.custodian.api.IdentityOrigin;
import com.axl.custodian.api.IdentitySnapshot;
import com.axl.custodian.api.IdentityState;
import com.axl.custodian.api.RegistrationResult;
import com.axl.custodian.api.ScopeContribution;
import com.axl.custodian.api.ShadowContributor;
import com.infinitygear.data.GearData;
import com.infinitygear.data.TrackedKind;
import com.infinitypickaxes.InfinityPickaxes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustodianSettledShadowScannerTest {
    private static final Instant NOW = Instant.parse("2026-09-17T16:00:00Z");

    @Test
    void settledSnapshotsReuseStableNativeIdsAndOnlySubmitPartialComparisons() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask heartbeatTask = mock(BukkitTask.class);
        Logger logger = mock(Logger.class);
        CustodianApi api = mock(CustodianApi.class);
        ShadowContributor contributor = mock(ShadowContributor.class);
        AuthorityHandle authority = AuthorityHandle.issuedByHost("infinitygear");
        BridgeHandle bridge = new BridgeHandle(UUID.randomUUID(), "infinitygear");
        UUID identity = UUID.randomUUID();
        ItemStack gear = gear(identity);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(200L), eq(200L)))
                .thenReturn(heartbeatTask);
        when(contributor.startBridgeEpoch(authority, "local")).thenReturn(bridge);
        when(api.adopt(authority, identity)).thenReturn(registration(identity));
        when(contributor.contribute(eq(bridge), any())).thenReturn(
                new DuplicateAssessment(DuplicateAssessment.Status.ONE_ACTIVE, List.of()));

        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        when(playerInventory.getContents()).thenReturn(new ItemStack[]{gear});
        Inventory enderChest = inventory(new ItemStack[0]);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.getEnderChest()).thenReturn(enderChest);

        UUID worldId = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        UUID storageId = UUID.randomUUID();
        StorageMinecart storage = mock(StorageMinecart.class);
        when(storage.getUniqueId()).thenReturn(storageId);
        when(storage.getLocation()).thenReturn(new Location(world, 8, 72, -3));
        Inventory blockInventory = inventory(new ItemStack[]{gear});
        when(blockInventory.getHolder()).thenReturn(storage);
        InventoryView openView = mock(InventoryView.class);
        when(openView.getTopInventory()).thenReturn(blockInventory);
        when(player.getOpenInventory()).thenReturn(openView);

        UUID dropId = UUID.randomUUID();
        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(dropId);
        when(dropped.getItemStack()).thenReturn(gear);
        when(dropped.getLocation()).thenReturn(new Location(world, 10, 65, 4));
        when(world.getEntitiesByClass(Item.class)).thenReturn(List.of(dropped));
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        when(server.getWorlds()).thenReturn(List.of(world));

        CustodianSettledShadowScanner scanner = new CustodianSettledShadowScanner(
                plugin, api, contributor, authority, "local", Clock.fixed(NOW, ZoneOffset.UTC), 200L);
        scanner.accept(List.of());
        scanner.accept(List.of());

        ArgumentCaptor<ScopeContribution> contributions = ArgumentCaptor.forClass(ScopeContribution.class);
        verify(contributor, times(6)).contribute(eq(bridge), contributions.capture());
        List<String> first = contributions.getAllValues().subList(0, 3).stream()
                .map(CustodianSettledShadowScannerTest::encoded).toList();
        List<String> second = contributions.getAllValues().subList(3, 6).stream()
                .map(CustodianSettledShadowScannerTest::encoded).toList();
        assertEquals(first, second);
        assertTrue(first.contains("player:" + playerId + ":inventory|player:" + playerId
                + ":inventory:slot:0"));
        assertTrue(first.contains("entity:" + storageId + "|entity:" + storageId + ":slot:0"));
        assertTrue(first.contains("drop:" + dropId + "|drop:" + dropId + ":item"));
        assertTrue(contributions.getAllValues().stream().allMatch(contribution ->
                contribution.presences().stream().allMatch(presence ->
                        presence.epoch().id().equals(bridge.epochId()))));
        verify(contributor, times(1)).startBridgeEpoch(authority, "local");
        verify(api, never()).startEpoch(any());
        verify(gear, never()).setItemMeta(any());
        verify(plugin, never()).getDuplicateService();
        scanner.close();
        verify(heartbeatTask).cancel();
    }

    @Test
    void shutdownCancelsHeartbeatAndInvalidatesTheLocalBridgeHandle() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        CustodianApi api = mock(CustodianApi.class);
        ShadowContributor contributor = mock(ShadowContributor.class);
        AuthorityHandle authority = AuthorityHandle.issuedByHost("infinitygear");
        BridgeHandle bridge = new BridgeHandle(UUID.randomUUID(), "infinitygear");
        Logger logger = mock(Logger.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getScheduler()).thenReturn(scheduler);
        when(contributor.startBridgeEpoch(authority, "local")).thenReturn(bridge);
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.runTaskTimer(eq(plugin), heartbeat.capture(), eq(20L), eq(20L))).thenReturn(task);

        CustodianSettledShadowScanner scanner = new CustodianSettledShadowScanner(
                plugin, api, contributor, authority, "local", Clock.fixed(NOW, ZoneOffset.UTC), 20L);
        heartbeat.getValue().run();

        verify(contributor).heartbeat(bridge);
        verify(contributor).startBridgeEpoch(authority, "local");
        scanner.close();
        heartbeat.getValue().run();
        scanner.accept(List.of());
        verify(contributor, times(1)).heartbeat(bridge);
        verify(task).cancel();
    }

    @Test
    void malformedAndConflictingItemsAreNeitherAdoptedNorContributed() {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        Logger logger = mock(Logger.class);
        CustodianApi api = mock(CustodianApi.class);
        ShadowContributor contributor = mock(ShadowContributor.class);
        AuthorityHandle authority = AuthorityHandle.issuedByHost("infinitygear");
        BridgeHandle bridge = new BridgeHandle(UUID.randomUUID(), "infinitygear");
        UUID conflictId = UUID.randomUUID();
        ItemStack malformed = gear("not-a-uuid");
        ItemStack conflict = gear(conflictId.toString());
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Inventory emptyEnderChest = inventory(new ItemStack[0]);
        Inventory emptyTop = inventory(new ItemStack[0]);
        when(inventory.getContents()).thenReturn(new ItemStack[]{malformed, conflict});
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(emptyEnderChest);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(emptyTop);
        when(player.getOpenInventory()).thenReturn(view);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getScheduler()).thenReturn(scheduler);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        when(server.getWorlds()).thenReturn(List.of());
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(200L), eq(200L))).thenReturn(task);
        when(contributor.startBridgeEpoch(authority, "local")).thenReturn(bridge);
        when(api.adopt(authority, conflictId)).thenReturn(new RegistrationResult(
                RegistrationResult.Status.CONFLICT,
                new IdentitySnapshot(conflictId, IdentityState.ACTIVE, IdentityOrigin.ADOPTED,
                        "other", NOW, NOW, null)));

        CustodianSettledShadowScanner scanner = new CustodianSettledShadowScanner(
                plugin, api, contributor, authority, "local", Clock.fixed(NOW, ZoneOffset.UTC), 200L);
        scanner.accept(List.of());

        verify(api, times(1)).adopt(authority, conflictId);
        verify(contributor, never()).contribute(any(), any());
        verify(malformed, never()).setItemMeta(any());
        verify(conflict, never()).setItemMeta(any());
        scanner.close();
    }

    @Test
    void publicShadowContractCannotExpressCompleteReconciliation() {
        Set<String> operations = Arrays.stream(ShadowContributor.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("startBridgeEpoch", "heartbeat", "contribute"), operations);
        assertFalse(Arrays.stream(ScopeContribution.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("mode")));
    }

    private static Inventory inventory(ItemStack[] contents) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(contents);
        return inventory;
    }

    private static ItemStack gear(UUID identity) {
        return gear(identity.toString());
    }

    private static ItemStack gear(String identity) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(GearData.KEY_MARKER, PersistentDataType.BYTE)).thenReturn(true);
        when(pdc.get(GearData.KEY_KIND, PersistentDataType.STRING)).thenReturn(TrackedKind.GEAR.name());
        when(pdc.get(GearData.KEY_UUID, PersistentDataType.STRING)).thenReturn(identity);
        when(pdc.get(GearData.KEY_PROFILE, PersistentDataType.STRING)).thenReturn("partner:unknown");
        when(pdc.get(GearData.KEY_SCHEMA, PersistentDataType.INTEGER)).thenReturn(99);
        when(pdc.getOrDefault(GearData.KEY_LEVEL, PersistentDataType.INTEGER, 0)).thenReturn(3);
        when(pdc.getOrDefault(GearData.KEY_XP, PersistentDataType.DOUBLE, 0.0)).thenReturn(1.0);
        when(pdc.getOrDefault(GearData.KEY_BLOCKS, PersistentDataType.LONG, 0L)).thenReturn(8L);
        when(pdc.getOrDefault(GearData.KEY_SOCKETS, PersistentDataType.INTEGER, 0)).thenReturn(2);
        return item;
    }

    private static RegistrationResult registration(UUID identity) {
        return new RegistrationResult(RegistrationResult.Status.ALREADY_REGISTERED,
                new IdentitySnapshot(identity, IdentityState.ACTIVE, IdentityOrigin.ADOPTED,
                        "infinitygear", NOW, NOW, null));
    }

    private static String encoded(ScopeContribution contribution) {
        return contribution.scope().id() + "|" + contribution.presences().getFirst().instance().id();
    }
}

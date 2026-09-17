package com.infinitypickaxes.core.duplicate;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.config.ConfigManager;
import com.infinitypickaxes.config.MessageManager;
import com.infinitypickaxes.core.pickaxe.PickaxeData;
import com.infinitygear.data.TrackedItemData;
import com.infinitygear.data.TrackedKind;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Shelf;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.DecoratedPotInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhysicalStorageScannerTest {

    @Test
    void settledLegacyScanExcludesCursorOnlyItemsAcrossInventoryAndContainerMoves() throws Exception {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        DuplicateStore store = mock(DuplicateStore.class);
        when(store.loadRestrictedUuids()).thenReturn(Set.of());
        PickaxeDuplicateService service = new PickaxeDuplicateService(plugin, store);
        UUID uuid = UUID.randomUUID();
        ItemStack gear = mock(ItemStack.class);
        when(gear.getAmount()).thenReturn(1);
        AtomicReference<ItemStack[]> playerSlots = new AtomicReference<>(new ItemStack[]{gear});
        AtomicReference<ItemStack[]> containerSlots = new AtomicReference<>(new ItemStack[0]);

        PlayerInventory personal = mock(PlayerInventory.class);
        when(personal.getSize()).thenAnswer(ignored -> playerSlots.get().length);
        when(personal.getItem(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> playerSlots.get()[call.getArgument(0)]);
        Inventory container = mock(Inventory.class);
        when(container.getSize()).thenAnswer(ignored -> containerSlots.get().length);
        when(container.getItem(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> containerSlots.get()[call.getArgument(0)]);
        Container holder = blockContainer(UUID.randomUUID(), 3, 64, 9);
        when(container.getHolder()).thenReturn(holder);

        Player player = playerViewing("cursor", container);
        when(player.getInventory()).thenReturn(personal);
        // This is deliberately never read by the settled scanner.
        when(player.getItemOnCursor()).thenReturn(gear);

        var identity = new TrackedItemData.Identity(uuid, TrackedKind.GEAR,
                "infinitygear:cursor-test", 1, false);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<TrackedItemData> tracked = mockStatic(TrackedItemData.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            tracked.when(() -> TrackedItemData.readRaw(gear)).thenReturn(identity);

            // inventory -> cursor: the settled scan sees no item.
            playerSlots.set(new ItemStack[0]);
            assertEquals(0, service.scanOnline("test:inventory-to-cursor").itemsScanned());

            // cursor -> inventory: it becomes visible again.
            playerSlots.set(new ItemStack[]{gear});
            assertEquals(1, service.scanOnline("test:cursor-to-inventory").itemsScanned());

            // cursor -> physical container: it is visible through the container scope.
            playerSlots.set(new ItemStack[0]);
            containerSlots.set(new ItemStack[]{gear});
            assertEquals(1, service.scanOnline("test:cursor-to-container").itemsScanned());

            // A duplicate with one copy cursor-only is still one observed physical instance.
            playerSlots.set(new ItemStack[]{gear});
            containerSlots.set(new ItemStack[0]);
            DuplicateScanResult cursorOnlyDuplicate = service.scanOnline("test:cursor-only-duplicate");
            assertEquals(1, cursorOnlyDuplicate.physicalInstanceCounts().get(uuid));
            assertTrue(cursorOnlyDuplicate.duplicatesDetected().isEmpty());
            verify(player, never()).getItemOnCursor();
            verify(store, never()).quarantine(eq(uuid), anyString(), anyString(), anyList());
        }
    }

    @Test
    void persistedRestrictedArmorIsUnequippedWithOnlyOneVisibleCopy() throws Exception {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        DuplicateStore store = mock(DuplicateStore.class);
        UUID uuid = UUID.randomUUID();
        when(store.loadRestrictedUuids()).thenReturn(Set.of(uuid));
        PickaxeDuplicateService service = new PickaxeDuplicateService(plugin, store);

        ItemStack armor = mock(ItemStack.class);
        when(armor.getType()).thenReturn(Material.NETHERITE_CHESTPLATE);
        when(armor.getAmount()).thenReturn(1);
        PlayerInventory personal = mock(PlayerInventory.class);
        when(personal.getSize()).thenReturn(1);
        when(personal.getItem(0)).thenReturn(armor);
        when(personal.getContents()).thenReturn(new ItemStack[]{armor});
        when(personal.getItem(EquipmentSlot.CHEST)).thenReturn(armor);
        when(personal.addItem(armor)).thenReturn(new java.util.HashMap<>());
        Player player = playerViewing("returning", mock(Inventory.class));
        when(player.getInventory()).thenReturn(personal);
        var identity = new TrackedItemData.Identity(uuid, TrackedKind.GEAR,
                "infinitygear:armor", 1, true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<TrackedItemData> tracked = mockStatic(TrackedItemData.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            tracked.when(() -> TrackedItemData.readRaw(armor)).thenReturn(identity);

            DuplicateScanResult result = service.scanOnline("automatic:join:returning");

            assertEquals(1, result.itemsScanned());
            assertEquals(1, result.physicalInstanceCounts().get(uuid));
            assertTrue(result.duplicatesDetected().isEmpty());
            verify(personal).setItem(EquipmentSlot.CHEST, null);
            verify(personal).addItem(armor);
            verify(store, never()).quarantine(eq(uuid), anyString(), anyString(), anyList());
        }
    }

    @Test
    void duplicateArmorIsRecordedWithItsGearProfile() throws Exception {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        DuplicateStore store = mock(DuplicateStore.class);
        UUID uuid = UUID.randomUUID();
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(store.loadRestrictedUuids()).thenReturn(Set.of());
        PickaxeDuplicateService service = new PickaxeDuplicateService(plugin, store);

        ItemStack first = mock(ItemStack.class);
        ItemStack second = mock(ItemStack.class);
        for (ItemStack armor : List.of(first, second)) {
            when(armor.getType()).thenReturn(Material.NETHERITE_CHESTPLATE);
            when(armor.getAmount()).thenReturn(1);
        }
        PlayerInventory personal = mock(PlayerInventory.class);
        when(personal.getSize()).thenReturn(2);
        when(personal.getItem(0)).thenReturn(first);
        when(personal.getItem(1)).thenReturn(second);
        when(personal.getContents()).thenReturn(new ItemStack[]{first, second});
        when(personal.getItem(EquipmentSlot.CHEST)).thenReturn(first);
        when(personal.addItem(first)).thenReturn(new java.util.HashMap<>());
        Player player = playerViewing("armored", mock(Inventory.class));
        when(player.getInventory()).thenReturn(personal);

        var identity = new TrackedItemData.Identity(uuid, TrackedKind.GEAR,
                "infinitygear:armor", 1, false);
        PluginManager pluginManager = mock(PluginManager.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<TrackedItemData> tracked = mockStatic(TrackedItemData.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            tracked.when(() -> TrackedItemData.readRaw(first)).thenReturn(identity);
            tracked.when(() -> TrackedItemData.readRaw(second)).thenReturn(identity);

            DuplicateScanResult result = service.scanOnline("test:armor-profile");

            assertEquals(2, result.itemsScanned());
            assertEquals(2, result.physicalInstanceCounts().get(uuid));
            assertTrue(result.duplicatesDetected().contains(uuid));
            verify(store).quarantine(eq(uuid), eq(TrackedKind.GEAR.name()), eq("infinitygear:armor"),
                    anyString(), eq("test:armor-profile"), anyList());
            verify(messages).sendMessage(eq(player), eq("messages.gear-duplicate-detected"),
                    eq("%uuid%"), eq(uuid.toString()), eq("%profile%"), eq("infinitygear:armor"));
            verify(personal).setItem(EquipmentSlot.CHEST, null);
            verify(personal).addItem(first);
        }
    }

    @Test
    void twoPlayersViewingSameChestWrapperDoNotCreateDuplicate() throws Exception {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        DuplicateStore store = mock(DuplicateStore.class);
        when(store.loadRestrictedUuids()).thenReturn(Set.of());
        PickaxeDuplicateService service = new PickaxeDuplicateService(plugin, store);

        UUID worldUuid = UUID.randomUUID();
        Container firstHolder = blockContainer(worldUuid, 12, 64, -8);
        Container secondHolder = blockContainer(worldUuid, 12, 64, -8);
        Inventory firstWrapper = chestWrapper(firstHolder);
        Inventory secondWrapper = chestWrapper(secondHolder);

        ItemStack pickaxe = mock(ItemStack.class);
        when(pickaxe.getType()).thenReturn(Material.NETHERITE_PICKAXE);
        when(pickaxe.getAmount()).thenReturn(1);
        when(firstWrapper.getSize()).thenReturn(1);
        when(secondWrapper.getSize()).thenReturn(1);
        when(firstWrapper.getItem(0)).thenReturn(pickaxe);
        when(secondWrapper.getItem(0)).thenReturn(pickaxe);

        Player firstPlayer = playerViewing("first", firstWrapper);
        Player secondPlayer = playerViewing("second", secondWrapper);
        UUID pickaxeUuid = UUID.randomUUID();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PickaxeData> pickaxeData = mockStatic(PickaxeData.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(firstPlayer, secondPlayer));
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            pickaxeData.when(() -> PickaxeData.getPickaxeUuid(pickaxe)).thenReturn(pickaxeUuid);

            DuplicateScanResult result = service.scanOnline("test:shared-chest");

            assertEquals(1, result.itemsScanned());
            assertTrue(result.duplicatesDetected().isEmpty());
            verify(store, never()).quarantine(eq(pickaxeUuid), anyString(), anyString(), anyList());
        }
    }

    @Test
    void retainedOpenedStorageIsScannedAfterPlayerSwitchesAway() throws Exception {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        DuplicateStore store = mock(DuplicateStore.class);
        when(store.loadRestrictedUuids()).thenReturn(Set.of());
        PickaxeDuplicateService service = new PickaxeDuplicateService(plugin, store);

        UUID worldUuid = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldUuid);
        DecoratedPot liveHolder = blockStorage(DecoratedPot.class, world, 5, 40, 9);
        DecoratedPotInventory retainedWrapper = mock(DecoratedPotInventory.class);
        when(retainedWrapper.getHolder()).thenReturn(liveHolder);
        when(liveHolder.getInventory()).thenReturn(retainedWrapper);
        Block block = mock(Block.class);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getBlockAt(5, 40, 9)).thenReturn(block);
        when(block.getState(false)).thenReturn(liveHolder);
        PhysicalStorageKey retainedKey = PhysicalStorageKey.from(retainedWrapper).orElseThrow();
        ItemStack pickaxe = mock(ItemStack.class);
        UUID pickaxeUuid = UUID.randomUUID();
        when(pickaxe.getType()).thenReturn(Material.NETHERITE_PICKAXE);
        when(pickaxe.getAmount()).thenReturn(1);
        when(retainedWrapper.getSize()).thenReturn(1);
        when(retainedWrapper.getItem(0)).thenReturn(pickaxe);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PickaxeData> pickaxeData = mockStatic(PickaxeData.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            bukkit.when(() -> Bukkit.getWorld(worldUuid)).thenReturn(world);
            pickaxeData.when(() -> PickaxeData.getPickaxeUuid(pickaxe)).thenReturn(pickaxeUuid);

            DuplicateScanResult result = service.scanOnline(
                    "test:retained-storage", List.of(retainedKey));

            assertEquals(1, result.itemsScanned());
            assertTrue(result.duplicatesDetected().isEmpty());
            verify(store, never()).quarantine(eq(pickaxeUuid), anyString(), anyString(), anyList());
        }
    }

    @Test
    void doubleChestKeyIsStableRegardlessOfSideOrder() {
        UUID worldUuid = UUID.randomUUID();
        Container left = blockContainer(worldUuid, 0, 70, 0);
        Container right = blockContainer(worldUuid, 1, 70, 0);
        DoubleChest first = mock(DoubleChest.class);
        DoubleChest reversed = mock(DoubleChest.class);
        when(first.getLeftSide()).thenReturn(left);
        when(first.getRightSide()).thenReturn(right);
        when(reversed.getLeftSide()).thenReturn(right);
        when(reversed.getRightSide()).thenReturn(left);

        assertEquals(PhysicalStorageKey.from(first), PhysicalStorageKey.from(reversed));
    }

    @Test
    void hopperMinecartUsesEntityUuid() {
        HopperMinecart hopper = mock(HopperMinecart.class);
        UUID uuid = UUID.randomUUID();
        when(hopper.getUniqueId()).thenReturn(uuid);

        assertEquals("entity:" + uuid, PhysicalStorageKey.from(hopper).orElseThrow().value());
    }

    @Test
    void decoratedPotIsPhysicalBlockStorage() {
        UUID worldUuid = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldUuid);
        DecoratedPot pot = blockStorage(DecoratedPot.class, world, 3, 70, 4);

        assertEquals("block:" + worldUuid + ":3:70:4",
                PhysicalStorageKey.from(pot).orElseThrow().value());
    }

    @Test
    void shelfIsPhysicalBlockStorage() {
        UUID worldUuid = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldUuid);
        Shelf shelf = blockStorage(Shelf.class, world, -2, 81, 14);

        assertEquals("block:" + worldUuid + ":-2:81:14",
                PhysicalStorageKey.from(shelf).orElseThrow().value());
    }

    @Test
    void nestedDecoratedPotInventoryIsTraversed() throws Exception {
        InfinityPickaxes plugin = mock(InfinityPickaxes.class);
        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration config = mock(FileConfiguration.class);
        DuplicateStore store = mock(DuplicateStore.class);
        when(store.loadRestrictedUuids()).thenReturn(Set.of());
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getConfig()).thenReturn(config);
        when(config.getInt("duplicate-protection.container-recursion-depth", 3)).thenReturn(3);
        PickaxeDuplicateService service = new PickaxeDuplicateService(plugin, store);

        ItemStack nestedPickaxe = mock(ItemStack.class);
        when(nestedPickaxe.getAmount()).thenReturn(1);
        when(nestedPickaxe.getType()).thenReturn(Material.NETHERITE_PICKAXE);
        DecoratedPot pot = mock(DecoratedPot.class);
        DecoratedPotInventory potInventory = mock(DecoratedPotInventory.class);
        when(pot.getInventory()).thenReturn(potInventory);
        when(potInventory.getContents()).thenReturn(new ItemStack[]{nestedPickaxe});
        BlockStateMeta blockMeta = mock(BlockStateMeta.class);
        when(blockMeta.getBlockState()).thenReturn(pot);
        ItemStack potItem = mock(ItemStack.class);
        when(potItem.getAmount()).thenReturn(1);
        when(potItem.getType()).thenReturn(Material.DECORATED_POT);
        when(potItem.getItemMeta()).thenReturn(blockMeta);

        try (MockedStatic<PickaxeData> pickaxeData = mockStatic(PickaxeData.class)) {
            pickaxeData.when(() -> PickaxeData.isInfinityPickaxe(nestedPickaxe)).thenReturn(true);
            assertTrue(service.containsInfinityPickaxe(inventoryContaining(potItem)));
        }
    }

    private static Container blockContainer(UUID worldUuid, int x, int y, int z) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldUuid);
        return blockStorage(Container.class, world, x, y, z);
    }

    private static <T extends TileStateInventoryHolder> T blockStorage(
            Class<T> type, World world, int x, int y, int z) {
        T holder = mock(type);
        when(holder.getWorld()).thenReturn(world);
        when(holder.getLocation()).thenReturn(new Location(world, x, y, z));
        return holder;
    }

    private static Inventory chestWrapper(Container holder) {
        return storageWrapper(holder);
    }

    private static Inventory storageWrapper(TileStateInventoryHolder holder) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder);
        return inventory;
    }

    private static Inventory inventoryContaining(ItemStack item) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[]{item});
        return inventory;
    }

    private static Player playerViewing(String name, Inventory top) {
        Player player = mock(Player.class);
        PlayerInventory personal = mock(PlayerInventory.class);
        Inventory ender = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(player.getName()).thenReturn(name);
        when(player.getInventory()).thenReturn(personal);
        when(player.getEnderChest()).thenReturn(ender);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        return player;
    }
}

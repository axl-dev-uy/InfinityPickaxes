package com.infinitygear.mining;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.data.GearData;
import com.infinitygear.persistence.MariaMiningXpLedger.Receipt;
import com.infinitypickaxes.core.pickaxe.PickaxeData;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MiningXpItemProjectionTest {
    record Value(Object type, Object value) { }
    static class Item {
        Map<NamespacedKey, Value> values = new HashMap<>();
        final ItemStack stack = mock(ItemStack.class); int saves;
        final Map<ItemMeta, Map<NamespacedKey, Value>> copies = new IdentityHashMap<>();
        Item(MiningXpPlan plan) {
            put(GearData.KEY_MARKER, PersistentDataType.BYTE, (byte) 1);
            put(GearData.KEY_KIND, PersistentDataType.STRING, "GEAR");
            put(GearData.KEY_UUID, PersistentDataType.STRING, plan.pickaxeId().toString());
            put(GearData.KEY_PROFILE, PersistentDataType.STRING, plan.profileId());
            put(PickaxeData.KEY_IS_INFINITY, PersistentDataType.BYTE, (byte) 1);
            put(PickaxeData.KEY_UUID, PersistentDataType.STRING, plan.pickaxeId().toString());
            put(GearData.KEY_LEVEL, PersistentDataType.INTEGER, plan.before().level());
            put(GearData.KEY_XP, PersistentDataType.DOUBLE, plan.before().xp());
            put(GearData.KEY_BLOCKS, PersistentDataType.LONG, plan.before().blocksMined());
            put(PickaxeData.KEY_LEVEL, PersistentDataType.INTEGER, plan.before().level());
            put(PickaxeData.KEY_XP, PersistentDataType.DOUBLE, plan.before().xp());
            put(PickaxeData.KEY_BLOCKS_MINED, PersistentDataType.LONG, plan.before().blocksMined());
            when(stack.getAmount()).thenReturn(1); when(stack.hasItemMeta()).thenReturn(true);
            when(stack.getItemMeta()).thenAnswer(call -> {
                var copy = new HashMap<>(values); var meta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
                when(meta.getPersistentDataContainer()).thenReturn(pdc); copies.put(meta, copy);
                when(pdc.get(any(), any())).thenAnswer(a -> {
                    var value = copy.get(a.getArgument(0)); return value != null && value.type().equals(a.getArgument(1)) ? value.value() : null;
                });
                when(pdc.has(any(NamespacedKey.class))).thenAnswer(a -> copy.containsKey(a.getArgument(0)));
                when(pdc.has(any(NamespacedKey.class), any())).thenAnswer(a -> {
                    var value = copy.get(a.getArgument(0)); return value != null && value.type().equals(a.getArgument(1));
                });
                doAnswer(a -> { copy.put(a.getArgument(0), new Value(a.getArgument(1), a.getArgument(2))); return null; }).when(pdc).set(any(), any(), any());
                return meta;
            });
            when(stack.setItemMeta(any())).thenAnswer(a -> { values = new HashMap<>(copies.get(a.getArgument(0))); saves++; return true; });
        }
        void put(NamespacedKey key, Object type, Object value) { values.put(key, new Value(type, value)); }
    }
    Receipt receipt() {
        UUID id = UUID.randomUUID(); String profile = GearData.LEGACY_PICKAXE_PROFILE;
        var plan = new MiningXpPlan(id, profile, 0, new MiningXpPlan.Progress(0, 90, 7), 20, List.of(100.0, 200.0));
        var credit = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), id, profile,
                UUID.randomUUID(), 1, 2, 3, "minecraft:stone", MiningCredit.Source.NORMAL, "reset-1", true, true);
        return new Receipt(credit, plan, new MiningXpPlan.Account(id, profile, 1, plan.after()));
    }
    @Test void oneMetaSaveMirrorsProgressAndReplayNeverAddsXp() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var receipt = receipt(); var item = new Item(receipt.plan()); var projection = new MiningXpItemProjection();
            var other = new NamespacedKey("test", "unrelated"); item.put(other, PersistentDataType.STRING, "keep");
            assertEquals(MiningXpItemProjection.Result.APPLIED, projection.apply(item.stack, receipt));
            assertEquals(10.0, item.values.get(GearData.KEY_XP).value());
            assertEquals(item.values.get(GearData.KEY_XP), item.values.get(PickaxeData.KEY_XP));
            assertEquals(8L, item.values.get(PickaxeData.KEY_BLOCKS_MINED).value());
            assertEquals("keep", item.values.get(other).value());
            assertEquals(MiningXpItemProjection.Result.ALREADY_APPLIED, projection.apply(item.stack, receipt));
            assertEquals(1, item.saves);
        }
    }
    @Test void lostItemSaveCanRestoreTheSameAbsoluteReceipt() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var receipt = receipt(); var item = new Item(receipt.plan()); var before = new HashMap<>(item.values);
            var projection = new MiningXpItemProjection(); projection.apply(item.stack, receipt); var after = new HashMap<>(item.values);
            item.values = before;
            assertEquals(MiningXpItemProjection.Result.APPLIED, projection.apply(item.stack, receipt));
            assertEquals(after, item.values);
        }
    }
    @Test void adminOrLegacyEditsAreNotOverwritten() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var receipt = receipt(); var item = new Item(receipt.plan());
            item.put(PickaxeData.KEY_XP, PersistentDataType.DOUBLE, 91.0);
            assertEquals(MiningXpItemProjection.Result.CONFLICT, new MiningXpItemProjection().apply(item.stack, receipt));
            assertEquals(0, item.saves); assertEquals(91.0, item.values.get(PickaxeData.KEY_XP).value());
        }
    }
    @Test void newerOrMalformedRevisionsAreNotReplaced() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var receipt = receipt(); var item = new Item(receipt.plan());
            for (var value : List.of(new Value(PersistentDataType.LONG, 2L), new Value(PersistentDataType.STRING, "bad"))) {
                item.values.put(MiningXpItemProjection.REVISION, value);
                assertEquals(MiningXpItemProjection.Result.CONFLICT, new MiningXpItemProjection().apply(item.stack, receipt));
            }
            assertEquals(0, item.saves);
        }
    }
    @Test void projectionRequiresServerThread() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> new MiningXpItemProjection().apply(null, receipt()));
        }
    }
    @Test void legacyPersistenceCannotOverwriteManagedProgressButCanSaveUnchangedFields() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var receipt = receipt(); var item = new Item(receipt.plan());
            new MiningXpItemProjection().apply(item.stack, receipt);
            var current = receipt.account().progress();
            var gear = new com.infinitygear.gear.GearInstance(item.stack, receipt.account().pickaxeId(), receipt.account().profileId(),
                    current.level(), current.xp(), current.blocksMined(), 10);
            gear.xp(current.xp() + 1);
            assertThrows(IllegalStateException.class, () -> GearData.save(gear, false, true));
            var pickaxe = mock(com.infinitypickaxes.core.pickaxe.InfinityPickaxe.class);
            when(pickaxe.getLevel()).thenReturn(current.level()); when(pickaxe.getXp()).thenReturn(current.xp() + 1);
            when(pickaxe.getBlocksMined()).thenReturn(current.blocksMined());
            assertThrows(IllegalStateException.class, () -> PickaxeData.saveToItemStack(pickaxe, item.stack));
            assertEquals(1, item.saves);
            gear.xp(current.xp()); GearData.save(gear, false, true);
            assertEquals(2, item.saves);
            assertEquals(1L, item.values.get(MiningXpItemProjection.REVISION).value());
        }
    }
}

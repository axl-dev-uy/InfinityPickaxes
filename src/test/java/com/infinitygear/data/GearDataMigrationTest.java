package com.infinitygear.data;

import com.infinitypickaxes.core.pickaxe.PickaxeData;
import com.infinitygear.gear.GearInstance;
import com.infinitygear.testutil.ProfilePdcSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GearDataMigrationTest {
    @Test void validLegacyItemMigratesLazilyAndPreservesIdentityStatsAndQuarantine() {
        Harness harness = new Harness();
        UUID uuid = UUID.randomUUID();
        harness.put(PickaxeData.KEY_IS_INFINITY, (byte) 1);
        harness.put(PickaxeData.KEY_UUID, uuid.toString());
        harness.put(PickaxeData.KEY_LEVEL, 31);
        harness.put(PickaxeData.KEY_XP, 42.5);
        harness.put(PickaxeData.KEY_BLOCKS_MINED, 999L);
        harness.put(PickaxeData.KEY_QUARANTINED, (byte) 1);
        ProfilePdcSnapshot before = ProfilePdcSnapshot.capture(harness.item);

        GearData.ReadResult result = GearData.read(harness.item, 6, true);
        assertEquals(GearData.State.MIGRATED_LEGACY, result.state());
        assertEquals(uuid, result.gear().uuid());
        assertEquals(31, result.gear().level());
        assertEquals(42.5, result.gear().xp());
        assertEquals(999, result.gear().blocksMined());
        assertEquals(6, result.gear().socketCapacity());
        assertEquals(uuid.toString(), harness.values.get(GearData.KEY_UUID));
        assertEquals(GearData.SCHEMA_VERSION, harness.values.get(GearData.KEY_SCHEMA));
        assertEquals((byte) 1, harness.values.get(GearData.KEY_QUARANTINED));
        assertEquals(uuid.toString(), harness.values.get(PickaxeData.KEY_UUID));
        ProfilePdcSnapshot after = ProfilePdcSnapshot.capture(harness.item);
        assertEquals(before.values().get(PickaxeData.KEY_XP), after.values().get(PickaxeData.KEY_XP));
        assertEquals(before.values().get(PickaxeData.KEY_UUID), after.values().get(PickaxeData.KEY_UUID));
    }

    @Test void validNewDataIsPreferredEvenWhenLegacyDataAlsoExists() {
        Harness harness = new Harness();
        UUID newUuid = UUID.randomUUID();
        harness.put(GearData.KEY_MARKER, (byte) 1);
        harness.put(GearData.KEY_KIND, TrackedKind.GEAR.name());
        harness.put(GearData.KEY_UUID, newUuid.toString());
        harness.put(GearData.KEY_PROFILE, "infinitygear:sword");
        harness.put(GearData.KEY_SCHEMA, 1);
        harness.put(PickaxeData.KEY_IS_INFINITY, (byte) 1);
        harness.put(PickaxeData.KEY_UUID, UUID.randomUUID().toString());
        ProfilePdcSnapshot before = ProfilePdcSnapshot.capture(harness.item);
        GearData.ReadResult result = GearData.read(harness.item, 3, true);
        assertEquals(GearData.State.VALID_NEW, result.state());
        assertEquals(newUuid, result.gear().uuid());
        assertEquals("infinitygear:sword", result.gear().profileId());
        ProfilePdcSnapshot.assertProfileOwnedUnchanged(before, ProfilePdcSnapshot.capture(harness.item));
    }

    @Test void malformedLegacyUuidIsPreservedAndNeverMigrated() {
        Harness harness = new Harness();
        harness.put(PickaxeData.KEY_IS_INFINITY, (byte) 1);
        harness.put(PickaxeData.KEY_UUID, "bad");
        harness.put(PickaxeData.KEY_LEVEL, 1);
        harness.put(PickaxeData.KEY_XP, 0.0);
        harness.put(PickaxeData.KEY_BLOCKS_MINED, 0L);
        ProfilePdcSnapshot before = ProfilePdcSnapshot.capture(harness.item);
        GearData.ReadResult result = GearData.read(harness.item, 3, true);
        assertEquals(GearData.State.MALFORMED_LEGACY, result.state());
        assertNull(result.gear());
        assertFalse(harness.values.containsKey(GearData.KEY_MARKER));
        assertEquals("bad", harness.values.get(PickaxeData.KEY_UUID));
        ProfilePdcSnapshot.assertProfileOwnedUnchanged(before, ProfilePdcSnapshot.capture(harness.item));
    }

    @Test void futureSchemaAndUnknownProfileAreReadWithoutPdcMutation() {
        Harness harness = new Harness(); UUID uuid = UUID.randomUUID();
        harness.put(GearData.KEY_MARKER, (byte) 1); harness.put(GearData.KEY_KIND, TrackedKind.GEAR.name());
        harness.put(GearData.KEY_UUID, uuid.toString()); harness.put(GearData.KEY_PROFILE, "partner:future-profile");
        harness.put(GearData.KEY_SCHEMA, 99); harness.put(GearData.KEY_LEVEL, 71);
        harness.put(GearData.KEY_XP, Double.longBitsToDouble(0x3ff0000000000001L));
        harness.put(GearData.KEY_BLOCKS, 1_234_567_890_123L); harness.put(GearData.KEY_SOCKETS, 9);
        harness.put(GearData.KEY_QUARANTINED, (byte) 1);
        ProfilePdcSnapshot before = ProfilePdcSnapshot.capture(harness.item);
        GearData.ReadResult result = GearData.read(harness.item, 0, true);
        assertEquals(GearData.State.VALID_NEW, result.state()); assertEquals("partner:future-profile", result.gear().profileId());
        ProfilePdcSnapshot after = ProfilePdcSnapshot.capture(harness.item);
        ProfilePdcSnapshot.assertProfileOwnedUnchanged(before, after);
        assertEquals(before.values().get(GearData.KEY_XP), after.values().get(GearData.KEY_XP));
    }

    @Test void saveAndReadRoundTripWritesExactProfilePdcTypesAndValues() {
        Harness harness = new Harness(); UUID uuid = UUID.randomUUID();
        double xp = Double.longBitsToDouble(0x3ff0000000000001L);
        GearData.save(new GearInstance(harness.item, uuid, GearData.LEGACY_PICKAXE_PROFILE, 42, xp, 999L, 6), true, true);
        ProfilePdcSnapshot snapshot = ProfilePdcSnapshot.capture(harness.item);
        assertEquals("java.lang.Integer", snapshot.values().get(GearData.KEY_LEVEL).primitiveType());
        assertEquals("java.lang.Double", snapshot.values().get(GearData.KEY_XP).primitiveType());
        assertEquals(new ProfilePdcSnapshot.DoubleBits(Double.doubleToRawLongBits(xp)), snapshot.values().get(GearData.KEY_XP).value());
        assertEquals((byte) 1, snapshot.values().get(GearData.KEY_QUARANTINED).value());
        GearData.ReadResult roundTripped = GearData.read(harness.item, 0, false);
        assertEquals(GearData.State.VALID_NEW, roundTripped.state()); assertEquals(uuid, roundTripped.gear().uuid());
        assertEquals(42, roundTripped.gear().level()); assertEquals(xp, roundTripped.gear().xp());
        assertEquals(999L, roundTripped.gear().blocksMined()); assertEquals(6, roundTripped.gear().socketCapacity());
    }

    private static final class Harness {
        final Map<NamespacedKey, Object> values = new HashMap<>();
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        final ItemMeta meta = mock(ItemMeta.class);
        final ItemStack item = mock(ItemStack.class);
        Harness() {
            when(item.hasItemMeta()).thenReturn(true);
            when(item.getItemMeta()).thenReturn(meta);
            when(item.getType()).thenReturn(Material.NETHERITE_PICKAXE);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.getKeys()).thenAnswer(call -> Set.copyOf(values.keySet()));
            when(pdc.has(any(NamespacedKey.class), any())).thenAnswer(call -> hasType(call.getArgument(0), call.getArgument(1)));
            when(pdc.get(any(NamespacedKey.class), any())).thenAnswer(call -> valueForType(call.getArgument(0), call.getArgument(1)));
            when(pdc.getOrDefault(any(NamespacedKey.class), any(), any())).thenAnswer(call ->
                    java.util.Optional.ofNullable(valueForType(call.getArgument(0), call.getArgument(1))).orElse(call.getArgument(2)));
            doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
                    .when(pdc).set(any(NamespacedKey.class), any(), any());
            doAnswer(call -> { values.remove(call.getArgument(0)); return null; })
                    .when(pdc).remove(any(NamespacedKey.class));
        }
        void put(NamespacedKey key, Object value) { values.put(key, value); }
        private boolean hasType(NamespacedKey key, PersistentDataType<?, ?> type) { return valueForType(key, type) != null; }
        private Object valueForType(NamespacedKey key, PersistentDataType<?, ?> type) {
            Object value = values.get(key);
            return value != null && type.getPrimitiveType().isInstance(value) ? value : null;
        }
    }
}

package com.infinitygear.testutil;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test-only PDC forensic snapshot. Every discovered key is recorded, including unreadable custom types. */
public final class ProfilePdcSnapshot {
    private static final List<PersistentDataType<?, ?>> STANDARD_TYPES = List.of(
            PersistentDataType.BYTE, PersistentDataType.SHORT, PersistentDataType.INTEGER,
            PersistentDataType.LONG, PersistentDataType.FLOAT, PersistentDataType.DOUBLE,
            PersistentDataType.STRING, PersistentDataType.BYTE_ARRAY,
            PersistentDataType.INTEGER_ARRAY, PersistentDataType.LONG_ARRAY);
    private final Map<NamespacedKey, Value> values;
    private final Set<NamespacedKey> unreadableKeys;
    private final byte[] serializedItem;
    private ProfilePdcSnapshot(Map<NamespacedKey, Value> values, Set<NamespacedKey> unreadableKeys, byte[] serializedItem) {
        this.values = Map.copyOf(values); this.unreadableKeys = Set.copyOf(unreadableKeys);
        this.serializedItem = serializedItem == null ? null : serializedItem.clone();
    }
    public static ProfilePdcSnapshot capture(ItemStack item) {
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        Map<NamespacedKey, Value> values = new LinkedHashMap<>(); Set<NamespacedKey> unreadable = new LinkedHashSet<>();
        for (NamespacedKey key : pdc.getKeys()) {
            Value value = readKnownPrimitive(pdc, key);
            if (value == null) unreadable.add(key); else values.put(key, value);
        }
        // Bukkit exposes no type-independent PDC decoder. Its native item bytes provide the fallback evidence
        // whenever a real ItemStack is available; Mockito PDC harnesses intentionally return null here.
        byte[] serialized = item.serializeAsBytes();
        return new ProfilePdcSnapshot(values, unreadable, serialized);
    }
    /** The Bukkit API has no generic decoder for arbitrary custom PDC types; keys are retained visibly instead of omitted. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Value readKnownPrimitive(PersistentDataContainer pdc, NamespacedKey key) {
        for (PersistentDataType<?, ?> type : STANDARD_TYPES) {
            Object value = pdc.get(key, (PersistentDataType) type);
            if (value != null) return Value.of(type, value);
        }
        return null;
    }
    public Map<NamespacedKey, Value> values() { return values; }
    public Set<NamespacedKey> unreadableKeys() { return unreadableKeys; }
    public byte[] serializedItem() { return serializedItem == null ? null : serializedItem.clone(); }
    public static void assertProfileOwnedUnchanged(ProfilePdcSnapshot before, ProfilePdcSnapshot after) {
        assertEquals(profileEntries(before.values), profileEntries(after.values), "profile PDC values/types changed");
        assertEquals(profileKeys(before.unreadableKeys), profileKeys(after.unreadableKeys), "unreadable profile PDC keys changed");
        assertSerializedEquivalent(before, after);
    }
    /** Future Custodian-operation assertion: all non-Custodian PDC values must be equivalent. */
    public static void assertOnlyListedCustodianChanges(ProfilePdcSnapshot before, ProfilePdcSnapshot after, Set<NamespacedKey> allowedCustodianKeys) {
        assertProfileOwnedUnchanged(before, after);
        Set<NamespacedKey> all = new LinkedHashSet<>(); all.addAll(before.values.keySet()); all.addAll(after.values.keySet());
        for (NamespacedKey key : all) if (!java.util.Objects.equals(before.values.get(key), after.values.get(key))) {
            assertTrue(key.getNamespace().equals("custodian") && allowedCustodianKeys.contains(key), () -> "unexpected PDC mutation: " + key);
        }
        assertEquals(before.unreadableKeys, after.unreadableKeys, "a custom PDC key was added or removed; generic Bukkit PDC decoding is unavailable");
        assertSerializedEquivalent(before, after);
    }
    private static void assertSerializedEquivalent(ProfilePdcSnapshot before, ProfilePdcSnapshot after) {
        assertEquals(before.serializedItem == null, after.serializedItem == null, "item serialization availability changed");
        if (before.serializedItem != null) assertArrayEquals(before.serializedItem, after.serializedItem,
                "serialized item representation changed");
    }
    private static Map<NamespacedKey, Value> profileEntries(Map<NamespacedKey, Value> source) {
        Map<NamespacedKey, Value> filtered = new LinkedHashMap<>(); source.forEach((key, value) -> { if (profileOwned(key)) filtered.put(key, value); }); return filtered;
    }
    private static Set<NamespacedKey> profileKeys(Set<NamespacedKey> source) {
        Set<NamespacedKey> filtered = new LinkedHashSet<>(); source.forEach(key -> { if (profileOwned(key)) filtered.add(key); }); return filtered;
    }
    private static boolean profileOwned(NamespacedKey key) { return key.getNamespace().equals("infinitygear") || key.getNamespace().equals("infinitypickaxes"); }
    public record Value(String primitiveType, Object value) {
        static Value of(PersistentDataType<?, ?> type, Object value) {
            String primitive = type.getPrimitiveType().getName();
            if (value instanceof Double decimal) return new Value(primitive, new DoubleBits(Double.doubleToRawLongBits(decimal)));
            if (value instanceof byte[] bytes) return new Value(primitive, new ByteArray(bytes));
            if (value instanceof int[] integers) return new Value(primitive, new IntArray(integers));
            if (value instanceof long[] longs) return new Value(primitive, new LongArray(longs));
            return new Value(primitive, value);
        }
    }
    public record DoubleBits(long rawBits) { }
    private record ByteArray(byte[] value) { @Override public boolean equals(Object other) { return other instanceof ByteArray array && Arrays.equals(value, array.value); } @Override public int hashCode() { return Arrays.hashCode(value); } }
    private record IntArray(int[] value) { @Override public boolean equals(Object other) { return other instanceof IntArray array && Arrays.equals(value, array.value); } @Override public int hashCode() { return Arrays.hashCode(value); } }
    private record LongArray(long[] value) { @Override public boolean equals(Object other) { return other instanceof LongArray array && Arrays.equals(value, array.value); } @Override public int hashCode() { return Arrays.hashCode(value); } }
}

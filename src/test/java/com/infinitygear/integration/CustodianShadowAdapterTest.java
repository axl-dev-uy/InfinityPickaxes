package com.infinitygear.integration;

import com.axl.custodian.api.*;
import com.infinitygear.data.GearData;
import com.infinitygear.data.TrackedKind;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustodianShadowAdapterTest {
    @Test void validNewGearAdoptsUnchangedAndRepeatedCallsStayIdempotent() {
        Harness h = newGear("partner:unknown", 99); CustodianApi api = mock(CustodianApi.class); AuthorityHandle authority = AuthorityHandle.issuedByHost("infinitygear");
        when(api.adopt(eq(authority), eq(h.id))).thenReturn(result(RegistrationResult.Status.CREATED, h.id), result(RegistrationResult.Status.ALREADY_REGISTERED, h.id));
        var adapter = new CustodianShadowAdapter(api, authority); Map<NamespacedKey,Object> before = Map.copyOf(h.values);
        assertEquals(RegistrationResult.Status.CREATED, adapter.adopt(h.item, 0).orElseThrow().status());
        assertEquals(RegistrationResult.Status.ALREADY_REGISTERED, adapter.adopt(h.item, 0).orElseThrow().status());
        assertEquals(before, h.values); verify(h.item, never()).setItemMeta(any()); verify(api, never()).startEpoch(any());
    }
    @Test void legacyReadDoesNotMigrateAndMalformedUuidIsRejectedUnchanged() {
        UUID legacyId = UUID.randomUUID(); Harness legacy = Harness.legacy(legacyId.toString()); CustodianApi api = mock(CustodianApi.class); when(api.adopt(any(), eq(legacyId))).thenReturn(result(RegistrationResult.Status.CREATED, legacyId)); var adapter = new CustodianShadowAdapter(api, AuthorityHandle.issuedByHost("infinitygear")); Map<NamespacedKey,Object> before = Map.copyOf(legacy.values);
        assertTrue(adapter.adopt(legacy.item, 4).isPresent()); assertEquals(before, legacy.values); assertFalse(legacy.values.containsKey(GearData.KEY_MARKER));
        Harness bad = Harness.legacy("bad"); Map<NamespacedKey,Object> badBefore = Map.copyOf(bad.values); assertTrue(adapter.adopt(bad.item, 4).isEmpty()); assertEquals(badBefore, bad.values); verify(api, times(1)).adopt(any(), any());
    }
    @Test void mismatchedPresenceIsRejectedWithoutObservation() {
        Harness h = newGear("partner:unknown", 77); CustodianApi api = mock(CustodianApi.class); var adapter = new CustodianShadowAdapter(api, AuthorityHandle.issuedByHost("infinitygear"));
        ProcessEpoch epoch = new ProcessEpoch(UUID.randomUUID(), "alpha", Instant.EPOCH, Instant.EPOCH); PhysicalPresence wrong = new PhysicalPresence(UUID.randomUUID(), epoch, new PhysicalInstance("player:a:0"), "slot", Instant.EPOCH);
        assertTrue(adapter.observe(h.item, 0, wrong).isEmpty()); verify(api, never()).observe(any());
    }
    private static RegistrationResult result(RegistrationResult.Status status, UUID id) { return new RegistrationResult(status, new IdentitySnapshot(id, IdentityState.ACTIVE, IdentityOrigin.ADOPTED, "infinitygear", Instant.EPOCH, Instant.EPOCH, null)); }
    private static Harness newGear(String profile, int schema) { Harness h = new Harness(); h.put(GearData.KEY_MARKER,(byte)1); h.put(GearData.KEY_KIND, TrackedKind.GEAR.name()); h.put(GearData.KEY_UUID,h.id.toString()); h.put(GearData.KEY_PROFILE,profile); h.put(GearData.KEY_SCHEMA,schema); h.put(GearData.KEY_LEVEL,3); h.put(GearData.KEY_XP,Double.longBitsToDouble(0x3ff0000000000001L)); h.put(GearData.KEY_BLOCKS,8L); h.put(GearData.KEY_SOCKETS,2); return h; }
    private static final class Harness { final UUID id=UUID.randomUUID(); final Map<NamespacedKey,Object> values=new HashMap<>(); final PersistentDataContainer pdc=mock(PersistentDataContainer.class); final ItemMeta meta=mock(ItemMeta.class); final ItemStack item=mock(ItemStack.class); Harness(){when(item.hasItemMeta()).thenReturn(true);when(item.getItemMeta()).thenReturn(meta);when(meta.getPersistentDataContainer()).thenReturn(pdc);when(pdc.has(any(),any())).thenAnswer(c->value(c.getArgument(0),c.getArgument(1))!=null);when(pdc.get(any(),any())).thenAnswer(c->value(c.getArgument(0),c.getArgument(1)));when(pdc.getOrDefault(any(),any(),any())).thenAnswer(c->Optional.ofNullable(value(c.getArgument(0),c.getArgument(1))).orElse(c.getArgument(2)));} void put(NamespacedKey k,Object v){values.put(k,v);} Object value(NamespacedKey k,PersistentDataType<?,?> t){Object v=values.get(k);return v!=null&&t.getPrimitiveType().isInstance(v)?v:null;} static Harness legacy(String uuid){Harness h=new Harness();h.put(com.infinitypickaxes.core.pickaxe.PickaxeData.KEY_IS_INFINITY,(byte)1);h.put(com.infinitypickaxes.core.pickaxe.PickaxeData.KEY_UUID,uuid);h.put(com.infinitypickaxes.core.pickaxe.PickaxeData.KEY_LEVEL,2);h.put(com.infinitypickaxes.core.pickaxe.PickaxeData.KEY_XP,1.5);h.put(com.infinitypickaxes.core.pickaxe.PickaxeData.KEY_BLOCKS_MINED,9L);return h;} }
}

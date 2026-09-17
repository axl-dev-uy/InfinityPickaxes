package com.infinitygear.integration;

import com.axl.custodian.api.AuthorityHandle;
import com.axl.custodian.api.CustodianApi;
import com.axl.custodian.api.ObservationResult;
import com.axl.custodian.api.PhysicalPresence;
import com.axl.custodian.api.RegistrationResult;
import com.infinitygear.data.GearData;
import org.bukkit.inventory.ItemStack;
import java.util.Optional;

/** Opt-in shadow bridge: reads GearData unchanged and never participates in legacy enforcement. */
public final class CustodianShadowAdapter {
    private final CustodianApi custodian;
    private final AuthorityHandle authority;
    public CustodianShadowAdapter(CustodianApi custodian, AuthorityHandle authority, String serverId) { this.custodian = custodian; this.authority = authority; }
    public CustodianShadowAdapter(CustodianApi custodian, AuthorityHandle authority) { this(custodian, authority, "infinitygear-shadow"); }
    public Optional<RegistrationResult> adopt(ItemStack item, int legacySockets) {
        var gear = GearData.read(item, legacySockets, false);
        return gear.valid() ? Optional.of(custodian.adopt(authority, gear.gear().uuid())) : Optional.empty();
    }
    public Optional<ObservationResult> observe(ItemStack item, int legacySockets, PhysicalPresence presence) {
        var gear = GearData.read(item, legacySockets, false);
        if (!gear.valid() || !gear.gear().uuid().equals(presence.identity())) return Optional.empty();
        return Optional.of(custodian.observe(presence));
    }
}

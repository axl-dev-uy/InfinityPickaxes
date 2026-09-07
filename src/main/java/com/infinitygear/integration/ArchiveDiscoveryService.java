package com.infinitygear.integration;

import com.infinitygear.api.v1.ArchiveIntegrationService;
import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.Bukkit;
import java.util.*;

public final class ArchiveDiscoveryService implements ArchiveIntegrationService {
    private final InfinityPickaxes plugin;
    public ArchiveDiscoveryService(InfinityPickaxes plugin) { this.plugin = plugin; }

    @Override public Snapshot snapshot(int gearLevel) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        if (gearLevel < 0) throw new IllegalArgumentException("Negative level");
        var manager = plugin.getEnchantManager();
        var hook = manager.getEcoHook();
        var profiles = new ArrayList<Profile>();
        for (var profile : plugin.getGearProfiles().all().stream().sorted(Comparator.comparing(p -> p.id())).toList()) {
            var entries = new ArrayList<Enchantment>();
            for (var socket : plugin.getGearService().eligibleEnchantments(profile.id()).stream()
                    .sorted(Comparator.comparing(s -> s.getKeyString())).toList()) {
                var nativeEnchant = manager.getEnchantment(socket.getKeyString());
                if (nativeEnchant == null) continue;
                var eco = hook.findEcoEnchant(nativeEnchant);
                var policy = plugin.getGearService().resolveEnchantmentPolicy(profile.id(), socket.getKeyString(), gearLevel);
                if (policy.isEmpty()) continue;
                var p = policy.get();
                var targets = new TreeSet<String>();
                if (eco != null) eco.getTargets().forEach(t -> targets.add(t.getID()));
                else targets.addAll(profile.compatibleTargets());
                var conflicts = new TreeSet<String>();
                for (var other : manager.getAllSockets()) {
                    var candidate = manager.getEnchantment(other.getKeyString());
                    if (candidate != null && !candidate.equals(nativeEnchant)
                            && (nativeEnchant.conflictsWith(candidate) || candidate.conflictsWith(nativeEnchant)
                            || hook.conflictsWith(nativeEnchant, candidate))) conflicts.add(other.getKeyString());
                }
                entries.add(new Enchantment(socket.getKeyString(), socket.getDisplayName(),
                        eco == null ? nativeEnchant.getMaxLevel() : eco.getMaximumLevel(), targets, conflicts,
                        new Policy(p.enabled(), p.unlockLevel(), p.standardMaximum(), p.absoluteMaximum(),
                                p.socketCost(), p.removable(), p.additionalConflicts())));
            }
            profiles.add(new Profile(profile.id(), profile.enabled(), profile.displayName(), profile.compatibleTargets(), entries));
        }
        // Canonical ordering of unordered DTO fields keeps the revision stable across JVM restarts.
        var canonical = new StringBuilder().append(gearLevel);
        for (var p : profiles) {
            canonical.append(List.of(p.id(), p.enabled(), p.label(), new TreeSet<>(p.targets())));
            for (var e : p.enchantments()) canonical.append(List.of(e.key(), e.name(), e.nativeMaximum(),
                    new TreeSet<>(e.targets()), new TreeSet<>(e.conflicts()), e.policy().enabled(),
                    e.policy().unlockLevel(), e.policy().standardMaximum(), e.policy().absoluteMaximum(),
                    e.policy().socketCost(), e.policy().removable(), new TreeSet<>(e.policy().additionalConflicts())));
        }
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new Snapshot(HexFormat.of().formatHex(hash), gearLevel, profiles);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    @Override public Map<String, Capability> capabilities() {
        var producer = miningProducerCapabilities();
        var ordinary = producer.get(com.infinitygear.api.v1.MiningAuthority.Path.ORDINARY);
        var blast = producer.get(com.infinitygear.api.v1.MiningAuthority.Path.BLAST_MINING);
        var dynamite = producer.get(com.infinitygear.api.v1.MiningAuthority.Path.DYNAMITE);
        var vein = producer.get(com.infinitygear.api.v1.MiningAuthority.Path.VEIN_MINER);
        var setblock = producer.get(com.infinitygear.api.v1.MiningAuthority.Path.SETBLOCK);
        var natural = producer.get(com.infinitygear.api.v1.MiningAuthority.Path.BREAK_NATURALLY);
        boolean aoe = blast.supported() && dynamite.supported() && vein.supported();
        return Map.ofEntries(Map.entry("discovery", new Capability(true, "Poll snapshot revision on the server thread")),
                Map.entry("book-recovery", new Capability(plugin.getServer().getServicesManager().load(com.infinitygear.api.v1.BookIssuanceService.class) != null,
                        "Saved item recovery requires MariaDB; new issuance also requires provenance authority")),
                Map.entry("book-issuance", new Capability(plugin.getServer().getServicesManager().load(com.infinitygear.api.v1.BookIssuanceService.class) != null
                        && plugin.getServer().getServicesManager().load(com.infinitygear.api.v1.BookIssuanceService.ProvenanceAuthority.class) != null,
                        "Requires configured MariaDB journal and issuance authority")),
                Map.entry("book-lifecycle", new Capability(false, "Requires product policy and inventory participant recovery")),
                Map.entry("strict-mining", new Capability(false, "Producer capability is separate; XP adoption/custody and a durable receiver are not wired")),
                Map.entry("mining-setblock", new Capability(setblock.supported(), setblock.evidence())),
                Map.entry("mining-breakNaturally", new Capability(natural.supported(), natural.evidence())),
                Map.entry("mining-normal", new Capability(ordinary.supported(), ordinary.evidence())),
                Map.entry("mining-blast", new Capability(blast.supported(), blast.evidence())),
                Map.entry("mining-dynamite", new Capability(dynamite.supported(), dynamite.evidence())),
                Map.entry("mining-vein", new Capability(vein.supported(), vein.evidence())),
                Map.entry("mining-aoe", new Capability(aoe, aoe ? "All three AoE producer paths are fenced" : "Requires fenced Blast, Dynamite and Vein producer paths")),
                Map.entry("mining-world-edits", new Capability(false, "Direct/unobservable external edits remain outside the producer boundary")));
    }

    private EnumMap<com.infinitygear.api.v1.MiningAuthority.Path, com.infinitygear.api.v1.MiningAuthority.Capability> miningProducerCapabilities() {
        var result = new EnumMap<com.infinitygear.api.v1.MiningAuthority.Path, com.infinitygear.api.v1.MiningAuthority.Capability>(com.infinitygear.api.v1.MiningAuthority.Path.class);
        var unavailable = new com.infinitygear.api.v1.MiningAuthority.Capability(false, "No authoritative mining producer registered");
        for (var path : com.infinitygear.api.v1.MiningAuthority.Path.values()) result.put(path, unavailable);
        var authority = plugin.getServer().getServicesManager().load(com.infinitygear.api.v1.MiningAuthority.class);
        if (authority == null) return result;
        try { result.putAll(authority.capabilities()); }
        catch (RuntimeException invalid) {
            for (var path : com.infinitygear.api.v1.MiningAuthority.Path.values())
                result.put(path, new com.infinitygear.api.v1.MiningAuthority.Capability(false, "Mining producer capability query failed closed"));
        }
        return result;
    }
}

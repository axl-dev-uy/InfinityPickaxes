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
        return Map.of("discovery", new Capability(true, "Poll snapshot revision on the server thread"),
                "book-recovery", new Capability(plugin.getServer().getServicesManager().load(com.infinitygear.api.v1.BookIssuanceService.class) != null,
                        "Saved item recovery requires MariaDB; new issuance also requires provenance authority"),
                "book-issuance", new Capability(plugin.getServer().getServicesManager().load(com.infinitygear.api.v1.BookIssuanceService.class) != null
                        && plugin.getServer().getServicesManager().load(com.infinitygear.api.v1.BookIssuanceService.ProvenanceAuthority.class) != null,
                        "Requires configured MariaDB journal and issuance authority"),
                "book-lifecycle", new Capability(false, "Requires product policy and inventory participant recovery"),
                "strict-mining", new Capability(false, "Confirmed-only/fail-closed: AxMines guarded diagnostics lack validated mutation/protection authority; XP adoption/custody and durable receiver are not wired"),
                "mining-setblock", new Capability(false, "Direct setblock has no authoritative producer completion hook"),
                "mining-breakNaturally", new Capability(false, "breakNaturally has no authoritative producer completion hook"),
                "mining-normal", new Capability(false, "AxMines replacement/original listener timing and protection transparency require live acceptance"),
                "mining-aoe", new Capability(false, "Nested accounting, reset timing and actual Blast/Dynamite/Vein behavior require live acceptance"),
                "mining-world-edits", new Capability(false, "Unobservable external edits and placement/movement attribution have no authoritative completion evidence"));
    }
}

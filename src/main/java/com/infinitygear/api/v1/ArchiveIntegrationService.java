package com.infinitygear.api.v1;

import java.util.*;

/** Versioned, read-only discovery. Obtain through Bukkit ServicesManager; call on the server thread. */
public interface ArchiveIntegrationService {
    int API_VERSION = 1;
    Snapshot snapshot(int gearLevel);
    Map<String, Capability> capabilities();

    record Capability(boolean available, String reason) {}
    record Policy(boolean enabled, int unlockLevel, int standardMaximum, int absoluteMaximum,
                  int socketCost, boolean removable, Set<String> additionalConflicts) {
        public Policy { additionalConflicts = Set.copyOf(additionalConflicts); }
    }
    record Enchantment(String key, String name, int nativeMaximum, Set<String> targets,
                       Set<String> conflicts, Policy policy) {
        public Enchantment { targets = Set.copyOf(targets); conflicts = Set.copyOf(conflicts); }
    }
    record Profile(String id, boolean enabled, String label, Set<String> targets,
                   List<Enchantment> enchantments) {
        public Profile { targets = Set.copyOf(targets); enchantments = List.copyOf(enchantments); }
    }
    /** Revision is content-addressed, including disabled profiles and the requested policy level. */
    record Snapshot(String revision, int gearLevel, List<Profile> profiles) {
        public Snapshot { profiles = List.copyOf(profiles); }
    }
}

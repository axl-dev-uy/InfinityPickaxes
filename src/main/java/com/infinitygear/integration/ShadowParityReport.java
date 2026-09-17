package com.infinitygear.integration;

import com.infinitypickaxes.core.duplicate.DuplicateScanResult;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Immutable, diagnostic-only comparison of one settled legacy and Custodian scan. */
record ShadowParityReport(
        boolean agreement,
        Set<UUID> legacyIdentities,
        Set<UUID> custodianIdentities,
        Map<UUID, Integer> legacyPhysicalInstances,
        Map<UUID, Integer> custodianPhysicalInstances,
        Set<UUID> legacyDuplicates,
        Set<UUID> custodianDuplicates,
        Set<UUID> identityMismatches,
        Set<UUID> physicalInstanceMismatches,
        Set<UUID> duplicateDecisionMismatches) {

    static ShadowParityReport compare(
            DuplicateScanResult legacy,
            Map<UUID, Integer> custodianPhysicalInstances,
            Set<UUID> custodianDuplicates) {
        Map<UUID, Integer> legacyCounts = Map.copyOf(legacy.physicalInstanceCounts());
        Map<UUID, Integer> custodianCounts = Map.copyOf(custodianPhysicalInstances);
        Set<UUID> legacyIdentities = Set.copyOf(legacyCounts.keySet());
        Set<UUID> custodianIdentities = Set.copyOf(custodianCounts.keySet());
        Set<UUID> identityMismatches = symmetricDifference(legacyIdentities, custodianIdentities);

        Set<UUID> allIdentities = new LinkedHashSet<>(legacyIdentities);
        allIdentities.addAll(custodianIdentities);
        Set<UUID> countMismatches = new LinkedHashSet<>();
        for (UUID identity : allIdentities) {
            if (!legacyCounts.getOrDefault(identity, 0)
                    .equals(custodianCounts.getOrDefault(identity, 0))) {
                countMismatches.add(identity);
            }
        }

        Set<UUID> decisionMismatches = symmetricDifference(
                legacy.duplicatesDetected(), custodianDuplicates);
        boolean agreement = identityMismatches.isEmpty()
                && countMismatches.isEmpty()
                && decisionMismatches.isEmpty();
        return new ShadowParityReport(agreement, legacyIdentities, custodianIdentities,
                legacyCounts, custodianCounts, legacy.duplicatesDetected(), custodianDuplicates,
                identityMismatches, countMismatches, decisionMismatches);
    }

    String diagnostic() {
        return "event=custodian_shadow_parity result=" + (agreement ? "AGREEMENT" : "MISMATCH")
                + " legacy-identities=" + sorted(legacyIdentities)
                + " custodian-identities=" + sorted(custodianIdentities)
                + " legacy-physical-instances=" + sorted(legacyPhysicalInstances)
                + " custodian-physical-instances=" + sorted(custodianPhysicalInstances)
                + " legacy-duplicates=" + sorted(legacyDuplicates)
                + " custodian-duplicates=" + sorted(custodianDuplicates)
                + " identity-mismatches=" + sorted(identityMismatches)
                + " physical-instance-mismatches=" + sorted(physicalInstanceMismatches)
                + " duplicate-decision-mismatches=" + sorted(duplicateDecisionMismatches);
    }

    private static Set<UUID> symmetricDifference(Set<UUID> left, Set<UUID> right) {
        Set<UUID> difference = new LinkedHashSet<>(left);
        difference.addAll(right);
        Set<UUID> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        difference.removeAll(intersection);
        return Set.copyOf(difference);
    }

    private static Set<UUID> sorted(Set<UUID> values) {
        return new TreeSet<>(values);
    }

    private static Map<UUID, Integer> sorted(Map<UUID, Integer> values) {
        return new TreeMap<>(values);
    }
}

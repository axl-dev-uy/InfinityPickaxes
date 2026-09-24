package com.infinitygear.integration;

import com.infinitypickaxes.core.duplicate.DuplicateScanResult;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowParityReportTest {
    @Test
    void agreementRequiresMatchingIdentitiesPhysicalCountsAndDuplicateDecisions() {
        UUID unique = UUID.randomUUID();
        UUID duplicate = UUID.randomUUID();
        Map<UUID, Integer> counts = Map.of(unique, 1, duplicate, 2);

        ShadowParityReport report = ShadowParityReport.compare(
                new DuplicateScanResult(3, counts, Set.of(duplicate)), counts, Set.of(duplicate));

        assertTrue(report.agreement());
        assertTrue(report.diagnostic().contains("result=AGREEMENT"));
    }

    @Test
    void mismatchIdentifiesSetCountAndDecisionDifferences() {
        UUID missingFromCustodian = UUID.randomUUID();
        UUID countMismatch = UUID.randomUUID();
        UUID decisionMismatch = UUID.randomUUID();

        ShadowParityReport report = ShadowParityReport.compare(
                new DuplicateScanResult(4, Map.of(
                        missingFromCustodian, 1, countMismatch, 2, decisionMismatch, 1),
                        Set.of(decisionMismatch)),
                Map.of(countMismatch, 1, decisionMismatch, 1), Set.of());

        assertFalse(report.agreement());
        assertTrue(report.identityMismatches().contains(missingFromCustodian));
        assertTrue(report.physicalInstanceMismatches().contains(missingFromCustodian));
        assertTrue(report.physicalInstanceMismatches().contains(countMismatch));
        assertTrue(report.duplicateDecisionMismatches().contains(decisionMismatch));
        assertTrue(report.diagnostic().contains("result=MISMATCH"));
    }

    @Test
    void diagnosticOrderingIsDeterministicRegardlessOfInputOrder() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Map<UUID, Integer> legacyCounts = new LinkedHashMap<>();
        legacyCounts.put(second, 2);
        legacyCounts.put(first, 1);
        Map<UUID, Integer> custodianCounts = new LinkedHashMap<>();
        custodianCounts.put(second, 1);
        custodianCounts.put(first, 2);

        ShadowParityReport report = ShadowParityReport.compare(
                new DuplicateScanResult(3, legacyCounts,
                        new LinkedHashSet<>(java.util.List.of(second))),
                custodianCounts, new LinkedHashSet<>(java.util.List.of(first)));

        assertFalse(report.agreement());
        org.junit.jupiter.api.Assertions.assertEquals(
                "event=custodian_shadow_parity result=MISMATCH"
                        + " legacy-identities=[" + first + ", " + second + "]"
                        + " custodian-identities=[" + first + ", " + second + "]"
                        + " legacy-physical-instances={" + first + "=1, " + second + "=2}"
                        + " custodian-physical-instances={" + first + "=2, " + second + "=1}"
                        + " legacy-duplicates=[" + second + "]"
                        + " custodian-duplicates=[" + first + "]"
                        + " identity-mismatches=[]"
                        + " physical-instance-mismatches=[" + first + ", " + second + "]"
                        + " duplicate-decision-mismatches=[" + first + ", " + second + "]",
                report.diagnostic());
    }
}

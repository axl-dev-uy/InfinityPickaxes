package com.infinitypickaxes.core.duplicate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record DuplicateScanResult(
        int itemsScanned,
        Map<UUID, Integer> physicalInstanceCounts,
        Set<UUID> duplicatesDetected) {
    public DuplicateScanResult {
        physicalInstanceCounts = Map.copyOf(physicalInstanceCounts);
        duplicatesDetected = Set.copyOf(duplicatesDetected);
    }

    public DuplicateScanResult(int itemsScanned, Set<UUID> duplicatesDetected) {
        this(itemsScanned, Map.of(), duplicatesDetected);
    }

    public Set<UUID> identitiesObserved() {
        return physicalInstanceCounts.keySet();
    }
}

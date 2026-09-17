package com.infinitypickaxes.core.duplicate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Accumulates physical sightings without depending on Bukkit scanning mechanics. */
final class DuplicateObservations<T> {
    private final Map<UUID, List<Observation<T>>> byUuid = new LinkedHashMap<>();
    private int observedCopies;

    void observe(UUID uuid, T value, String location, int amount) {
        if (uuid == null || amount < 1) return;
        List<Observation<T>> sightings = byUuid.computeIfAbsent(uuid, ignored -> new ArrayList<>());
        for (int copy = 0; copy < amount; copy++) {
            String copyLocation = amount == 1 ? location : location + ":stack-copy=" + copy;
            sightings.add(new Observation<>(value, copyLocation));
            observedCopies++;
        }
    }

    int observedCopies() {
        return observedCopies;
    }

    Map<UUID, Integer> physicalInstanceCounts() {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        byUuid.forEach((identity, observations) -> counts.put(identity, observations.size()));
        return counts;
    }

    Map<UUID, List<Observation<T>>> entries() {
        return byUuid;
    }

    record Observation<T>(T value, String location) {}
}

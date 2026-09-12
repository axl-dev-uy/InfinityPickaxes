package com.infinitygear.mining;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MiningXpPlanTest {
    MiningXpPlan plan(MiningXpPlan.Progress before, double amount, List<Double> requirements) {
        return new MiningXpPlan(UUID.randomUUID(), "infinitygear:pickaxe", 0, before, amount, requirements);
    }
    @Test void capturedCostsSurviveReloadAndSupportMultipleLevels() {
        var costs = new ArrayList<>(List.of(100.0, 200.0, 400.0));
        var plan = plan(new MiningXpPlan.Progress(0, 90, 7), 260, costs);
        costs.clear();
        assertEquals(new MiningXpPlan.Progress(2, 50, 8), plan.after());
        assertEquals(plan, MiningXpPlan.decode(plan.encode()));
        assertThrows(UnsupportedOperationException.class, () -> plan.requiredXp().clear());
    }
    @Test void maximumLevelRetainsXpAndStillCountsOneMinedBlock() {
        var plan = plan(new MiningXpPlan.Progress(2, 17, 9), 1000, List.of(100.0, 200.0));
        assertEquals(new MiningXpPlan.Progress(2, 17, 10), plan.after());
    }
    @Test void invalidAndOverflowingPlansCannotCreateProgress() {
        var before = new MiningXpPlan.Progress(0, 0, 0);
        for (double value : List.of(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException.class, () -> plan(before, value, List.of(100.0)));
        assertThrows(IllegalArgumentException.class, () -> plan(before, 1, List.of(0.0)));
        assertThrows(IllegalArgumentException.class, () -> plan(new MiningXpPlan.Progress(0, Double.MAX_VALUE, 0), Double.MAX_VALUE, List.of(100.0)).after());
        assertThrows(ArithmeticException.class, () -> plan(new MiningXpPlan.Progress(0, 0, Long.MAX_VALUE), 1, List.of(100.0)).after());
    }
    @Test void invalidPersistedPayloadFailsClosed() {
        var encoded = plan(new MiningXpPlan.Progress(0, 0, 0), 1, List.of(100.0)).encode();
        assertThrows(IllegalArgumentException.class, () -> MiningXpPlan.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> MiningXpPlan.decode(Arrays.copyOf(encoded, encoded.length + 1)));
        encoded[3] = 2;
        assertThrows(IllegalArgumentException.class, () -> MiningXpPlan.decode(encoded));
    }
}

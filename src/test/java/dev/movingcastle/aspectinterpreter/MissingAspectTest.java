package dev.movingcastle.aspectinterpreter;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 1.11.1: aspects the pack doesn't define are dropped from a mob guess before the tier clamp, so the clamp holds at serve time. */
class MissingAspectTest {
    private static final String SOMNIUM = "madmans_dreams:somnium", AVERSIO = "thaumaturge:aversio", HUMANUS = "thaumaturge:humanus";

    private static Map<String, Double> insanity() {
        Map<String, Double> g = new LinkedHashMap<>();
        g.put(SOMNIUM, 10.0);
        g.put(AVERSIO, 2.0);
        g.put(HUMANUS, 15.0);
        return g;
    }

    private static Set<String> available() { return Set.of(AVERSIO, HUMANUS, "thaumaturge:ignis"); }

    private static int total(Map<String, ? extends Number> m) { return m.values().stream().mapToInt(Number::intValue).sum(); }

    /** What InterpreterContributor.build does to a table: drop missing, rescale the rest. */
    private static Map<String, Integer> simulateBuild(Map<String, Integer> t, Set<String> avail) {
        int total = 0, dropped = 0;
        for (var e : t.entrySet()) { total += e.getValue(); if (!avail.contains(e.getKey())) dropped += e.getValue(); }
        Map<String, Integer> out = new LinkedHashMap<>();
        double scale = dropped > 0 ? (double) total / (total - dropped) : 1;
        for (var e : t.entrySet()) if (avail.contains(e.getKey())) out.put(e.getKey(), dropped > 0 ? (int) Math.max(1, Math.round(e.getValue() * scale)) : e.getValue());
        return out;
    }

    @Test
    void insanityLosesSomniumAndKeepsItsTotal() {
        Map<String, Double> r = MissingAspects.dropMissing(insanity(), available());
        assertFalse(r.containsKey(SOMNIUM));
        assertEquals(27, total(r), 1);
        assertEquals(List.of(AVERSIO, HUMANUS), new ArrayList<>(r.keySet()));
    }

    @Test
    void clampAfterDropMeansServeTimeChangesNothing() {
        Map<String, Double> dropped = MissingAspects.dropMissing(insanity(), available());
        Map<String, Integer> guess = new LinkedHashMap<>();
        dropped.forEach((a, x) -> guess.put(a, (int) Math.round(x)));
        MobTiers.Caps weak = new MobTiers.Caps(48, Map.of(HUMANUS, 15), 15);
        Map<String, Integer> clamped = MobTiers.clamp(guess, weak);
        clamped.forEach((a, x) -> assertTrue(x <= weak.cap(a), a));
        assertTrue(total(clamped) <= 48);
        assertEquals(clamped, simulateBuild(clamped, available()));
    }

    @Test
    void emptyAvailableMeansUnknownAndAllMissingGivesEmpty() {
        Map<String, Double> g = insanity();
        assertSame(g, MissingAspects.dropMissing(g, Set.of()));
        assertSame(g, MissingAspects.dropMissing(g, Set.of(SOMNIUM, AVERSIO, HUMANUS)));
        assertTrue(MissingAspects.dropMissing(g, Set.of("thaumaturge:ignis")).isEmpty());
    }

    @Test
    void insertionOrderDoesNotChangeTheResult() {
        Map<String, Double> rev = new LinkedHashMap<>();
        rev.put(HUMANUS, 15.0);
        rev.put(AVERSIO, 2.0);
        rev.put(SOMNIUM, 10.0);
        assertEquals(MissingAspects.dropMissing(insanity(), available()), MissingAspects.dropMissing(rev, available()));
    }
}

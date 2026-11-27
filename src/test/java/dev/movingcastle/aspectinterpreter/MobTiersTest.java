package dev.movingcastle.aspectinterpreter;

import org.junit.jupiter.api.Test;

import java.util.*;

import static dev.movingcastle.aspectinterpreter.MobTiers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 1.11.0 requirement 3: a guessed mob never exceeds the hand-written mobs of its threat tier (total and per aspect). No Minecraft needed. */
class MobTiersTest {
    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put("thaumaturge:" + kv[i], (Integer) kv[i + 1]);
        return out;
    }

    private static int total(Map<String, Integer> x) { return x.values().stream().mapToInt(Integer::intValue).sum(); }

    @Test
    void tiersFollowStatsHostilityAndBossStatus() {
        assertEquals(Tier.PASSIVE, classify(new Stats(10, 2, false, false)));
        assertEquals(Tier.PASSIVE, classify(new Stats(53, 2, false, false)), "a big passive animal stays passive");
        assertEquals(Tier.WEAK, classify(new Stats(20, 2, true, false)));
        assertEquals(Tier.STRONG, classify(new Stats(24, 5, true, false)));
        assertEquals(Tier.STRONG, classify(new Stats(40, 2, true, false)));
        assertEquals(Tier.MINI_BOSS, classify(new Stats(60, 2, true, false)));
        assertEquals(Tier.MINI_BOSS, classify(new Stats(30, 15, false, false)), "an iron golem hits like a mini-boss");
        assertEquals(Tier.BOSS, classify(new Stats(200, 2, true, true)));
    }

    @Test
    void ceilingsComeFromTheTiersHandWrittenMobs() {
        List<Sample> hand = List.of(
                new Sample(Tier.WEAK, m("aversio", 10, "bestia", 5)),          // total 15
                new Sample(Tier.WEAK, m("aversio", 20, "corpus", 10)),         // total 30, aversio max 20
                new Sample(Tier.STRONG, m("aversio", 40, "bestia", 10)));
        Map<Tier, Caps> caps = capsFrom(hand);
        Caps weak = caps.get(Tier.WEAK);
        assertEquals(30, weak.total());
        assertEquals(20, weak.cap("thaumaturge:aversio"));
        assertEquals(5, weak.cap("thaumaturge:bestia"));
        assertEquals(20, weak.cap("thaumaturge:unseen"), "an aspect no mob of the tier carries: the tier's largest single amount");
        assertNull(caps.get(Tier.PASSIVE), "nothing below the lowest tier to borrow from");
        assertSame(caps.get(Tier.STRONG), caps.get(Tier.MINI_BOSS), "a tier without hand-written mobs takes the next lower tier's");
        assertSame(caps.get(Tier.STRONG), caps.get(Tier.BOSS));
    }

    @Test
    void anOutlierDoesNotSetTheCeiling() {
        List<Sample> hand = List.of(
                new Sample(Tier.PASSIVE, m("aversio", 100, "bestia", 100)),    // total 200: an outlier (more than twice the next)
                new Sample(Tier.PASSIVE, m("aversio", 40, "bestia", 20)),      // total 60
                new Sample(Tier.PASSIVE, m("aversio", 30)),                    // total 30
                new Sample(Tier.WEAK, m("aversio", 30, "corpus", 30)),         // total 60
                new Sample(Tier.WEAK, m("aversio", 15, "corpus", 15)));        // total 30: exactly half, not an outlier
        Map<Tier, Caps> caps = capsFrom(hand);
        Caps passive = caps.get(Tier.PASSIVE);
        assertEquals(60, passive.total(), "total: the next highest, not 200");
        assertEquals(40, passive.cap("thaumaturge:aversio"), "per aspect: 100 is more than twice 40");
        assertEquals(20, passive.cap("thaumaturge:bestia"), "100 is more than twice the next, 20");
        assertEquals(100, passive.maxSingle(), "the fallback keeps the tier's largest single amount");
        assertEquals(60, caps.get(Tier.WEAK).total(), "twice the next is not an outlier");
        assertEquals(30, caps.get(Tier.WEAK).cap("thaumaturge:corpus"));
    }

    @Test
    void aGuessedMobIsClampedInTotalAndPerAspect() {
        Caps caps = new Caps(30, Map.of("thaumaturge:aversio", 20, "thaumaturge:bestia", 5), 20);
        // per aspect: aversio 100 -> 20, bestia 50 -> 5, an unseen aspect -> the tier's largest single (20)
        Map<String, Integer> guess = m("aversio", 100, "bestia", 50, "metallum", 90);
        Map<String, Integer> out = clamp(guess, caps);
        assertTrue(out.get("thaumaturge:aversio") <= 20 && out.get("thaumaturge:bestia") <= 5 && out.get("thaumaturge:metallum") <= 20, out.toString());
        assertTrue(total(out) <= 30, "total held at the tier maximum: " + out);
        assertEquals(guess.keySet(), out.keySet(), "scaling keeps every aspect when it can");
        // within the ceilings nothing changes, and the very same map comes back
        Map<String, Integer> small = m("aversio", 10, "bestia", 3);
        assertSame(small, clamp(small, caps));
    }

    @Test
    void weakestAspectsAreDroppedOnlyWhenScalingIsNotEnough() {
        Caps caps = new Caps(3, Map.of(), 3);
        Map<String, Integer> out = clamp(m("a", 9, "b", 8, "c", 7, "d", 6, "e", 1), caps);
        assertTrue(total(out) <= 3, out.toString());
        assertFalse(out.isEmpty());
        // deterministic: the same guess always clamps to the same result
        assertEquals(out, clamp(m("a", 9, "b", 8, "c", 7, "d", 6, "e", 1), caps));
    }

    @Test
    void modelPredictionDoesNotDependOnTrainingInsertionOrder() {
        List<String> ids = new ArrayList<>();
        for (String c : List.of("red", "blue", "green", "white", "black", "pink"))
            for (String s : List.of("brick", "slab", "stairs", "wall")) ids.add("modx:" + c + "_" + s);
        String[] results = new String[2];
        for (int run = 0; run < 2; run++) {
            List<String> order = new ArrayList<>(ids);
            Collections.shuffle(order, new Random(run));
            Map<String, Map<String, Double>> train = new LinkedHashMap<>();
            for (String id : order) {
                Map<String, Double> v = new LinkedHashMap<>();
                if (run == 0) { v.put("thaumaturge:terra", 8.0); v.put("thaumaturge:ordo", 3.0); v.put("thaumaturge:vitreus", 3.0); }
                else { v.put("thaumaturge:vitreus", 3.0); v.put("thaumaturge:ordo", 3.0); v.put("thaumaturge:terra", 8.0); }
                train.put(id, v);
            }
            results[run] = new Model(train, Lexicon.load()).predict("mody:yellow_brick", List.of(), 8, null).aspects().toString();
        }
        assertEquals(results[0], results[1]);
    }
}

package dev.movingcastle.aspectinterpreter;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 1.9.0: guesses can no longer inflate (sizing, cap, kinds, and the learning loop). No Minecraft needed. */
class InflationGuardTest {
    private static final String T = "thaumaturge:";

    private static Map<String, Double> a(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(T + kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    private static int total(Model.Prediction p) {
        return p.aspects().values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void weightedMedianIgnoresOneOutlier() {
        List<double[]> l = List.of(new double[]{10, 1}, new double[]{12, 1}, new double[]{2000, 1.2}, new double[]{11, 1});
        assertEquals(12, Model.weightedMedian(l));
    }

    @Test
    void oneInflatedNeighbourDoesNotSetTheSize() {
        Map<String, Map<String, Double>> train = new HashMap<>();
        for (String c : List.of("red", "blue", "green", "white"))
            train.put("modx:" + c + "_sulfur_brick", a("terra", 8, "alkimia", 2));
        train.put("modx:black_sulfur_brick", a("terra", 1500, "alkimia", 500)); // a second-hand inflated value
        Model m = new Model(train, Lexicon.load());
        Model.Prediction p = m.predict("modx:yellow_sulfur_brick", List.of(), 8, null);
        assertNotNull(p);
        assertTrue(total(p) <= 12, "size follows the typical neighbour, got " + p.aspects());
    }

    @Test
    void nameBasedTotalsAreCappedAtTheHandWrittenMax() {
        Map<String, Map<String, Double>> train = new HashMap<>();
        for (int i = 0; i < 5; i++) train.put("modx:mithminite_part_" + (char) ('a' + i), a("metallum", 900, "praecantatio", 600));
        Model m = new Model(train, Lexicon.load()).withMaxTotal(100);
        Model.Prediction p = m.predict("modx:mithminite_part_z", List.of(), 8, null);
        assertTrue(total(p) <= 101, "capped, got " + p.aspects()); // rounding may add one
        // a forced (recipe or creature) total is Thaumaturge's own rule and is not capped
        Model.Prediction forced = m.predict("modx:mithminite_part_z", List.of(), 8, 400.0);
        assertTrue(total(forced) >= 390);
    }

    @Test
    void neighboursOfAnotherKindCountLess() {
        Map<String, Map<String, Double>> train = new HashMap<>();
        Map<String, String> kinds = new HashMap<>();
        for (String metal : List.of("iron", "copper", "gold", "tin", "lead")) {
            train.put("modx:raw_" + metal, a("metallum", 10, "terra", 5));
            kinds.put("modx:raw_" + metal, "item");
        }
        for (String meat : List.of("beef", "mutton", "chicken")) {
            train.put("modx:raw_" + meat, a("victus", 6, "bestia", 4));
            kinds.put("modx:raw_" + meat, "food");
        }
        Model m = new Model(train, Lexicon.load()).withKinds(kinds);
        Model.Prediction food = m.predict("mody:raw_bison", List.of(), 8, null, Set.of(), "food");
        String top = food.aspects().keySet().iterator().next();
        assertNotEquals(T + "metallum", top, "raw meat is not raw ore: " + food.aspects());
        assertFalse(food.aspects().getOrDefault(T + "metallum", 0) > food.aspects().getOrDefault(T + "victus", 0));
        // blocks and plain items stay one kind, so a material still pairs with its block
        assertEquals(Model.coarseKind("item"), Model.coarseKind("block:stone"));
    }

    @Test
    void cleanTrainingIsReusedOnlyWhileTheCacheFingerprintMatches() {
        Map<String, Map<String, Double>> clean = Map.of("minecraft:netherite_chestplate", a("metallum", 40, "praecantatio", 12));
        String json = CleanPass.toJson("abc123", clean);
        assertEquals(clean, CleanPass.fromJson(json, "abc123"));
        assertNull(CleanPass.fromJson(json, "other"), "a different index must not reuse the saved values");
        String cache = "{\"fingerprint\":\"abc123\",\"item_count\":3,\"aspects\":{}}";
        assertEquals("abc123", CleanPass.headerFingerprint(cache));
        assertEquals("", CleanPass.headerFingerprint("{\"aspects\":{}}"));
        assertFalse(CleanPass.needsRebuild("", "", ""), "no cache: a build is coming anyway");
        assertFalse(CleanPass.needsRebuild("abc", "abc", ""), "in-memory values match");
        assertFalse(CleanPass.needsRebuild("abc", "", "abc"), "saved values match");
        assertTrue(CleanPass.needsRebuild("abc", "old", ""), "valid cache, no matching clean values: rebuild");
        assertTrue(CleanPass.needsRebuild("abc", "", "old"));
    }
}

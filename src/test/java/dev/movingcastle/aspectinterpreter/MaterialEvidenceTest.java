package dev.movingcastle.aspectinterpreter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Option B: a material word counts only when the item corroborates it. No Minecraft needed. */
class MaterialEvidenceTest {
    private static final String T = "thaumaturge:";
    private static Lexicon lex;
    private static Model model;
    /** Stand-in for "has a wood sound / tag / ingredient": ids that are really wooden. */
    private static final Set<String> WOODEN = new HashSet<>();

    private static void add(Map<String, Map<String, Double>> m, String id, Object... aspects) {
        Map<String, Double> a = new LinkedHashMap<>();
        for (int i = 0; i < aspects.length; i += 2) a.put(T + aspects[i], ((Number) aspects[i + 1]).doubleValue());
        m.put(id, a);
    }

    @BeforeAll
    static void build() {
        lex = Lexicon.load();
        Map<String, Map<String, Double>> train = new HashMap<>();
        for (String w : List.of("crimson", "warped", "oak", "spruce", "birch")) {
            for (String s : List.of("planks", "stairs", "slab", "stem", "fence", "trapdoor", "door", "button", "sign")) {
                String id = "minecraft:" + w + "_" + s;
                add(train, id, "herba", 4, "fabrico", 1);
            }
        }
        for (String m : List.of("iron", "golden", "diamond", "stone", "wooden", "netherite")) {
            add(train, "minecraft:" + m + "_sword", "metallum", 6, "aversio", 4);
            add(train, "minecraft:" + m + "_helmet", "praemunio", 5, "metallum", 4);
            add(train, "minecraft:" + m + "_chestplate", "praemunio", 7, "metallum", 6);
            add(train, "minecraft:" + m + "_leggings", "praemunio", 6, "metallum", 5);
        }
        for (String c : List.of("white", "red", "blue", "green", "black", "yellow"))
            add(train, "minecraft:" + c + "_banner", "bestia", 6, "fabrico", 4);
        for (String f : List.of("rotten_flesh", "porkchop", "beef", "mutton", "chicken", "cod", "salmon", "rabbit"))
            add(train, "minecraft:" + f, "victus", 5, "bestia", 3);
        for (String b : List.of("void", "thaumium", "steel", "obsidian"))
            add(train, "thaumaturge:" + b + "_blade", "metallum", 6, "aversio", 4);
        for (String f : List.of("fish_mixture", "bone_muscle", "raw_muscle", "meat_mixture"))
            add(train, "other:" + f, "victus", 5, "bestia", 3);
        for (String b : List.of("cult", "sigil", "rune", "ritual")) add(train, T + b + "_stone", "praecantatio", 5, "terra", 2);
        lex.calibrate(train);
        model = new Model(train, lex);
        for (String id : train.keySet()) if (id.contains("planks") || id.contains("stairs") || id.contains("stem") || id.contains("trapdoor")) WOODEN.add(id);
        WOODEN.addAll(List.of("minecraft:crimson_planks", "minecraft:crimson_stem", "minecraft:crimson_stairs", "minecraft:warped_stem",
                "minecraft:oak_planks", "minecraft:oak_log", "mcwfences:crimson_picket_fence"));
    }

    private static Model.Prediction guess(String id, boolean useEvidence) {
        Set<String> ignored = useEvidence
                ? Materials.uncorroborated(lex, Tokens.of(id), m -> WOODEN.contains(id))
                : Set.of();
        return model.predict(id, null, 8, null, ignored);
    }

    private static String top(Model.Prediction p) {
        return p.aspects().keySet().iterator().next();
    }

    @Test
    void bugReproducesWithoutEvidenceCheck() {
        assertEquals(T + "herba", top(guess("thaumaturge:crimson_blade", false)), "fixture should show the original bug");
    }

    @Test
    void crimsonBladeIsNotHerba() {
        assertNotEquals(T + "herba", top(guess("thaumaturge:crimson_blade", true)));
        assertTrue(guess("thaumaturge:crimson_blade", true).aspects().containsKey(T + "metallum"));
    }

    @Test
    void crimsonHelmsAreNotHerba() {
        for (String id : List.of("thaumaturge:crimson_captain_helm", "thaumaturge:crimson_ranger_helm"))
            assertNotEquals(T + "herba", top(guess(id, true)), id);
    }

    @Test
    void crimsonRobesAreNotHerbaOrAlienis() {
        for (String id : List.of("thaumaturge:crimson_captain_chest", "thaumaturge:crimson_ranger_chest", "thaumaturge:crimson_ranger_legs")) {
            Model.Prediction p = guess(id, true);
            assertNotEquals(T + "herba", top(p), id);
            assertFalse(p.aspects().containsKey(T + "herba"), id);
        }
    }

    @Test
    void crimsonBannerIsNotHerba() {
        Model.Prediction p = guess("thaumaturge:banner_crimson_cult", true);
        assertNotEquals(T + "herba", top(p));
        assertEquals(T + "bestia", top(p), "banner should read like other banners");
    }

    @Test
    void warpedFoodIsNotHerba() {
        for (String id : List.of("alexsmobs:warped_mixture", "alexsmobs:warped_muscle")) {
            Model.Prediction p = guess(id, true);
            assertFalse(p.aspects().containsKey(T + "herba"), id + " " + p.aspects());
        }
    }

    @Test
    void realWoodStaysWood() {
        for (String id : List.of("minecraft:crimson_planks", "minecraft:crimson_stem", "minecraft:crimson_stairs", "minecraft:warped_stem", "minecraft:oak_planks")) {
            assertTrue(WOODEN.contains(id));
            assertTrue(Materials.uncorroborated(lex, Tokens.of(id), m -> WOODEN.contains(id)).isEmpty(), id);
            assertEquals(T + "herba", top(guess(id, true)), id);
        }
    }

    @Test
    void modWoodFurnitureWithEvidenceStaysWood() {
        // a furniture id the model has never seen, but with a wood sound / tag: herba must still lead
        Model.Prediction p = guess("mcwfences:crimson_picket_fence", true);
        assertEquals(T + "herba", top(p));
    }

    @Test
    void unrelatedItemsAreUntouched() {
        assertTrue(Materials.uncorroborated(lex, Tokens.of("minecraft:iron_sword"), m -> false).isEmpty());
        assertTrue(Materials.uncorroborated(lex, Tokens.of("thaumaturge:cult_stone"), m -> false).isEmpty());
    }

    @Test
    void uncorroboratedWordsAreReportedAndEvidenceIsLazy() {
        int[] asked = {0};
        Set<String> r = Materials.uncorroborated(lex, Tokens.of("thaumaturge:crimson_blade"), m -> { asked[0]++; return false; });
        assertEquals(Set.of("crimson"), r);
        assertEquals(1, asked[0]);
        asked[0] = 0;
        Materials.uncorroborated(lex, Tokens.of("minecraft:iron_sword"), m -> { asked[0]++; return false; });
        assertEquals(0, asked[0], "no material word, so evidence is never gathered");
    }

    @Test
    void tagPatterns() {
        List<String> p = lex.materials.get("wood").tags();
        assertTrue(Materials.tagMatches(p, "minecraft:planks"));
        assertTrue(Materials.tagMatches(p, "minecraft:wooden_stairs"));
        assertTrue(Materials.tagMatches(p, "minecraft:crimson_stems"));
        assertFalse(Materials.tagMatches(p, "minecraft:swords"));
        assertFalse(Materials.tagMatches(p, "c:ingots/iron"));
    }
}

package dev.movingcastle.aspectinterpreter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** config/aspect_interpreter.json */
final class Config {
    boolean items = true;
    boolean entities = true;
    /** Also learn from Thaumaturge's own resolved index cache (config/thaumaturge/aspect_index.json), excluding this mod's guesses. */
    boolean learnFromThaumaturgeCache = true;
    /** 0..1. Guesses below this are left unassigned. Lexicon-only guesses usually score 0.1-0.3. */
    double minConfidence = 0.15;
    /** Write config/aspect_interpreter/assignments.txt after each reload so you can review guesses. */
    boolean writeReport = true;
    /** Add a missing function aspect (container -> vacuos, lamp -> lux...) to items the interpreter fills in. */
    boolean functionLayer = true;
    /** Add craftsmanship (ordo/fabrico) for each crafting step beyond the first refinement, on non-canon items made at the crafting table. */
    boolean craftingComplexity = true;
    /** Enrich mobs from their habitat, dimension, behaviour and drops. The only layer that adds to hand-written entries. */
    boolean mobEnrichment = true;
    /**
     * Mods whose aspects are canon: learned from, never added to. Trailing * matches a prefix. Any other mod that ships
     * its own Thaumaturge aspect files is detected and treated the same way automatically.
     */
    List<String> canonNamespaces = new ArrayList<>(List.of("minecraft", "thaumaturge", "thaumadditions", "thaumaturgeadditions",
            "thaumaturgetweaks", "madmans_dreams", "thaumic_aeronautics", "thaumaturge_node_navigator", "magicbees",
            "create", "create_*", "createaddition"));
    /** Use where an object spawns, generates or drops (which dimension) as evidence. */
    boolean dimensionContext = true;
    /** Dimensions that carry no special meaning. */
    List<String> baselineDimensions = new ArrayList<>(List.of("minecraft:overworld"));
    /** Optional fixed signatures, e.g. {"minecraft:the_end": {"thaumaturge:alienis": 1.0}}. Replaces the learned one. */
    Map<String, Map<String, Double>> dimensionAspects = new HashMap<>();
    List<String> excludedNamespaces = new ArrayList<>();
    List<String> excludedIds = new ArrayList<>(List.of(
            "minecraft:air", "minecraft:barrier", "minecraft:light", "minecraft:structure_void", "minecraft:structure_block",
            "minecraft:jigsaw", "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:command_block_minecart", "minecraft:debug_stick", "minecraft:knowledge_book",
            "minecraft:marker", "minecraft:interaction", "minecraft:block_display", "minecraft:item_display", "minecraft:text_display",
            "minecraft:area_effect_cloud", "minecraft:lightning_bolt", "minecraft:item", "minecraft:falling_block",
            "minecraft:leash_knot", "minecraft:player", "minecraft:ominous_item_spawner"));
    List<String> excludedWords = new ArrayList<>(List.of("debug", "creative", "dev", "test"));

    private static volatile Config instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static Config get() {
        Config c = instance;
        return c != null ? c : load();
    }

    private static synchronized Config load() {
        if (instance == null) {
            Path p = FMLPaths.CONFIGDIR.get().resolve("aspect_interpreter.json");
            try {
                if (Files.exists(p)) instance = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Config.class);
            } catch (Exception e) {
                AspectInterpreter.LOG.error("Bad config at {}, using defaults", p, e);
            }
            if (instance == null) instance = new Config();
            try {
                Files.writeString(p, GSON.toJson(instance), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return instance;
    }

    boolean excluded(String id) {
        if (excludedIds.contains(id) || excludedNamespaces.contains(Tokens.namespace(id))) return true;
        for (String t : Tokens.of(id)) if (excludedWords.contains(t)) return true;
        return false;
    }
}

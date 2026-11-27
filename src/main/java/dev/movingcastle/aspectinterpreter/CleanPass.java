package dev.movingcastle.aspectinterpreter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.content.aspect.AspectIndex;
import com.leclowndu93150.thaumaturge.content.aspect.AspectIndexBuilder;
import com.leclowndu93150.thaumaturge.content.aspect.AspectIndexFingerprint;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The clean pass: at the start of each Thaumaturge index build this mod runs Thaumaturge's own derivation once more
 * with everything of ours switched off ({@link #active()}), and the model trains on that result in the same launch.
 * Thaumaturge's own cache file is never read for learning, because it holds this mod's guesses.
 *
 * <p>When Thaumaturge's cache is valid no build happens and the clean values are not recomputed; they are kept in memory
 * and in {@code config/aspect_interpreter/clean_training.json}, stamped with the fingerprint Thaumaturge stores in the
 * header of {@code aspect_index.json}, and reused only while the two match.
 */
public final class CleanPass {
    private CleanPass() {}

    private static volatile boolean active;
    private static volatile Map<String, Map<String, Double>> memory = Map.of();
    private static volatile String memoryFingerprint = "";
    private static boolean warned;

    /** True while the clean pass runs: our contributor and mixins must leave Thaumaturge's result untouched. */
    public static boolean active() { return active; }

    /** The clean values the model last trained on (item id -> aspect -> amount); read by the pack test. */
    public static Map<String, Map<String, Double>> cleanValues() { return memory; }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("aspect_interpreter").resolve("clean_training.json");
    }

    /** Runs Thaumaturge's derivation with this mod off and returns item id -> aspect -> amount. Empty on failure. */
    static Map<String, Map<String, Double>> run(RecipeManager recipes, HolderLookup.Provider registries, MinecraftServer server) {
        long t0 = System.nanoTime();
        Map<String, Map<String, Double>> out = new HashMap<>();
        active = true;
        try {
            AspectIndex index = AspectIndexBuilder.build(recipes, registries);
            for (Item item : BuiltInRegistries.ITEM) {
                AspectList list = index.of(item);
                if (list == null || list.isEmpty()) continue;
                Map<String, Double> m = Knowledge.toMap(list);
                if (!m.isEmpty()) out.put(BuiltInRegistries.ITEM.getKey(item).toString(), m);
            }
        } catch (Throwable t) {
            AspectInterpreter.LOG.warn("Aspect Interpreter's clean pass failed; learning from data maps only this launch", t);
            out.clear();
        } finally {
            active = false;
        }
        String fingerprint = "";
        try {
            if (server != null) fingerprint = AspectIndexFingerprint.compute(server).digest();
        } catch (Throwable t) {
            AspectInterpreter.LOG.debug("Aspect Interpreter could not fingerprint the index", t);
        }
        memory = out;
        memoryFingerprint = fingerprint;
        if (!fingerprint.isEmpty() && !out.isEmpty()) save(fingerprint, out);
        AspectInterpreter.LOG.info("Aspect Interpreter: clean pass over {} items in {} ms (learning from Thaumaturge's own values only)",
                out.size(), (System.nanoTime() - t0) / 1_000_000);
        return out;
    }

    /**
     * The training values outside a build: the in-memory or saved clean map if its fingerprint equals the one in
     * Thaumaturge's cache header, otherwise nothing (data maps only).
     */
    static Map<String, Map<String, Double>> training() {
        String cacheFp = cacheFingerprint();
        if (cacheFp.isEmpty()) return Map.of(); // no cache: a build is about to run the clean pass
        if (cacheFp.equals(memoryFingerprint) && !memory.isEmpty()) return memory;
        Map<String, Map<String, Double>> saved = load(cacheFp);
        if (saved != null) {
            memory = saved;
            memoryFingerprint = cacheFp;
            return saved;
        }
        if (!warned) {
            warned = true;
            AspectInterpreter.LOG.warn("Aspect Interpreter: Thaumaturge's index cache is valid but no clean training values match it "
                    + "(config/aspect_interpreter/clean_training.json is missing or from another index); learning from data maps only until the next index build");
        }
        return Map.of();
    }

    /**
     * Must Thaumaturge's cache be moved aside so the next start builds (and so runs the clean pass)? Yes when a cache
     * exists and neither the in-memory nor the saved clean values carry its fingerprint. No cache: a build is coming anyway.
     */
    static boolean needsRebuild(String cacheFp, String memoryFp, String savedFp) {
        if (cacheFp == null || cacheFp.isEmpty()) return false;
        return !cacheFp.equals(memoryFp) && !cacheFp.equals(savedFp);
    }

    /** Called before Thaumaturge decides whether to build: true if its valid cache has no matching clean training values. */
    static boolean cacheLacksCleanValues() {
        if (!Config.get().learnFromThaumaturgeCache) return false;
        String cacheFp = cacheFingerprint();
        String savedFp = "";
        try {
            Path p = file();
            if (Files.exists(p)) {
                JsonElement f = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject().get("fingerprint");
                if (f != null) savedFp = f.getAsString();
            }
        } catch (Exception ignored) {
            // unreadable file: treated as missing
        }
        return needsRebuild(cacheFp, memoryFingerprint, savedFp);
    }

    /** The {@code fingerprint} in the header of Thaumaturge's aspect_index.json, or "" if there is none. */
    private static String cacheFingerprint() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve("thaumaturge").resolve("aspect_index.json");
            if (!Files.exists(p)) return "";
            return headerFingerprint(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    /** Pulls the top-level fingerprint out of a Thaumaturge cache file. Plain Java for tests. */
    static String headerFingerprint(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement f = root.get("fingerprint");
        return f != null && f.isJsonPrimitive() ? f.getAsString() : "";
    }

    static String toJson(String fingerprint, Map<String, Map<String, Double>> items) {
        JsonObject root = new JsonObject();
        root.addProperty("fingerprint", fingerprint);
        JsonObject all = new JsonObject();
        items.forEach((id, aspects) -> {
            JsonObject o = new JsonObject();
            aspects.forEach(o::addProperty);
            all.add(id, o);
        });
        root.add("items", all);
        return root.toString();
    }

    /** The saved items if the file's fingerprint equals {@code fingerprint}, else null. */
    static Map<String, Map<String, Double>> fromJson(String json, String fingerprint) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement f = root.get("fingerprint");
        if (f == null || !f.getAsString().equals(fingerprint)) return null;
        Map<String, Map<String, Double>> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("items").entrySet()) {
            Map<String, Double> m = new HashMap<>();
            e.getValue().getAsJsonObject().entrySet().forEach(a -> m.put(a.getKey(), a.getValue().getAsDouble()));
            if (!m.isEmpty()) out.put(e.getKey(), m);
        }
        return out.isEmpty() ? null : out;
    }

    private static void save(String fingerprint, Map<String, Map<String, Double>> items) {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            Files.writeString(p, toJson(fingerprint, items), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AspectInterpreter.LOG.warn("Aspect Interpreter could not save its clean training values", e);
        }
    }

    private static Map<String, Map<String, Double>> load(String fingerprint) {
        try {
            Path p = file();
            if (!Files.exists(p)) return null;
            return fromJson(Files.readString(p, StandardCharsets.UTF_8), fingerprint);
        } catch (Exception e) {
            return null;
        }
    }
}

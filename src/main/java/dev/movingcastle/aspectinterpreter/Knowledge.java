package dev.movingcastle.aspectinterpreter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leclowndu93150.thaumaturge.api.aspect.AspectDataMaps;
import com.leclowndu93150.thaumaturge.api.aspect.AspectInstance;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * What the interpreter has learned. Nothing is bundled with the mod: everything comes from the
 * player's own installation.
 * <ul>
 *   <li>Live {@code base_aspects} / {@code entity_aspects} data maps (always).</li>
 *   <li>Thaumaturge's own resolved index cache on disk, if one exists, minus every item this mod
 *       interpreted itself (so the model never learns from its own guesses).</li>
 * </ul>
 * A fresh install has no cache yet; it learns from data maps only, and the next launch rebuilds
 * once more with the richer index (see {@link AspectInterpreter}).
 */
final class Knowledge {
    static final Lexicon LEXICON = Lexicon.load();

    private static volatile boolean stale = true;
    private static volatile Model itemModel;
    private static volatile Model entityModel;
    /** How many items were learned from Thaumaturge's cache in the last rebuild. */
    static volatile int learnedFromCache;
    /** Median share each aspect takes of an item's total when it is not the leading aspect (the original layer's scale for additions). */
    private static volatile Map<String, Double> minorShares = Map.of();
    /** Items with hand-written base aspects in the running pack. */
    static volatile Map<String, Map<String, Double>> authored = Map.of();

    static double minorShare(String aspect) {
        return Math.min(0.2, minorShares.getOrDefault(aspect, 0.14));
    }

    static Map<String, Double> toMap(AspectList list) {
        Map<String, Double> v = new HashMap<>();
        if (list == null) return v;
        for (AspectInstance i : list.entries()) {
            i.aspect().unwrapKey().ifPresent(k -> v.merge(k.location().toString(), (double) i.amount(), Double::sum));
        }
        return v;
    }

    static void markStale() { stale = true; }

    static synchronized void rebuildIfStale() {
        if (stale || itemModel == null || entityModel == null) rebuild();
    }

    private static synchronized void rebuild() {
        long t0 = System.nanoTime();
        Config cfg = Config.get();
        Map<String, Map<String, Double>> items = new HashMap<>();
        learnedFromCache = 0;
        if (cfg.learnFromThaumaturgeCache) {
            Set<String> ours = InterpretedIds.load();
            for (Map.Entry<String, Map<String, Double>> e : readThaumaturgeCache().entrySet()) {
                if (ours.contains(e.getKey())) continue;
                items.put(e.getKey(), e.getValue());
                learnedFromCache++;
            }
        }
        Map<String, Map<String, Double>> hand = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Holder.Reference<Item> h = item.builtInRegistryHolder();
            AspectList base = h.getData(AspectDataMaps.BASE_ASPECTS);
            if (base != null && !base.isEmpty()) {
                Map<String, Double> m = toMap(base);
                items.put(h.key().location().toString(), m);
                hand.put(h.key().location().toString(), m);
            }
        }
        authored = hand;
        Map<String, List<Double>> shares = new HashMap<>();
        for (Map<String, Double> v : items.values()) {
            double t = Model.total(v);
            if (t <= 0 || v.size() < 2) continue;
            String top = Collections.max(v.entrySet(), Map.Entry.comparingByValue()).getKey();
            v.forEach((a, x) -> { if (!a.equals(top)) shares.computeIfAbsent(a, k -> new ArrayList<>()).add(x / t); });
        }
        Map<String, Double> med = new HashMap<>();
        shares.forEach((a, l) -> { Collections.sort(l); med.put(a, l.get(l.size() / 2)); });
        minorShares = med;
        Map<String, Map<String, Double>> entities = new HashMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            AspectList a = type.builtInRegistryHolder().getData(AspectDataMaps.ENTITY_ASPECTS);
            if (a != null && !a.isEmpty()) entities.put(EntityType.getKey(type).toString(), toMap(a));
        }
        LEXICON.calibrate(items);
        FunctionLayer.calibrate(hand);
        itemModel = new Model(items, LEXICON);
        entityModel = new Model(entities, LEXICON);
        stale = false;
        AspectInterpreter.LOG.info("Aspect Interpreter learned from {} items ({} from Thaumaturge's index cache) and {} entities in {} ms",
                items.size(), learnedFromCache, entities.size(), (System.nanoTime() - t0) / 1_000_000);
    }

    /** Reads Thaumaturge's index cache (or the copy this mod set aside). Returns item id -> aspect -> amount. */
    private static Map<String, Map<String, Double>> readThaumaturgeCache() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("thaumaturge");
        for (String name : List.of("aspect_index.json", "aspect_index.before_interpreter.json")) {
            Path p = dir.resolve(name);
            if (!Files.exists(p)) continue;
            try {
                JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject aspects = root.getAsJsonObject("aspects");
                if (aspects == null) continue;
                Map<String, Map<String, Double>> out = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : aspects.entrySet()) {
                    JsonElement v = e.getValue();
                    JsonArray arr = v.isJsonArray() ? v.getAsJsonArray()
                            : v.isJsonObject() && v.getAsJsonObject().has("base") ? v.getAsJsonObject().getAsJsonArray("base") : null;
                    if (arr == null) continue;
                    Map<String, Double> m = new HashMap<>();
                    for (JsonElement el : arr) {
                        JsonObject o = el.getAsJsonObject();
                        if (!o.has("aspect")) continue;
                        m.merge(o.get("aspect").getAsString(), o.has("amount") ? o.get("amount").getAsDouble() : 1.0, Double::sum);
                    }
                    if (!m.isEmpty()) out.put(e.getKey(), m);
                }
                if (!out.isEmpty()) return out;
            } catch (Exception ex) {
                AspectInterpreter.LOG.warn("Aspect Interpreter could not read {}", p, ex);
            }
        }
        return Map.of();
    }

    static Model items() {
        Model m = itemModel;
        if (m == null || stale) { rebuildIfStale(); m = itemModel; }
        return m;
    }

    static Model entities() {
        Model m = entityModel;
        if (m == null || stale) { rebuildIfStale(); m = entityModel; }
        return m;
    }
}

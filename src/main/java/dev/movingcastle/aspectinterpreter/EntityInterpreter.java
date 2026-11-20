package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.AspectDataMaps;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback aspects for entity types with no entity_aspects entry.
 *
 * Thaumaturge's goggles overlay asks for entity aspects every frame, so {@link #guess} must never
 * compute anything: it only reads a finished table. The server fills it in {@link #warmUp} at startup
 * and after reloads, then sends it to every client ({@link EntityTablePayload}), so the goggles,
 * scans and server-side mechanics always agree. Until it arrives, lookups simply return nothing.
 */
public final class EntityInterpreter {
    private EntityInterpreter() {}

    private static final Map<EntityType<?>, AspectList> LISTS = new ConcurrentHashMap<>();
    private static final Map<EntityType<?>, Map<String, Double>> GUESSES = new ConcurrentHashMap<>();
    private static volatile HolderLookup.Provider listsFor;
    private static volatile boolean ready;
    private static final String T = "thaumaturge:";

    private static final Map<MobCategory, Map<String, Double>> CATEGORY = new EnumMap<>(MobCategory.class);
    /** Category profiles learned from the pack's own entity_aspects entries; blended with the fallback above. */
    private static volatile Map<MobCategory, Map<String, Double>> learnedCategory = Map.of();

    static {
        CATEGORY.put(MobCategory.MONSTER, Map.of(T + "aversio", 0.6, T + "bestia", 0.4));
        // Thaumaturge gives land animals their habitat element (a cow is bestia 15 + terra 15), rarely victus.
        CATEGORY.put(MobCategory.CREATURE, Map.of(T + "bestia", 0.6, T + "terra", 0.4));
        CATEGORY.put(MobCategory.AMBIENT, Map.of(T + "bestia", 0.5, T + "volatus", 0.3, T + "tenebrae", 0.2));
        Map<String, Double> water = Map.of(T + "aqua", 0.5, T + "bestia", 0.4, T + "victus", 0.1);
        for (MobCategory c : List.of(MobCategory.AXOLOTLS, MobCategory.WATER_CREATURE, MobCategory.WATER_AMBIENT, MobCategory.UNDERGROUND_WATER_CREATURE))
            CATEGORY.put(c, water);
    }

    /**
     * Called from the mixin, possibly every frame. Pure table lookup. Returns the final list for this type: a guess
     * for unassigned types, or the real entry plus additions for natives of other dimensions; null if neither.
     */
    public static AspectList guess(EntityType<?> type) {
        if (!ready) return null; // the server's table hasn't arrived yet; never compute on the caller's thread
        HolderLookup.Provider provider = provider();
        if (provider == null) return null;
        if (provider != listsFor) { LISTS.clear(); listsFor = provider; }
        AspectList cached = LISTS.get(type);
        if (cached != null) return cached;
        Map<String, Double> g = GUESSES.get(type);
        if (g == null || g.isEmpty()) return null;
        Map<String, Integer> ints = new LinkedHashMap<>();
        g.forEach((a, x) -> ints.put(a, (int) Math.round(x)));
        AspectList list = InterpreterContributor.build(ints, provider);
        LISTS.put(type, list);
        return list;
    }

    /** Fills the guess table for every entity type without real aspects. Blocking; call off the render thread. */
    static void warmUp() {
        if (!Config.get().entities) return;
        Knowledge.rebuildIfStale();
        learnCategories();
        Additions.clearEntities();
        Map<EntityType<?>, Map<String, Double>> fresh = new HashMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            AspectList real = type.builtInRegistryHolder().getData(AspectDataMaps.ENTITY_ASPECTS);
            if (real == null || real.isEmpty()) { fresh.put(type, compute(type)); continue; }
            // real entries keep everything they have; natives of other dimensions gain alienis and its flavour
            String id = EntityType.getKey(type).toString();
            Map<String, Integer> realInts = new LinkedHashMap<>();
            Knowledge.toMap(real).forEach((a, x) -> realInts.put(a, (int) Math.round(x)));
            Map<String, Integer> augmented = Config.get().excluded(id) ? null : Additions.forRealEntity(id, realInts);
            if (augmented != null) {
                Map<String, Double> d = new LinkedHashMap<>();
                augmented.forEach((a, x) -> d.put(a, (double) x));
                fresh.put(type, d);
            }
        }
        GUESSES.clear();
        GUESSES.putAll(fresh);
        LISTS.clear();
        ready = true;
    }

    /** Mean aspect profile per mob category over entities that have real entity_aspects, top 3 aspects. */
    private static void learnCategories() {
        Map<MobCategory, Map<String, Double>> sums = new EnumMap<>(MobCategory.class);
        Map<MobCategory, Integer> counts = new EnumMap<>(MobCategory.class);
        Map<MobCategory, Map<String, Integer>> presence = new EnumMap<>(MobCategory.class);
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            AspectList real = type.builtInRegistryHolder().getData(AspectDataMaps.ENTITY_ASPECTS);
            if (real == null || real.isEmpty()) continue;
            Map<String, Double> v = Model.normalise(Knowledge.toMap(real));
            Map<String, Double> sum = sums.computeIfAbsent(type.getCategory(), k -> new HashMap<>());
            v.forEach((a, x) -> sum.merge(a, x, Double::sum));
            Map<String, Integer> pres = presence.computeIfAbsent(type.getCategory(), k -> new HashMap<>());
            v.keySet().forEach(a -> pres.merge(a, 1, Integer::sum));
            counts.merge(type.getCategory(), 1, Integer::sum);
        }
        Map<MobCategory, Map<String, Double>> out = new EnumMap<>(MobCategory.class);
        sums.forEach((cat, sum) -> {
            int n = counts.get(cat);
            List<Map.Entry<String, Double>> top = new ArrayList<>(sum.entrySet());
            top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            Map<String, Double> learned = new HashMap<>();
            // keep only what most members share (a category of zombies and spiders shares little)
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                String a = top.get(i).getKey();
                if (presence.get(cat).getOrDefault(a, 0) >= 0.4 * n) learned.put(a, top.get(i).getValue() / n);
            }
            if (learned.isEmpty()) { out.put(cat, CATEGORY.getOrDefault(cat, Map.of())); return; }
            double conf = n / (n + 5.0);
            Map<String, Double> blended = new HashMap<>();
            Model.normalise(learned).forEach((a, x) -> blended.merge(a, conf * x, Double::sum));
            Model.normalise(CATEGORY.getOrDefault(cat, Map.of())).forEach((a, x) -> blended.merge(a, (1 - conf) * x, Double::sum));
            out.put(cat, blended);
        });
        learnedCategory = out;
    }

    /** The server's finished table, as sent to clients. */
    static Map<String, Map<String, Integer>> table() {
        Map<String, Map<String, Integer>> out = new HashMap<>();
        GUESSES.forEach((type, g) -> {
            if (g.isEmpty()) return;
            Map<String, Integer> m = new LinkedHashMap<>();
            g.forEach((a, x) -> m.put(a, (int) Math.round(x)));
            out.put(EntityType.getKey(type).toString(), m);
        });
        return out;
    }

    /** Client side: adopt the server's table so goggles and scans agree with the server. */
    static void acceptRemote(Map<String, Map<String, Integer>> table) {
        Map<EntityType<?>, Map<String, Double>> fresh = new HashMap<>();
        table.forEach((id, aspects) -> {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) return;
            Map<String, Double> g = new LinkedHashMap<>();
            aspects.forEach((a, x) -> g.put(a, (double) x));
            fresh.put(BuiltInRegistries.ENTITY_TYPE.get(rl), g);
        });
        GUESSES.clear();
        GUESSES.putAll(fresh);
        LISTS.clear();
        ready = true;
    }


    /** The entity's real aspects if it has any, otherwise the interpreter's guess. Used for spawn eggs. */
    static Map<String, Double> knownOrGuess(EntityType<?> type) {
        AspectList real = type.builtInRegistryHolder().getData(AspectDataMaps.ENTITY_ASPECTS);
        if (real != null && !real.isEmpty()) return Knowledge.toMap(real);
        Map<String, Double> g = GUESSES.get(type);
        return g != null ? g : compute(type);
    }

    static void invalidate() {
        ready = false;
        LISTS.clear();
    }

    private static Map<String, Double> compute(EntityType<?> type) {
        Config cfg = Config.get();
        ResourceLocation rl = EntityType.getKey(type);
        String id = rl.toString();
        if (cfg.excluded(id)) return Map.of();
        List<Model.Source> extra = new ArrayList<>();
        MobCategory cat = type.getCategory();
        Map<String, Double> prior = cat == MobCategory.MISC ? null : learnedCategory.getOrDefault(cat, CATEGORY.get(cat));
        if (prior != null && !prior.isEmpty()) extra.add(new Model.Source("category", prior, 0.7));
        Double forcedTotal = null;
        if (cat == MobCategory.MISC) {
            // Boats, minecarts, arrows, projectiles: read them as the item they are (or resemble).
            Model items = Knowledge.items();
            if (items.knows(id)) {
                extra.add(new Model.Source("item", items.known(id), 1.5));
                forcedTotal = Model.total(items.known(id));
            } else {
                Model.Prediction asItem = items.predict(id, null, 8, null);
                if (asItem != null && asItem.confidence() >= 0.3) {
                    Map<String, Double> d = new HashMap<>();
                    asItem.aspects().forEach((a, x) -> d.put(a, (double) x));
                    extra.add(new Model.Source("item-like", d, asItem.confidence()));
                    forcedTotal = Model.total(d);
                }
            }
        } else if (type.fireImmune()) {
            // Only living creatures: projectiles and body-part entities are fire-immune for technical reasons.
            extra.add(new Model.Source("fireproof", Map.of(T + "ignis", 1.0), 0.2));
        }
        extra.addAll(DimensionContext.sources(id, true));
        double size = Math.cbrt(Math.max(0.01, type.getWidth() * type.getWidth() * type.getHeight()));
        double fallback = 25 * Math.max(0.4, Math.min(2.0, size / 0.9));
        Model.Prediction p = Knowledge.entities().predict(id, extra, fallback, forcedTotal);
        if (p == null || p.confidence() < cfg.minConfidence) {
            AssignmentLog.entity(id, null, p == null ? 0 : p.confidence(), p == null ? "no evidence" : "below min_confidence");
            return Map.of();
        }
        DimensionContext.ensureNative(p.aspects(), id, true);
        AssignmentLog.entity(id, p.aspects(), p.confidence(), p.basis() + DimensionContext.describe(id, true));
        Map<String, Double> out = new LinkedHashMap<>();
        p.aspects().forEach((a, x) -> out.put(a, (double) x));
        return out;
    }

    private static HolderLookup.Provider provider() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) return server.registryAccess();
        if (FMLEnvironment.dist == Dist.CLIENT) return ClientAccess.registries();
        return null;
    }
}

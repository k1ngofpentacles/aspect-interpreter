package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.AspectDataMaps;
import com.leclowndu93150.thaumaturge.api.aspect.AspectInstance;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

import java.util.*;

/**
 * What the interpreter has learned. Nothing is bundled with the mod: everything comes from the
 * player's own installation.
 * <ul>
 *   <li>Live {@code base_aspects} / {@code entity_aspects} data maps (always).</li>
 *   <li>Thaumaturge's own index of this launch ({@link #cleanFrom}), read without our overlay. Thaumaturge builds it with
 *       this mod switched off, so the model never learns from its own guesses, even second-hand. Rebuilt from scratch
 *       at every launch and reload; nothing of it is saved.</li>
 * </ul>
 */
final class Knowledge {
    static final Lexicon LEXICON = Lexicon.load();

    private static volatile boolean stale = true;
    private static volatile Model itemModel;
    private static volatile Model entityModel;
    /** How many items were learned from Thaumaturge's clean derived values in the last rebuild. */
    static volatile int learnedFromCache;
    /** Median share each aspect takes of an item's total when it is not the leading aspect (the original layer's scale for additions). */
    private static volatile Map<String, Double> minorShares = Map.of();
    /** Items with hand-written base aspects in the running pack. */
    static volatile Map<String, Map<String, Double>> authored = Map.of();

    static double minorShare(String aspect) {
        return Math.min(0.2, minorShares.getOrDefault(aspect, 0.14));
    }

    static Map<String, Double> toMap(AspectList list) {
        Map<String, Double> v = new TreeMap<>();
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

    /** Thaumaturge's own values for every item, read from its index without our overlay (id -> aspect -> amount), sorted. */
    static Map<String, Map<String, Double>> cleanFrom(com.leclowndu93150.thaumaturge.content.aspect.AspectIndex index) {
        Map<String, Map<String, Double>> out = new TreeMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            AspectList list = Overlay.rawOf(index, item);
            if (list == null || list.isEmpty()) continue;
            Map<String, Double> m = toMap(list);
            if (!m.isEmpty()) out.put(BuiltInRegistries.ITEM.getKey(item).toString(), m);
        }
        cleanValues = out;
        return out;
    }

    /** The values the model last trained on besides the data maps; read by the pack test. */
    public static volatile Map<String, Map<String, Double>> cleanValues = Map.of();

    /**
     * A model needed before the overlay computation has run (a lookup racing the first tick, or after a reload): the data maps
     * plus whatever index Thaumaturge holds. Replaced by the computation's own rebuild as soon as that runs.
     */
    private static synchronized void rebuild() {
        var index = com.leclowndu93150.thaumaturge.content.aspect.AspectIndexHolder.getConcrete();
        boolean have = Config.get().learnFromThaumaturgeCache && index != com.leclowndu93150.thaumaturge.content.aspect.AspectIndex.EMPTY;
        rebuild(have ? cleanFrom(index) : Map.of());
    }

    /**
     * Rebuilds the model from the data maps plus {@code clean}, Thaumaturge's own values derived without this mod.
     * Every map is sorted by id and aspect, so sums and tie-breaks never depend on hash or insertion order.
     */
    static synchronized void rebuild(Map<String, Map<String, Double>> clean) {
        long t0 = System.nanoTime();
        Map<String, Map<String, Double>> items = new TreeMap<>(clean);
        // a hand-written entry always wins over the clean value of the same item (put below)
        learnedFromCache = clean.size();
        Map<String, Map<String, Double>> hand = new TreeMap<>();
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
        Map<String, Map<String, Double>> entities = new TreeMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            AspectList a = type.builtInRegistryHolder().getData(AspectDataMaps.ENTITY_ASPECTS);
            if (a != null && !a.isEmpty()) entities.put(EntityType.getKey(type).toString(), toMap(a));
        }
        LEXICON.calibrate(items);
        FunctionLayer.calibrate(hand);
        double maxHand = 0;
        for (Map<String, Double> v : hand.values()) maxHand = Math.max(maxHand, Model.total(v));
        Map<String, String> kinds = new HashMap<>();
        for (String id : items.keySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            BuiltInRegistries.ITEM.getOptional(rl).ifPresent(it -> kinds.put(id, ItemFeatures.kind(it)));
        }
        itemModel = new Model(items, LEXICON).withKinds(kinds).withMaxTotal(maxHand).withHandWritten(hand.keySet());
        entityModel = new Model(entities, LEXICON);
        stale = false;
        AspectInterpreter.LOG.info("Aspect Interpreter learned from {} items ({} from Thaumaturge's own derived values; name-based totals capped at {}) and {} entities in {} ms",
                items.size(), learnedFromCache, (int) maxHand, entities.size(), (System.nanoTime() - t0) / 1_000_000);
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

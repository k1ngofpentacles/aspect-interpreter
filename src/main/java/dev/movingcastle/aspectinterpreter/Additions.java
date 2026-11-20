package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.AspectInstance;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the additive layers may act, and a record of what each added. Nothing is ever removed or reduced.
 *
 * <ul>
 *   <li><b>Mobs</b> ({@link MobEnrichment}): habitat, dimension, behaviour and drops. The only layer that adds to
 *       hand-written entries.</li>
 *   <li><b>Family</b> ({@link FamilyLayer}): crafted and interpreted items of any mod take the key aspects their
 *       hand-written relatives share (Forestry's and Thaumaturge's crafted candles take lux from vanilla's).</li>
 *   <li><b>Craftsmanship</b> ({@link CraftComplexity}): items of non-canon mods made at the crafting table, whether
 *       Thaumaturge derived them or the interpreter guessed them.</li>
 *   <li><b>Function</b> ({@link FunctionLayer}): only items the interpreter fills in.</li>
 *   <li><b>Dimension</b> ({@link DimensionContext}): only filled-in items that come from a mob of another
 *       dimension (its drops, its bucket, its egg). Blocks and canon items never get it.</li>
 * </ul>
 * Function and craftsmanship stop once an item has {@value #MAX_FOR_EXTRAS} aspects, the original layer's
 * practical ceiling (5 or more aspects is about 1% of hand-written items).
 */
public final class Additions {
    private Additions() {}

    static final int MAX_FOR_EXTRAS = 5;
    private static final Map<String, String> LOG = new ConcurrentHashMap<>();
    private static final Map<String, String> ENTITY_LOG = new ConcurrentHashMap<>();
    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> ENTITY_COUNTS = new ConcurrentHashMap<>();

    /** Start of an item index build. */
    static void clear() { LOG.clear(); COUNTS.clear(); }

    /** Start of an entity warm-up. */
    static void clearEntities() { ENTITY_LOG.clear(); ENTITY_COUNTS.clear(); }

    static Set<String> itemIds() { return new HashSet<>(LOG.keySet()); }

    static Map<String, String> log() {
        Map<String, String> all = new TreeMap<>(LOG);
        ENTITY_LOG.forEach((k, v) -> all.put(k + " (entity)", v));
        return all;
    }

    static String summary() {
        return String.format(Locale.ROOT, "family %d, craftsmanship %d, function %d, mob-drop dimension %d",
                COUNTS.getOrDefault("family", 0), COUNTS.getOrDefault("craftsmanship", 0), COUNTS.getOrDefault("function", 0), COUNTS.getOrDefault("dimension", 0));
    }

    static String entitySummary() {
        return String.format(Locale.ROOT, "%d hand-written mobs enriched, %d guessed mobs enriched",
                ENTITY_COUNTS.getOrDefault("real", 0), ENTITY_COUNTS.getOrDefault("guess", 0));
    }

    /**
     * Recipe-derived items (called from the crafting mixin): family keys for any mod's crafted items, then
     * craftsmanship for non-canon table crafts. Hand-written items never reach here.
     */
    public static AspectList forDerived(Item item, AspectList list, HolderLookup.Provider registries) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        if (Config.get().excluded(id)) return list;
        Map<String, Integer> current = amounts(list);
        Map<String, Integer> plan = new LinkedHashMap<>();
        layer(plan, "family", id, FamilyLayer.additions(item, id, current));
        Set<String> now = new HashSet<>(current.keySet());
        now.addAll(plan.keySet());
        if (!Canon.isCanon(id) && now.size() < MAX_FOR_EXTRAS) {
            Map<String, Integer> craft = new LinkedHashMap<>(CraftComplexity.craftsmanship(item, list.totalAmount()));
            craft.keySet().removeAll(now);
            layer(plan, "craftsmanship", id, craft);
        }
        return apply(list, plan, registries);
    }

    /** Interpreted items: mob-drop dimension, function and craftsmanship, merged into the guess. */
    static void forGuess(Item item, String id, LinkedHashMap<String, Integer> aspects) {
        int total = 0;
        for (int x : aspects.values()) total += x;
        Map<String, Integer> plan = new LinkedHashMap<>();
        layer(plan, "family", id, FamilyLayer.additions(item, id, aspects));
        Set<String> withFamily = new HashSet<>(aspects.keySet());
        withFamily.addAll(plan.keySet());
        layer(plan, "dimension", id, DimensionContext.dropAdditions(id, withFamily, total));
        Set<String> now = new HashSet<>(aspects.keySet());
        now.addAll(plan.keySet());
        if (now.size() < MAX_FOR_EXTRAS) {
            Map<String, Integer> fn = FunctionLayer.additions(id, now, total);
            layer(plan, "function", id, fn);
            now.addAll(fn.keySet());
        }
        if (now.size() < MAX_FOR_EXTRAS) {
            Map<String, Integer> craft = new LinkedHashMap<>(CraftComplexity.craftsmanship(item, total));
            craft.keySet().removeAll(now);
            layer(plan, "craftsmanship", id, craft);
        }
        plan.forEach((a, x) -> aspects.merge(a, x, Integer::sum));
    }

    /** Called by {@link MobEnrichment} for every mob it adds to. */
    static void entity(String id, boolean real, String note) {
        ENTITY_LOG.put(id, note);
        ENTITY_COUNTS.merge(real ? "real" : "guess", 1, Integer::sum);
    }

    private static void layer(Map<String, Integer> plan, String name, String id, Map<String, Integer> add) {
        if (add.isEmpty()) return;
        add.forEach((a, x) -> plan.merge(a, x, Integer::sum));
        COUNTS.merge(name, 1, Integer::sum);
        StringJoiner j = new StringJoiner(" ");
        add.forEach((a, x) -> j.add("+" + shortName(a) + " " + x));
        LOG.merge(id, name + ": " + j, (o, n) -> o.contains(name + ":") ? o : o + "; " + n);
    }

    static String shortName(String aspect) {
        return aspect.substring(aspect.indexOf(':') + 1);
    }

    private static Map<String, Integer> amounts(AspectList list) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (AspectInstance i : list.entries()) i.aspect().unwrapKey().ifPresent(k -> m.merge(k.location().toString(), i.amount(), Integer::sum));
        return m;
    }

    /** Adds each planned aspect to the original list; existing entries are never changed except by addition. */
    private static AspectList apply(AspectList list, Map<String, Integer> plan, HolderLookup.Provider registries) {
        if (plan.isEmpty()) return list;
        Optional<? extends HolderLookup.RegistryLookup<IAspect>> lookup = registries.lookup(InterpreterContributor.ASPECT_REGISTRY);
        if (lookup.isEmpty()) return list;
        AspectList out = list;
        for (Map.Entry<String, Integer> e : plan.entrySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
            if (rl == null) continue;
            Optional<? extends Holder<IAspect>> h = lookup.get().get(ResourceKey.create(InterpreterContributor.ASPECT_REGISTRY, rl));
            if (h.isPresent()) out = out.add(new AspectInstance(h.get(), e.getValue()));
        }
        return out;
    }
}

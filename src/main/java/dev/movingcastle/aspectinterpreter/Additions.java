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
 * Applies the additive layers in a fixed order, and records what each added.
 *
 * <ol>
 *   <li><b>Dimension</b>: alienis plus the dimension's top flavour, for objects native to a non-Overworld
 *       dimension. The only layer that touches hand-written entries.</li>
 *   <li><b>Function</b>: one missing function aspect, from the name's head (container &rarr; vacuos).</li>
 *   <li><b>Craftsmanship</b>: ordo / machina / fabrico for crafting steps beyond the first refinement.</li>
 * </ol>
 * Nothing is ever removed or reduced. Function and craftsmanship stop once an object has {@value #MAX_FOR_EXTRAS}
 * aspects, the original layer's practical ceiling (5 or more aspects is about 1% of hand-written items).
 */
public final class Additions {
    private Additions() {}

    static final int MAX_FOR_EXTRAS = 5;
    private static final Map<String, String> LOG = new ConcurrentHashMap<>();
    private static final Map<String, String> ENTITY_LOG = new ConcurrentHashMap<>();
    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();

    /** Start of an item index build. */
    static void clear() { LOG.clear(); COUNTS.clear(); }

    /** Start of an entity warm-up. */
    static void clearEntities() { ENTITY_LOG.clear(); }

    static Set<String> itemIds() { return new HashSet<>(LOG.keySet()); }

    static Map<String, String> log() {
        Map<String, String> all = new TreeMap<>(LOG);
        ENTITY_LOG.forEach((k, v) -> all.put(k + " (entity)", v));
        return all;
    }

    static String summary() {
        return String.format(Locale.ROOT, "dimension %d, function %d, craftsmanship %d",
                COUNTS.getOrDefault("dimension", 0), COUNTS.getOrDefault("function", 0), COUNTS.getOrDefault("craftsmanship", 0));
    }

    /** Hand-written base entries (called from the collectBase mixin): dimension only. */
    public static AspectList forAuthored(Item item, AspectList list, HolderLookup.Provider registries) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        if (Config.get().excluded(id)) return list;
        Map<String, Integer> plan = new LinkedHashMap<>();
        layer(plan, "dimension", id, DimensionContext.additions(id, false, present(list), list.totalAmount()));
        return apply(id, list, plan, registries);
    }

    /** Recipe-derived items (called from the crafting mixin): all three layers. */
    public static AspectList forDerived(Item item, AspectList list, HolderLookup.Provider registries) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        if (Config.get().excluded(id)) return list;
        Set<String> present = present(list);
        int total = list.totalAmount();
        Map<String, Integer> plan = new LinkedHashMap<>();
        layer(plan, "dimension", id, DimensionContext.additions(id, false, present, total));
        extras(plan, item, id, present, total);
        return apply(id, list, plan, registries);
    }

    /** Interpreted items: all three layers, merged into the guess. */
    static void forGuess(Item item, String id, LinkedHashMap<String, Integer> aspects) {
        int total = 0;
        for (int x : aspects.values()) total += x;
        Map<String, Integer> plan = new LinkedHashMap<>();
        layer(plan, "dimension", id, DimensionContext.additions(id, false, aspects.keySet(), total));
        Set<String> present = new HashSet<>(aspects.keySet());
        present.addAll(plan.keySet());
        extras(plan, item, id, present, total);
        plan.forEach((a, x) -> aspects.merge(a, x, Integer::sum));
    }

    /** Entities with real entity_aspects: dimension only. Returns null when nothing is added. */
    static Map<String, Integer> forRealEntity(String id, Map<String, Integer> real) {
        int total = 0;
        for (int x : real.values()) total += x;
        Map<String, Integer> plan = new LinkedHashMap<>();
        Map<String, Integer> add = DimensionContext.additions(id, true, real.keySet(), total);
        if (add.isEmpty()) return null;
        plan.putAll(add);
        StringJoiner j = new StringJoiner(" ");
        add.forEach((a, x) -> j.add("+" + a.replace("thaumaturge:", "") + " " + x));
        ENTITY_LOG.put(id, "dimension: " + j);
        Map<String, Integer> out = new LinkedHashMap<>(real);
        plan.forEach((a, x) -> out.merge(a, x, Integer::sum));
        return out;
    }

    private static void extras(Map<String, Integer> plan, Item item, String id, Set<String> present, int total) {
        Set<String> now = new HashSet<>(present);
        now.addAll(plan.keySet());
        if (now.size() >= MAX_FOR_EXTRAS) return;
        Map<String, Integer> fn = FunctionLayer.additions(id, now, total);
        layer(plan, "function", id, fn);
        now.addAll(fn.keySet());
        if (now.size() >= MAX_FOR_EXTRAS) return;
        Map<String, Integer> craft = new LinkedHashMap<>(CraftComplexity.craftsmanship(item, total));
        layer(plan, "craftsmanship", id, craft);
    }

    private static void layer(Map<String, Integer> plan, String name, String id, Map<String, Integer> add) {
        if (add.isEmpty()) return;
        add.forEach((a, x) -> plan.merge(a, x, Integer::sum));
        COUNTS.merge(name, 1, Integer::sum);
        StringJoiner j = new StringJoiner(" ");
        add.forEach((a, x) -> j.add("+" + a.replace("thaumaturge:", "") + " " + x));
        LOG.merge(id, name + ": " + j, (o, n) -> o.contains(name + ":") ? o : o + "; " + n);
    }

    private static Set<String> present(AspectList list) {
        Set<String> s = new HashSet<>();
        for (AspectInstance i : list.entries()) i.aspect().unwrapKey().ifPresent(k -> s.add(k.location().toString()));
        return s;
    }

    /** Adds each planned aspect to the original list; existing entries are never changed except by addition. */
    private static AspectList apply(String id, AspectList list, Map<String, Integer> plan, HolderLookup.Provider registries) {
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

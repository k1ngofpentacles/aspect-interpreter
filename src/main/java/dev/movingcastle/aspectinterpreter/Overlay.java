package dev.movingcastle.aspectinterpreter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.leclowndu93150.thaumaturge.api.aspect.AspectInstance;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.api.aspect.IAspectIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Our values for items, served on top of Thaumaturge's own index. Thaumaturge's index (and its cache file) holds only
 * Thaumaturge's values; this overlay holds the items whose value differs once this mod's guesses and additions are
 * applied: the guesses for items Thaumaturge leaves empty, additions to items it derives, and the items derived from
 * those. It is computed from scratch on every launch ({@link OverlayBuilder}), never read back from a file, and is
 * consulted by {@code AspectIndexMixin} at the one place every lookup passes ({@code AspectIndex.of}). Hand-written
 * items are never in it.
 */
public final class Overlay {
    private Overlay() {}

    private static volatile Map<Item, AspectList> byItem = Map.of();
    private static volatile Map<String, Map<String, Integer>> sorted = Map.of();
    private static volatile boolean building;
    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();
    private static final Gson GSON = new GsonBuilder().create();

    /** Our value for this item, or null if Thaumaturge's own stands (also null on the thread that is computing the overlay). */
    public static AspectList get(Item item) {
        if (BYPASS.get() != null) return null;
        return byItem.get(item);
    }

    /** {@code index.of(item)} with the overlay switched off: what Thaumaturge's index itself holds. */
    public static AspectList rawOf(IAspectIndex index, Item item) {
        Boolean before = BYPASS.get();
        BYPASS.set(Boolean.TRUE);
        try {
            return index.of(item);
        } finally {
            if (before == null) BYPASS.remove(); else BYPASS.set(before);
        }
    }

    /** Runs {@code r} with the overlay switched off on this thread. */
    static <T> T raw(java.util.function.Supplier<T> r) {
        Boolean before = BYPASS.get();
        BYPASS.set(Boolean.TRUE);
        try {
            return r.get();
        } finally {
            if (before == null) BYPASS.remove(); else BYPASS.set(before);
        }
    }

    /** True while this mod's own index build runs: our contributor and crafting mixin act only then. */
    public static boolean building() { return building; }

    static void building(boolean b) { building = b; }

    static void set(Map<Item, AspectList> items) {
        Map<String, Map<String, Integer>> s = new TreeMap<>();
        items.forEach((item, list) -> s.put(BuiltInRegistries.ITEM.getKey(item).toString(), amounts(list)));
        sorted = s;
        byItem = new HashMap<>(items);
    }

    static void clear() {
        byItem = Map.of();
        sorted = Map.of();
    }

    public static int size() { return byItem.size(); }

    static Map<Item, AspectList> snapshot() { return byItem; }

    /** The overlay as sorted id -> aspect -> amount; the same text on every launch of the same pack. */
    public static String json() { return GSON.toJson(sorted); }

    static Map<String, Map<String, Integer>> sorted() { return sorted; }

    static Map<String, Integer> amounts(AspectList list) {
        Map<String, Integer> m = new TreeMap<>();
        for (AspectInstance i : list.entries()) i.aspect().unwrapKey().ifPresent(k -> m.merge(k.location().toString(), i.amount(), Integer::sum));
        return m;
    }
}

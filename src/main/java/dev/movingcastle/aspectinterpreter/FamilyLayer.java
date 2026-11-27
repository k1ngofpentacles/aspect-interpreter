package dev.movingcastle.aspectinterpreter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Family consistency: similar things share their key aspects.
 *
 * Thaumaturge's recipe rule loses an object's defining aspect when no ingredient carries it: vanilla candles are
 * hand-written lux 5, but Forestry's big candles and Thaumaturge's own coloured candles are crafted from wax,
 * string and dye, so they come out with no lux at all. This layer groups items into families and gives crafted
 * and interpreted members the key aspects their hand-written relatives agree on. Hand-written items are never
 * changed.
 *
 * <ul>
 *   <li><b>Families.</b> An item's family is its head noun: the last word of its name that names a kind of thing,
 *       after colour, size, shaping and material words ({@code big_candle_red}, {@code candle_black} and
 *       {@code jumbo_candle_refractory} are all candles; {@code copper_candle_holder} is a holder). A word is a
 *       head noun only if, across the pack, it mostly ends names. A rare head noun ({@code potato_cannon}) gives
 *       no family rather than falling back to a modifier.</li>
 *   <li><b>Who defines the keys.</b> Hand-written members of the family in the item's own mod first (at least 2);
 *       otherwise hand-written members across the pack (at least 3, from two mods or including vanilla).</li>
 *   <li><b>Key aspects.</b> Up to 2 aspects carried by at least 60% of those members, ignoring any aspect that a
 *       member's modifier words explain (chorus fruit's alienis comes from "chorus", not from "fruit").</li>
 *   <li><b>Same kind only.</b> Members only learn from anchors of the same kind: a block of the same material,
 *       food, gear, or other item, unless they share an item tag. A music disc doesn't learn from chicken meat.</li>
 *   <li><b>Light.</b> A bright block (light 10+ as placed, or when lit by hand, like a candle holder) is a light
 *       source; if the pack's hand-written light sources mostly carry lux, so does it.</li>
 *   <li><b>Amounts.</b> Each key aspect is sized to its median share among the anchors, at most the item's largest
 *       existing aspect; all family additions together stay within half the item's original total.</li>
 * </ul>
 */
final class FamilyLayer {
    private FamilyLayer() {}

    static final double KEY_PRESENCE = 0.6, CAP_SHARE = 0.5, LIGHT_PRESENCE = 0.5;
    static final int BRIGHT = 10;
    private static final String LUX = "thaumaturge:lux";
    private static final Set<String> MATERIAL_ASPECTS = Set.of("thaumaturge:metallum", "thaumaturge:terra", "thaumaturge:herba", "thaumaturge:vitreus");

    private record Anchor(String id, String ns, String kind, Set<TagKey<Item>> tags, Map<String, Double> aspects, List<String> modifiers) {}
    private record Key(String aspect, double share) {}

    private static volatile Map<String, String> heads = Map.of();          // item id -> head noun
    private static volatile Map<String, List<Anchor>> anchors = Map.of(); // head -> hand-written members
    private static volatile Key light;                                    // lux for light sources, or null
    private static volatile Object preparedFor;

    /** Once per index build, after {@link Knowledge} is rebuilt. */
    static synchronized void prepare() {
        Map<String, Map<String, Double>> authored = Knowledge.authored;
        if (preparedFor == authored) return;
        // which words end names, across the whole pack
        Map<String, Integer> last = new HashMap<>(), seen = new HashMap<>();
        Map<String, List<String>> cands = new HashMap<>();
        for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) {
            String id = rl.toString();
            List<String> c = candidates(id);
            cands.put(id, c);
            if (!c.isEmpty()) last.merge(c.get(c.size() - 1), 1, Integer::sum);
            for (String t : new HashSet<>(c)) seen.merge(t, 1, Integer::sum);
        }
        Map<String, String> h = new HashMap<>();
        cands.forEach((id, c) -> {
            for (int i = c.size() - 1; i >= 0; i--) {
                String t = c.get(i);
                int l = last.getOrDefault(t, 0), s = seen.getOrDefault(t, 1);
                if (l >= 3 && l >= 0.5 * s) { h.put(id, t); return; }
                if (l < 0.75 * s) continue; // a modifier that sometimes trails: keep looking
                return;                     // a real but rare head noun: no family
            }
        });
        Map<String, List<Anchor>> a = new HashMap<>();
        List<Map<String, Double>> lights = new ArrayList<>();
        authored.forEach((id, aspects) -> {
            Item item = item(id);
            if (item == null || aspects.isEmpty()) return;
            if (lightSource(item)) lights.add(aspects);
            String head = h.get(id);
            if (head == null) return;
            List<String> mods = new ArrayList<>(strip(id));
            mods.remove(head);
            a.computeIfAbsent(head, k -> new ArrayList<>()).add(new Anchor(id, Tokens.namespace(id), ItemFeatures.kind(item),
                    tags(item), aspects, mods));
        });
        Key lightKey = null;
        if (lights.size() >= 3) {
            List<Double> shares = new ArrayList<>();
            for (Map<String, Double> l : lights) if (l.containsKey(LUX)) shares.add(l.get(LUX) / Model.total(l));
            if (shares.size() >= LIGHT_PRESENCE * lights.size()) lightKey = new Key(LUX, median(shares));
        }
        heads = h;
        anchors = a;
        light = lightKey;
        preparedFor = authored;
        AspectInterpreter.LOG.info("Aspect Interpreter families: {} items in {} families with hand-written members; light sources {}",
                h.size(), a.size(), lightKey == null ? "have no shared key" : String.format(Locale.ROOT, "carry lux (median share %.0f%%)", lightKey.share() * 100));
    }

    /** Family additions for a crafted or interpreted item, given its current aspects. */
    static Map<String, Integer> additions(Item item, String id, Map<String, Integer> current) {
        if (!Config.get().familyConsistency || current.isEmpty() || preparedFor == null) return Map.of();
        int total = 0, biggest = 0;
        for (int x : current.values()) { total += x; biggest = Math.max(biggest, x); }
        if (total <= 0) return Map.of();
        List<Key> keys = new ArrayList<>(keysFor(item, id));
        if (light != null && lightSource(item) && keys.stream().noneMatch(k -> k.aspect().equals(LUX))) keys.add(light);
        Map<String, Integer> out = new LinkedHashMap<>();
        int budget = (int) Math.floor(CAP_SHARE * total);
        for (Key k : keys) {
            if (current.containsKey(k.aspect()) || out.containsKey(k.aspect())) continue;
            int x = (int) Math.min(biggest, Math.max(1, Math.round(k.share() * total)));
            x = Math.min(x, budget);
            if (x < 1) break;
            out.put(k.aspect(), x);
            budget -= x;
        }
        return out;
    }

    private static List<Key> keysFor(Item item, String id) {
        String head = heads.get(id);
        if (head == null) return List.of();
        List<Anchor> family = anchors.getOrDefault(head, List.of());
        if (family.isEmpty()) return List.of();
        String ns = Tokens.namespace(id), kind = ItemFeatures.kind(item);
        Set<TagKey<Item>> tags = tags(item);
        List<Anchor> compatible = family.stream()
                .filter(a -> !a.id().equals(id) && (a.kind().equals(kind) || !Collections.disjoint(a.tags(), tags))).toList();
        List<Anchor> own = compatible.stream().filter(a -> a.ns().equals(ns)).toList();
        if (own.size() >= 2) return keys(own);
        if (compatible.size() >= 3) {
            Set<String> mods = compatible.stream().map(Anchor::ns).collect(Collectors.toSet());
            if (mods.size() >= 2 || mods.contains("minecraft")) return keys(compatible);
        }
        return List.of();
    }

    private static List<Key> keys(List<Anchor> anchors) {
        Map<String, Integer> presence = new HashMap<>();
        Map<String, List<Double>> shares = new HashMap<>();
        Lexicon lex = Knowledge.LEXICON;
        for (Anchor a : anchors) {
            double t = Model.total(a.aspects());
            for (Map.Entry<String, Double> e : a.aspects().entrySet()) {
                if (explained(a.modifiers(), e.getKey(), lex)) continue; // the material or modifier brings it, not the noun
                presence.merge(e.getKey(), 1, Integer::sum);
                shares.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue() / t);
            }
        }
        List<Key> out = new ArrayList<>();
        presence.entrySet().stream()
                .filter(e -> e.getValue() >= KEY_PRESENCE * anchors.size())
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(e -> -median(shares.get(e.getKey())))
                        .thenComparing(Map.Entry::getKey))
                .limit(2)
                .forEach(e -> out.add(new Key(e.getKey(), median(shares.get(e.getKey())))));
        return out;
    }

    private static boolean explained(List<String> modifiers, String aspect, Lexicon lex) {
        for (String m : modifiers) {
            Map<String, Double> w = lex.keywords.get(m);
            if (w != null && w.containsKey(aspect)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- names

    /** Name words without colour/size words (single or two-word, anywhere in the name). */
    private static List<String> strip(String id) {
        List<String> t = Tokens.of(id), out = new ArrayList<>();
        Set<String> neutral = Knowledge.LEXICON.neutral;
        for (int i = 0; i < t.size(); i++) {
            if (i + 1 < t.size() && neutral.contains(t.get(i) + "_" + t.get(i + 1))) { i++; continue; }
            if (!neutral.contains(t.get(i))) out.add(t.get(i));
        }
        return out;
    }

    /** Words that could be a head noun: not colour, size, shaping or material words. */
    private static List<String> candidates(String id) {
        List<String> out = new ArrayList<>();
        Lexicon lex = Knowledge.LEXICON;
        for (String t : strip(id)) {
            if (lex.familyModifiers.contains(t) || material(t, lex)) continue;
            out.add(t);
        }
        return out;
    }

    private static boolean material(String t, Lexicon lex) {
        if (FunctionLayer.isFunctionWord(t)) return false;
        Map<String, Double> w = lex.keywords.get(t);
        if (w == null) return false;
        for (Map.Entry<String, Double> e : w.entrySet()) if (MATERIAL_ASPECTS.contains(e.getKey()) && e.getValue() >= 0.8) return true;
        return false;
    }

    // ---------------------------------------------------------------- items

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null || !BuiltInRegistries.ITEM.containsKey(rl) ? null : BuiltInRegistries.ITEM.get(rl);
    }

    private static Set<TagKey<Item>> tags(Item item) {
        try {
            return item.builtInRegistryHolder().tags().collect(Collectors.toSet());
        } catch (Throwable t) {
            return Set.of();
        }
    }

    /**
     * A real light source: a block that shines at {@value #BRIGHT}+ as placed, or that is lit by hand (a LIT state,
     * no machine inside) and shines that bright when lit. Faint glows (brewing stand, magma block) and machines
     * that light up while working (furnaces) don't count; the pack's own hand-written blocks draw the same line.
     */
    static boolean lightSource(Item item) {
        try {
            if (!(item instanceof BlockItem bi)) return false;
            Block b = bi.getBlock();
            BlockState st = b.defaultBlockState();
            if (st.getLightEmission() >= BRIGHT) return true;
            if (b instanceof EntityBlock || !st.hasProperty(BlockStateProperties.LIT)) return false;
            return st.setValue(BlockStateProperties.LIT, true).getLightEmission() >= BRIGHT;
        } catch (Throwable t) {
            return false;
        }
    }

    private static double median(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }
}

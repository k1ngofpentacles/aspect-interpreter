package dev.movingcastle.aspectinterpreter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.io.Reader;
import java.util.*;

/**
 * Which mods count as canon: their aspects are hand-written, so the interpreter learns from them but never adds
 * to their items.
 *
 * Canon is the configured list ({@link Config#canonNamespaces}: vanilla, Thaumaturge and its add-ons, Create and
 * its add-ons) plus every mod found shipping its own Thaumaturge aspect files. Detection reads the data map files
 * directly from every loaded pack, so a newly installed add-on that hand-writes aspects is recognised with no
 * configuration. A mod that hand-writes a large part of another mod's items (at least {@value #COVER_SHARE} of them)
 * makes that mod canon too.
 */
final class Canon {
    private Canon() {}

    private static final List<ResourceLocation> FILES = List.of(
            ResourceLocation.fromNamespaceAndPath("thaumaturge", "data_maps/item/base_aspects.json"),
            ResourceLocation.fromNamespaceAndPath("thaumaturge", "data_maps/entity_type/entity_aspects.json"));
    static final double COVER_SHARE = 0.25;

    private static volatile Set<String> detected = Set.of();
    /** Hand-writing pack -> entries it writes, for the log. */
    private static volatile Map<String, Integer> writers = Map.of();
    private static volatile boolean computed;

    static boolean computed() { return computed; }

    static synchronized void compute(MinecraftServer server) {
        if (server == null) return;
        ResourceManager rm = server.getResourceManager();
        Map<String, Integer> byPack = new TreeMap<>();
        Map<String, Integer> covered = new HashMap<>();
        Set<String> found = new HashSet<>();
        for (ResourceLocation file : FILES) {
            boolean items = file.getPath().contains("/item/");
            for (Resource r : rm.getResourceStack(file)) {
                String pack = packMod(r.sourcePackId());
                int n = 0;
                try (Reader in = r.openAsReader()) {
                    JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
                    JsonElement values = root.get("values");
                    if (values != null && values.isJsonObject()) {
                        for (String key : values.getAsJsonObject().keySet()) {
                            n++;
                            if (items) countItems(key, covered);
                        }
                    }
                } catch (Exception e) {
                    AspectInterpreter.LOG.debug("Aspect Interpreter could not read {} from {}", file, r.sourcePackId(), e);
                }
                if (n == 0) continue;
                byPack.merge(pack, n, Integer::sum);
                if (!pack.isEmpty()) found.add(pack);
            }
        }
        // a namespace mostly hand-written by someone else is canon too
        Map<String, Integer> sizes = new HashMap<>();
        for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) sizes.merge(rl.getNamespace(), 1, Integer::sum);
        covered.forEach((ns, n) -> { if (n >= COVER_SHARE * sizes.getOrDefault(ns, Integer.MAX_VALUE)) found.add(ns); });
        detected = found;
        writers = byPack;
        computed = true;
        StringJoiner j = new StringJoiner(", ");
        byPack.forEach((p, n) -> j.add(p + " (" + n + ")"));
        AspectInterpreter.LOG.info("Aspect Interpreter canon: hand-written aspects found in {}", j.length() == 0 ? "no packs" : j);
    }

    /** True if the interpreter may only read this object, never add to it. */
    static boolean isCanon(String id) {
        String ns = Tokens.namespace(id);
        if (detected.contains(ns)) return true;
        for (String p : Config.get().canonNamespaces)
            if (p.endsWith("*") ? ns.startsWith(p.substring(0, p.length() - 1)) : p.equals(ns)) return true;
        return false;
    }

    static Map<String, Integer> writers() { return writers; }

    /** "mod/create_thaumaturgy" -> "create_thaumaturgy"; data packs keep their own name. */
    private static String packMod(String packId) {
        if (packId == null) return "";
        String p = packId;
        if (p.startsWith("mod/")) p = p.substring(4);
        else if (p.startsWith("mod:")) p = p.substring(4);
        if (p.equals("mod_resources") || p.equals("vanilla")) return "";
        return p;
    }

    private static void countItems(String key, Map<String, Integer> covered) {
        boolean tag = key.startsWith("#");
        ResourceLocation rl = ResourceLocation.tryParse(tag ? key.substring(1) : key);
        if (rl == null) return;
        if (!tag) { covered.merge(rl.getNamespace(), 1, Integer::sum); return; }
        Optional<HolderSet.Named<Item>> set = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, rl));
        set.ifPresent(s -> s.forEach(h -> h.unwrapKey().ifPresent(k -> covered.merge(k.location().getNamespace(), 1, Integer::sum))));
    }
}

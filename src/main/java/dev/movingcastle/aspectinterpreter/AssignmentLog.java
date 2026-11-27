package dev.movingcastle.aspectinterpreter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The readable record of everything this mod serves: {@code config/aspect_interpreter/assignments.json}. It is the
 * overlay (items) and the guessed mobs, with how each came about, sorted and free of times or counters so the same pack
 * gives the same bytes on every launch. Recomputed every launch; never read back.
 */
final class AssignmentLog {
    private AssignmentLog() {}

    private static final Map<String, String> ITEMS = new ConcurrentSkipListMap<>();
    private static final Map<String, String> ENTITIES = new ConcurrentSkipListMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static void clearItems() { ITEMS.clear(); }

    static void clearEntities() { ENTITIES.clear(); }

    static void item(String id, Map<String, Integer> aspects, double conf, String basis) { ITEMS.put(id, line(aspects, conf, basis)); }

    static void entity(String id, Map<String, Integer> aspects, double conf, String basis) { ENTITIES.put(id, line(aspects, conf, basis)); }

    private static String line(Map<String, Integer> aspects, double conf, String basis) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "confidence %.2f, strongest evidence %s", conf, basis == null ? "-" : basis.trim()));
        if (aspects == null) sb.append("; left unassigned");
        return sb.toString();
    }

    /** The whole report as text (also what is written to disk). */
    static String json() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("about", "Aspect Interpreter's values, computed from scratch on every launch and served on top of Thaumaturge's index; "
                + "Thaumaturge's own index and cache file hold none of them. Nothing here is read back. To override one, give it a "
                + "base_aspects / entity_aspects entry in a datapack: hand-written entries always win and are never touched.");
        root.put("version", AspectInterpreter.VERSION);
        Map<String, Object> items = new TreeMap<>();
        Map<String, String> added = new TreeMap<>(Additions.itemLog());
        Overlay.sorted().forEach((id, aspects) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("aspects", aspects);
            String how = ITEMS.get(id), extra = added.get(id);
            if (how != null && !how.contains("left unassigned")) e.put("guess", how);
            if (extra != null) e.put("additions", extra);
            if (how == null && extra == null) e.put("note", "derived from one or more items above");
            items.put(id, e);
        });
        root.put("items", items);
        Map<String, String> unassigned = new TreeMap<>();
        ITEMS.forEach((id, v) -> { if (v.contains("left unassigned")) unassigned.put(id, v); });
        root.put("items_left_unassigned", unassigned);
        Map<String, Object> ents = new TreeMap<>();
        EntityInterpreter.table().forEach((id, aspects) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("aspects", new TreeMap<>(aspects));
            String how = ENTITIES.get(id), extra = Additions.entityLog().get(id);
            if (how != null) e.put("guess", how);
            if (extra != null) e.put("enrichment_and_tier", extra);
            ents.put(id, e);
        });
        root.put("entities", ents);
        Map<String, String> entUnassigned = new TreeMap<>();
        ENTITIES.forEach((id, v) -> { if (v.contains("left unassigned")) entUnassigned.put(id, v); });
        root.put("entities_left_unassigned", entUnassigned);
        return GSON.toJson(root) + "\n";
    }

    static void write() {
        if (!Config.get().writeReport) return;
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("aspect_interpreter");
            Files.createDirectories(dir);
            Files.deleteIfExists(dir.resolve("assignments.txt")); // the 1.10.0 and earlier report
            Files.writeString(dir.resolve("assignments.json"), json(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AspectInterpreter.LOG.warn("Could not write aspect report", e);
        }
    }
}

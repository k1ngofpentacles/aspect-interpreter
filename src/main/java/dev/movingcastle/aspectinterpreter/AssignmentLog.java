package dev.movingcastle.aspectinterpreter;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/** Records every guess so pack makers can review and override them. */
final class AssignmentLog {
    private AssignmentLog() {}

    private static final Map<String, String> ITEMS = new ConcurrentSkipListMap<>();
    private static final Map<String, String> ENTITIES = new ConcurrentSkipListMap<>();

    static void clearItems() { ITEMS.clear(); }

    static void item(String id, Map<String, Integer> aspects, double conf, String basis) { ITEMS.put(id, line(aspects, conf, basis)); }

    static void entity(String id, Map<String, Integer> aspects, double conf, String basis) { ENTITIES.put(id, line(aspects, conf, basis)); }

    private static String line(Map<String, Integer> aspects, double conf, String basis) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%.2f  %-11s ", conf, basis == null ? "-" : basis));
        if (aspects == null) return sb.append("(left unassigned)").toString();
        StringJoiner j = new StringJoiner(" ");
        aspects.forEach((a, x) -> j.add(a.replace("thaumaturge:", "") + " " + x));
        return sb.append(j).toString();
    }

    static void write() {
        if (!Config.get().writeReport) return;
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("aspect_interpreter");
            Files.createDirectories(dir);
            List<String> out = new ArrayList<>();
            out.add("# Aspect Interpreter assignments. Columns: id | confidence | strongest evidence | aspects");
            out.add("# To override one, give it a base_aspects / entity_aspects entry in a datapack; real entries always win.");
            out.add("");
            out.add("## Items (" + ITEMS.size() + ")");
            ITEMS.forEach((k, v) -> out.add(k + " | " + v));
            out.add("");
            out.add("## Entities guessed so far (" + ENTITIES.size() + "; entities are interpreted when first scanned)");
            ENTITIES.forEach((k, v) -> out.add(k + " | " + v));
            Files.write(dir.resolve("assignments.txt"), out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            AspectInterpreter.LOG.warn("Could not write aspect report", e);
        }
    }
}

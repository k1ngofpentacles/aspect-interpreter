package dev.movingcastle.aspectinterpreter;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The items this mod filled in during the last index build. Thaumaturge writes them into its cache
 * alongside its own results, so they are excluded when learning from that cache.
 */
final class InterpretedIds {
    private InterpretedIds() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("aspect_interpreter").resolve("interpreted_items.txt");
    }

    static Set<String> load() {
        try {
            Set<String> s = new HashSet<>();
            Path p = file();
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) s.add(line);
                }
            }
            // 1.1.0 only wrote the human-readable report; treat every item it assigned as ours.
            Path report = p.resolveSibling("assignments.txt");
            if (Files.exists(report)) {
                boolean inItems = false;
                for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
                    if (line.startsWith("## ")) { inItems = line.startsWith("## Items"); continue; }
                    int bar = line.indexOf(" | ");
                    if (inItems && bar > 0 && !line.contains("(left unassigned)")) s.add(line.substring(0, bar).trim());
                }
            }
            return s;
        } catch (Exception e) {
            return Set.of();
        }
    }

    static void save(Collection<String> ids) {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Items Aspect Interpreter filled in during the last Thaumaturge index build. Do not edit.");
            lines.addAll(new TreeSet<>(ids));
            Files.write(p, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            AspectInterpreter.LOG.warn("Aspect Interpreter could not save its interpreted-item list", e);
        }
    }
}

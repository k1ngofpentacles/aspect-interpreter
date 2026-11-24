package dev.movingcastle.aspectinterpreter;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The items this mod filled in or added to during the last index build, and every item Thaumaturge derived from
 * them through recipes. Thaumaturge writes all of them into its cache alongside its own results, so they are
 * excluded when learning from that cache. Without the derived items the model would learn its own guesses
 * second-hand (a guessed ingredient makes its products richer, which makes the next guess richer) and inflate
 * a little more on every launch.
 */
final class InterpretedIds {
    private InterpretedIds() {}

    /** Header of the current format. Older files list only direct guesses, so the cache they describe is untrusted. */
    static final String FORMAT = "# format 2: guesses, additions and everything derived from them";

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("aspect_interpreter").resolve("interpreted_items.txt");
    }

    /**
     * The excluded ids, or null if the last build predates format 2 (its cache may hold second-hand guesses and must
     * not be learned from). No file at all means this mod never took part in a build: the cache is Thaumaturge's own.
     */
    static Set<String> load() {
        try {
            Set<String> s = new HashSet<>();
            Path p = file();
            if (!Files.exists(p)) return s;
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            if (lines.stream().noneMatch(FORMAT::equals)) return null;
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) s.add(line);
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Collection<String> ids, int derived) {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Items Aspect Interpreter filled in or added to during the last Thaumaturge index build, and the "
                    + derived + " items derived from them (all excluded from learning). Do not edit.");
            lines.add(FORMAT);
            lines.addAll(new TreeSet<>(ids));
            Files.write(p, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            AspectInterpreter.LOG.warn("Aspect Interpreter could not save its interpreted-item list", e);
        }
    }

    /**
     * Every item reachable from {@code roots} through {@code products} (ingredient -> items made from it), roots
     * included. Plain Java so it is unit-testable.
     */
    static Set<String> downstream(Set<String> roots, Map<String, ? extends Collection<String>> products) {
        Set<String> out = new HashSet<>(roots);
        Deque<String> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            Collection<String> next = products.get(todo.pop());
            if (next != null) for (String n : next) if (out.add(n)) todo.push(n);
        }
        return out;
    }
}

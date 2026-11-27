package dev.movingcastle.aspectinterpreter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** ALGORITHM step 6 on a mob guess (1.11.1). Pure Java: no Minecraft classes. */
final class MissingAspects {
    private MissingAspects() {}

    /**
     * Drops aspects the pack doesn't define and rescales the rest by total/(total-dropped).
     * An empty {@code available} means "not known yet": the guess is returned unchanged.
     */
    static Map<String, Double> dropMissing(Map<String, Double> guess, Set<String> available) {
        if (available.isEmpty()) return guess;
        double total = 0, dropped = 0;
        boolean any = false;
        for (Map.Entry<String, Double> e : guess.entrySet()) {
            total += e.getValue();
            if (!available.contains(e.getKey())) { dropped += e.getValue(); any = true; }
        }
        if (!any) return guess;
        Map<String, Double> out = new LinkedHashMap<>();
        if (dropped >= total) return out;
        double scale = total / (total - dropped);
        for (Map.Entry<String, Double> e : guess.entrySet()) {
            if (available.contains(e.getKey())) out.put(e.getKey(), (double) Math.max(1, Math.round(e.getValue() * scale)));
        }
        return out;
    }
}

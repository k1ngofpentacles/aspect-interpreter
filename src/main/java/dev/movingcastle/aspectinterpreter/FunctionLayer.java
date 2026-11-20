package dev.movingcastle.aspectinterpreter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The function layer: what an object is FOR.
 *
 * Thaumaturge's authors write function into key items by hand (a chest is vacuos, a bookshelf cognitio, a
 * piston machina), but the recipe rule can't add it, so a derived barrel is only wood. This layer adds the
 * missing function aspect to recipe-derived and interpreted items whose name calls for it.
 *
 * <ul>
 *   <li>Only the <b>head</b> of the name counts: its last word or two, after trailing colour and size words.
 *       An {@code armor_stand} is a stand; a {@code light_blue_wool} is wool.</li>
 *   <li>Each word-aspect pair is checked against the pack's hand-written items. If 3 or more of them have that
 *       head and fewer than half carry the aspect, the authors evidently don't do this and the pair is skipped
 *       (Thaumaturge does not make bottles vacuos, for example).</li>
 *   <li>At most one function aspect is added per item, sized like a minor aspect in the original layer (the
 *       aspect's median minor share, capped at 20%), and only if the item lacks it.</li>
 * </ul>
 * Hand-written items are never changed by this layer: their function was chosen by their authors.
 */
final class FunctionLayer {
    private FunctionLayer() {}

    private static final Map<String, Set<String>> WORDS = new HashMap<>();   // head word -> aspects
    private static volatile Set<String> contradicted = Set.of();              // "word|aspect"

    static {
        try (InputStream in = FunctionLayer.class.getResourceAsStream("/data/aspect_interpreter/lexicon.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject f = root.getAsJsonObject("functions");
            if (f != null) for (Map.Entry<String, JsonElement> e : f.entrySet()) {
                if (e.getKey().startsWith("_") || !e.getValue().isJsonArray()) continue;
                for (JsonElement w : (JsonArray) e.getValue()) WORDS.computeIfAbsent(normalise(w.getAsString()), k -> new HashSet<>()).add(e.getKey());
            }
        } catch (Exception ex) {
            AspectInterpreter.LOG.error("Could not load function words", ex);
        }
    }

    private static String normalise(String phrase) {
        StringJoiner j = new StringJoiner("_");
        for (String p : phrase.split("_")) j.add(Tokens.norm(p));
        return j.toString();
    }

    /** The head of an ID: its last word and last two words, after trailing neutral (colour, size) words. */
    static List<String> heads(String id) {
        List<String> t = new ArrayList<>(Tokens.of(id));
        Set<String> neutral = Knowledge.LEXICON.neutral;
        while (!t.isEmpty()) {
            int n = t.size();
            if (n > 1 && neutral.contains(t.get(n - 2) + "_" + t.get(n - 1))) { t.remove(n - 1); t.remove(n - 2); }
            else if (neutral.contains(t.get(n - 1))) t.remove(n - 1);
            else break;
        }
        if (t.isEmpty()) return List.of();
        int n = t.size();
        return n > 1 ? List.of(t.get(n - 2) + "_" + t.get(n - 1), t.get(n - 1)) : List.of(t.get(0));
    }

    static void calibrate(Map<String, Map<String, Double>> handWritten) {
        Map<String, int[]> counts = new HashMap<>();
        handWritten.forEach((id, aspects) -> {
            for (String h : heads(id))
                for (String a : WORDS.getOrDefault(h, Set.of())) {
                    int[] c = counts.computeIfAbsent(h + "|" + a, k -> new int[2]);
                    c[0]++;
                    if (aspects.containsKey(a)) c[1]++;
                }
        });
        Set<String> bad = new HashSet<>();
        counts.forEach((k, c) -> { if (c[0] >= 3 && c[1] * 2 < c[0]) bad.add(k); });
        contradicted = bad;
    }

    /** Up to one missing function aspect for this ID, with its amount. */
    static Map<String, Integer> additions(String id, Set<String> present, int total) {
        if (!Config.get().functionLayer || total <= 0) return Map.of();
        for (String h : heads(id)) {
            Set<String> aspects = WORDS.get(h);
            if (aspects == null) continue;
            for (String a : aspects) {
                if (present.contains(a) || contradicted.contains(h + "|" + a)) continue;
                return Map.of(a, (int) Math.max(1, Math.round(Knowledge.minorShare(a) * total)));
            }
            return Map.of(); // the head matched but its function is already present (or contradicted)
        }
        return Map.of();
    }
}

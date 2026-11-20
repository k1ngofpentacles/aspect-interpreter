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
 * The thematic meaning of each aspect, expressed as name fragments that evoke it.
 * Weights are calibrated against the pack's existing assignments, so a word that the
 * pack consistently contradicts ("crimson" on wooden blocks is herba, not infernum)
 * loses influence automatically.
 */
final class Lexicon {
    final Map<String, Map<String, Double>> keywords = new HashMap<>();
    final Set<String> neutral = new HashSet<>();
    final Map<String, String> meanings = new LinkedHashMap<>();
    final Map<String, Map<String, Double>> dimensions = new HashMap<>();
    private volatile Map<String, Double> calibration = Map.of();

    static Lexicon load() {
        Lexicon lex = new Lexicon();
        try (InputStream in = Lexicon.class.getResourceAsStream("/data/aspect_interpreter/lexicon.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("neutral")) lex.neutral.add(Tokens.norm(e.getAsString()));
            JsonObject dims = root.getAsJsonObject("dimensions");
            if (dims != null) for (Map.Entry<String, JsonElement> d : dims.entrySet()) {
                if (!d.getValue().isJsonObject()) continue;
                Map<String, Double> w = new HashMap<>();
                d.getValue().getAsJsonObject().entrySet().forEach(x -> w.put(x.getKey(), x.getValue().getAsDouble()));
                lex.dimensions.put(d.getKey(), w);
            }
            for (Map.Entry<String, JsonElement> a : root.getAsJsonObject("aspects").entrySet()) {
                JsonObject def = a.getValue().getAsJsonObject();
                lex.meanings.put(a.getKey(), def.get("meaning").getAsString());
                JsonArray words = def.getAsJsonArray("words");
                for (JsonElement w : words) {
                    String raw = w.getAsString();
                    int c = raw.indexOf(':');
                    String word = c >= 0 ? raw.substring(0, c) : raw;
                    double weight = c >= 0 ? Double.parseDouble(raw.substring(c + 1)) : 1.0;
                    StringJoiner key = new StringJoiner("_");
                    for (String p : word.split("_")) key.add(Tokens.norm(p));
                    String k = key.toString();
                    if (lex.neutral.contains(k)) continue;
                    lex.keywords.computeIfAbsent(k, x -> new HashMap<>()).merge(a.getKey(), weight, Math::max);
                }
            }
        } catch (Exception ex) {
            AspectInterpreter.LOG.error("Could not load aspect lexicon", ex);
        }
        return lex;
    }

    record Match(String gram, Map<String, Double> weights) {}

    /** Longest phrases first; a matched phrase consumes its words ("dark_oak" stops "dark" counting). */
    List<Match> matches(List<String> tokens) {
        boolean[] used = new boolean[tokens.size()];
        List<Match> out = new ArrayList<>();
        for (int n = 3; n >= 1; n--) {
            for (int i = 0; i + n <= tokens.size(); i++) {
                boolean taken = false;
                for (int j = i; j < i + n; j++) taken |= used[j];
                if (taken) continue;
                String g = String.join("_", tokens.subList(i, i + n));
                Map<String, Double> w = keywords.get(g);
                if (w != null) {
                    out.add(new Match(g, w));
                    for (int j = i; j < i + n; j++) used[j] = true;
                }
            }
        }
        return out;
    }

    Map<String, Double> dimensionPrior(String dim) {
        return dimensions.getOrDefault(dim, Map.of());
    }

    double calibrationFor(String gram, String aspect) {
        return calibration.getOrDefault(gram + "|" + aspect, 1.0);
    }

    /** Measures how often each keyword's aspect actually appears on items containing that keyword. */
    void calibrate(Map<String, Map<String, Double>> train) {
        Map<String, int[]> counts = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : train.entrySet()) {
            for (Match m : matches(Tokens.of(e.getKey()))) {
                for (String a : m.weights().keySet()) {
                    int[] c = counts.computeIfAbsent(m.gram() + "|" + a, k -> new int[2]);
                    c[0]++;
                    if (e.getValue().containsKey(a)) c[1]++;
                }
            }
        }
        Map<String, Double> cal = new HashMap<>();
        counts.forEach((k, c) -> {
            if (c[0] >= 4) cal.put(k, Math.max(0.05, Math.min(1.5, 2 * (c[1] + 0.5) / (c[0] + 1))));
        });
        calibration = cal;
    }
}

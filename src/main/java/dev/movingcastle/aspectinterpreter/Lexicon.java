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
    // crafting-complexity layer
    final Set<String> formWords = new HashSet<>();
    final Map<String, String> craftAspects = new HashMap<>();
    double craftPerStep = 0.06, craftMaxShare = 0.3;
    // dimensions: underworlds get no alienis; pools are flavours a native mob may take when they suit it
    final Set<String> underworlds = new HashSet<>();
    final Map<String, List<String>> pools = new HashMap<>();
    // mobs
    final Set<String> realAnimals = new HashSet<>();
    final Set<String> fantasyWords = new HashSet<>();
    final Map<String, Double> fantasyAspects = new HashMap<>();
    /** "#ns:tag" or a built-in trait ("fire_immune", "monster", "water", "ambient") -> aspect weights. */
    final Map<String, Map<String, Double>> traits = new LinkedHashMap<>();
    /** "#c:biome_tag" -> aspect weights: the landscape reading of a biome. */
    final Map<String, Map<String, Double>> habitats = new LinkedHashMap<>();
    /** Shaping and preparation words that never name a family (cut, polished, roasted...). */
    final Set<String> familyModifiers = new HashSet<>();
    /** Material families whose words only count with evidence (sound, tag, ingredient); see {@link Materials}. */
    final Map<String, Materials.Material> materials = new LinkedHashMap<>();
    private volatile Map<String, Double> calibration = Map.of();

    static Lexicon load() {
        Lexicon lex = new Lexicon();
        try (InputStream in = Lexicon.class.getResourceAsStream("/data/aspect_interpreter/lexicon.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("neutral")) lex.neutral.add(Tokens.norm(e.getAsString()));
            JsonObject craft = root.getAsJsonObject("craftsmanship");
            if (craft != null) {
                craft.getAsJsonObject("aspects").entrySet().forEach(x -> lex.craftAspects.put(x.getKey(), x.getValue().getAsString()));
                for (JsonElement w : craft.getAsJsonArray("form_words")) lex.formWords.add(Tokens.norm(w.getAsString()));
                if (craft.has("per_step")) lex.craftPerStep = craft.get("per_step").getAsDouble();
                if (craft.has("max_share")) lex.craftMaxShare = craft.get("max_share").getAsDouble();
            }
            JsonObject mats = root.getAsJsonObject("materials");
            if (mats != null) for (Map.Entry<String, JsonElement> m : mats.entrySet()) {
                if (!m.getValue().isJsonObject()) continue; // _doc
                JsonObject o = m.getValue().getAsJsonObject();
                Set<String> words = new LinkedHashSet<>(), sounds = new LinkedHashSet<>();
                List<String> tags = new ArrayList<>();
                for (JsonElement w : o.getAsJsonArray("words")) words.add(Tokens.norm(w.getAsString()));
                for (JsonElement w : o.getAsJsonArray("sounds")) sounds.add(w.getAsString());
                for (JsonElement w : o.getAsJsonArray("tags")) tags.add(w.getAsString());
                lex.materials.put(m.getKey(), new Materials.Material(m.getKey(), words, sounds, tags));
            }
            JsonObject dims = root.getAsJsonObject("dimensions");
            if (dims != null) for (Map.Entry<String, JsonElement> d : dims.entrySet()) {
                if (d.getKey().equals("_underworlds")) { for (JsonElement u : d.getValue().getAsJsonArray()) lex.underworlds.add(u.getAsString()); continue; }
                if (d.getKey().equals("_pools")) {
                    for (Map.Entry<String, JsonElement> pl : d.getValue().getAsJsonObject().entrySet()) {
                        List<String> l = new ArrayList<>();
                        for (JsonElement a : pl.getValue().getAsJsonArray()) l.add(a.getAsString());
                        lex.pools.put(pl.getKey(), l);
                    }
                    continue;
                }
                if (!d.getValue().isJsonObject() || (d.getKey().startsWith("_") && !d.getKey().equals("_core"))) continue;
                Map<String, Double> w = new HashMap<>();
                d.getValue().getAsJsonObject().entrySet().forEach(x -> w.put(x.getKey(), x.getValue().getAsDouble()));
                lex.dimensions.put(d.getKey(), w);
            }
            JsonObject fam = root.getAsJsonObject("families");
            if (fam != null) for (JsonElement w : fam.getAsJsonArray("modifier_words")) lex.familyModifiers.add(Tokens.norm(w.getAsString()));
            JsonObject mobs = root.getAsJsonObject("mobs");
            if (mobs != null) {
                for (JsonElement w : mobs.getAsJsonArray("real_animals")) lex.realAnimals.add(Tokens.norm(w.getAsString()));
                for (JsonElement w : mobs.getAsJsonArray("fantasy_words")) lex.fantasyWords.add(Tokens.norm(w.getAsString()));
                mobs.getAsJsonObject("fantasy_aspects").entrySet().forEach(x -> lex.fantasyAspects.put(x.getKey(), x.getValue().getAsDouble()));
                readWeights(mobs.getAsJsonObject("traits"), lex.traits);
                if (mobs.has("habitats")) readWeights(mobs.getAsJsonObject("habitats"), lex.habitats);
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

    private static void readWeights(JsonObject section, Map<String, Map<String, Double>> into) {
        for (Map.Entry<String, JsonElement> t : section.entrySet()) {
            if (!t.getValue().isJsonObject()) continue; // _doc
            Map<String, Double> w = new LinkedHashMap<>();
            t.getValue().getAsJsonObject().entrySet().forEach(x -> w.put(x.getKey(), x.getValue().getAsDouble()));
            into.put(t.getKey(), w);
        }
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

    /** Matches a recipe type id against patterns like "create:*" or "minecraft:smelting". */
    static boolean matchesType(List<String> patterns, String typeId) {
        for (String p : patterns) {
            if (p.endsWith(":*") ? typeId.startsWith(p.substring(0, p.length() - 1)) : p.equals(typeId)) return true;
        }
        return false;
    }

    List<String> dimensionPool(String dim) {
        return pools.getOrDefault(dim, List.of());
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

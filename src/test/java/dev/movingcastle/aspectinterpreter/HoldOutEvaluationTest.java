package dev.movingcastle.aspectinterpreter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hold-out fidelity check of the name/neighbour model (1.11.0). Five folds, each holding out a random 10% of the
 * pack's hand-written items (fixed seed per fold); the model trains on the rest and predicts each held-out id from its
 * name alone, as {@link Model#predict} is driven during an index build (no recipe, material or dimension evidence).
 * Prints a table for two training sets: 1.9.0's (Thaumaturge's cache minus the interpreter's own items) and 1.10.0's
 * (the clean pass). Needs the scratch files named below; skips when they are absent.
 *
 * Directory (env AI_EVAL_DIR): hand_items.json (the pack test's AiDumpTests), run_old/config/thaumaturge/aspect_index.json
 * with run_old/config/aspect_interpreter/interpreted_items.txt, run_new/config/aspect_interpreter/clean_training.json.
 */
class HoldOutEvaluationTest {
    private static final String T = "thaumaturge:";
    private static final int FOLDS = 5;
    private static final double MIN_CONFIDENCE = 0.15;

    private static Path dir() {
        String e = System.getenv("AI_EVAL_DIR");
        return Path.of(e != null ? e : "build/ai-eval");   // set AI_EVAL_DIR to the folder with run_old/ and run_new/
    }

    private static Map<String, Map<String, Double>> readHand(Path p) throws IOException {
        Map<String, Map<String, Double>> out = new TreeMap<>();
        JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            Map<String, Double> m = new TreeMap<>();
            e.getValue().getAsJsonObject().entrySet().forEach(a -> m.put(a.getKey(), a.getValue().getAsDouble()));
            if (!m.isEmpty()) out.put(e.getKey(), m);
        }
        return out;
    }

    private static Map<String, Map<String, Double>> readOld(Path cache, Path interpreted) throws IOException {
        Set<String> skip = new HashSet<>();
        for (String l : Files.readAllLines(interpreted, StandardCharsets.UTF_8)) if (!l.isBlank() && !l.startsWith("#")) skip.add(l.trim());
        Map<String, Map<String, Double>> out = new TreeMap<>();
        JsonObject aspects = JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("aspects");
        for (Map.Entry<String, JsonElement> e : aspects.entrySet()) {
            if (skip.contains(e.getKey())) continue;
            Map<String, Double> m = new TreeMap<>();
            e.getValue().getAsJsonArray().forEach(x -> {
                JsonObject o = x.getAsJsonObject();
                m.merge(o.get("aspect").getAsString(), o.has("amount") ? o.get("amount").getAsDouble() : 1.0, Double::sum);
            });
            if (!m.isEmpty()) out.put(e.getKey(), m);
        }
        return out;
    }

    private static Map<String, Map<String, Double>> readNew(Path p) throws IOException {
        Map<String, Map<String, Double>> out = new TreeMap<>();
        JsonObject items = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("items");
        for (Map.Entry<String, JsonElement> e : items.entrySet()) {
            Map<String, Double> m = new TreeMap<>();
            e.getValue().getAsJsonObject().entrySet().forEach(a -> m.put(a.getKey(), a.getValue().getAsDouble()));
            if (!m.isEmpty()) out.put(e.getKey(), m);
        }
        return out;
    }

    private static String top(Map<String, ? extends Number> m) {
        String best = null;
        double bv = -1;
        for (Map.Entry<String, ? extends Number> e : m.entrySet())
            if (e.getValue().doubleValue() > bv || (e.getValue().doubleValue() == bv && e.getKey().compareTo(best) < 0)) { best = e.getKey(); bv = e.getValue().doubleValue(); }
        return best;
    }

    private static double pct(List<Double> sorted, double p) {
        return sorted.isEmpty() ? Double.NaN : sorted.get(Math.min(sorted.size() - 1, (int) (p * sorted.size())));
    }

    /** Result of one training set over all folds. */
    private record Result(int heldOut, int predicted, double precision, double recall, double topMatch, double medianRatio, double p90Ratio, int leaked,
                          double precisionClean, double recallClean, double topClean, double medianClean, int predictedClean) {}

    private static Result evaluate(Map<String, Map<String, Double>> trainingSet, Map<String, Map<String, Double>> hand, boolean weighted) {
        List<String> handIds = new ArrayList<>(hand.keySet());
        int per = Math.max(1, handIds.size() / 10);
        int held = 0, predicted = 0, leaked = 0;
        double inter = 0, predSize = 0, trueSize = 0;
        int top = 0;
        List<Double> ratios = new ArrayList<>();
        double interC = 0, predSizeC = 0, trueSizeC = 0;
        int topC = 0, predC = 0;
        List<Double> ratiosC = new ArrayList<>();
        for (int fold = 0; fold < FOLDS; fold++) {
            List<String> ids = new ArrayList<>(handIds);
            Collections.shuffle(ids, new Random(20261127L + fold));
            Set<String> out = new HashSet<>(ids.subList(0, per));
            Map<String, Map<String, Double>> train = new TreeMap<>();
            trainingSet.forEach((id, v) -> { if (!out.contains(id)) train.put(id, v); });
            hand.forEach((id, v) -> { if (!out.contains(id)) train.put(id, v); });
            Lexicon lex = Lexicon.load();
            lex.calibrate(train);
            double maxHand = 0;
            for (String id : handIds) if (!out.contains(id)) maxHand = Math.max(maxHand, Model.total(hand.get(id)));
            Model model = new Model(train, lex).withMaxTotal(maxHand);
            if (weighted) { Set<String> h = new HashSet<>(handIds); h.removeAll(out); model.withHandWritten(h); }
            for (String id : new TreeSet<>(out)) {
                held++;
                Map<String, Double> truth = hand.get(id);
                Model.Prediction p = model.predict(id, List.of(), 8, null);
                if (p == null || p.confidence() < MIN_CONFIDENCE) continue;
                predicted++;
                // leakage: a training item that is not hand-written, has the same aspect set and a name containing the held-out item's words
                boolean leak = false;
                List<String> words = Tokens.of(id);
                for (Map.Entry<String, Map<String, Double>> e : train.entrySet()) {
                    if (hand.containsKey(e.getKey()) || !e.getValue().keySet().equals(truth.keySet())) continue;
                    if (Tokens.of(e.getKey()).containsAll(words)) { leak = true; break; }
                }
                if (leak) leaked++;
                int ip = 0;
                for (String a : p.aspects().keySet()) if (truth.containsKey(a)) ip++;
                double pt = p.aspects().values().stream().mapToInt(Integer::intValue).sum();
                double tt = Model.total(truth);
                boolean tm = Objects.equals(top(p.aspects()), top(truth));
                inter += ip; predSize += p.aspects().size(); trueSize += truth.size();
                if (tm) top++;
                ratios.add(pt / tt);
                if (!leak) {
                    predC++; interC += ip; predSizeC += p.aspects().size(); trueSizeC += truth.size();
                    if (tm) topC++;
                    ratiosC.add(pt / tt);
                }
            }
        }
        Collections.sort(ratios);
        Collections.sort(ratiosC);
        return new Result(held, predicted, inter / predSize, inter / trueSize, (double) top / predicted, pct(ratios, 0.5), pct(ratios, 0.9), leaked,
                interC / predSizeC, interC / trueSizeC, (double) topC / predC, pct(ratiosC, 0.5), predC);
    }

    private static void print(String name, Map<String, Map<String, Double>> set, Result r) {
        System.out.printf(Locale.ROOT, "%-34s | train %5d | held %4d predicted %4d (%.0f%%) | precision %.2f recall %.2f top-aspect %.2f | total ratio median %.2f p90 %.2f | leaked %d%n",
                name, set.size(), r.heldOut(), r.predicted(), 100.0 * r.predicted() / r.heldOut(), r.precision(), r.recall(), r.topMatch(), r.medianRatio(), r.p90Ratio(), r.leaked());
        System.out.printf(Locale.ROOT, "%-34s | without leaked items: predicted %4d | precision %.2f recall %.2f top-aspect %.2f | total ratio median %.2f%n",
                "", r.predictedClean(), r.precisionClean(), r.recallClean(), r.topClean(), r.medianClean());
    }

    @Test
    void holdOutTable() throws IOException {
        Path d = dir();
        Path handFile = d.resolve("hand_items.json");
        Path oldCache = d.resolve("run_old/config/thaumaturge/aspect_index.json");
        Path oldInterpreted = d.resolve("run_old/config/aspect_interpreter/interpreted_items.txt");
        Path newClean = d.resolve("run_new/config/aspect_interpreter/clean_training.json");
        Assumptions.assumeTrue(Files.exists(handFile) && Files.exists(oldCache) && Files.exists(oldInterpreted) && Files.exists(newClean),
                "hold-out scratch files absent: skipped");
        Map<String, Map<String, Double>> hand = readHand(handFile);
        Map<String, Map<String, Double>> oldSet = readOld(oldCache, oldInterpreted);
        Map<String, Map<String, Double>> newSet = readNew(newClean);
        System.out.println("== Aspect Interpreter hold-out (5 folds x 10% of " + hand.size() + " hand-written items, name-only prediction, min confidence " + MIN_CONFIDENCE + ")");
        Result o = evaluate(oldSet, hand, false);
        print("old (1.9.0: cache minus interpreted)", oldSet, o);
        Result n = evaluate(newSet, hand, false);
        print("new, derived weight 1.0 (1.10.0)", newSet, n);
        print("new, derived weight " + Model.DERIVED_WEIGHT + " (1.11.0)", newSet, evaluate(newSet, hand, true));
        assertTrue(o.predicted() > 0 && n.predicted() > 0);
    }
}

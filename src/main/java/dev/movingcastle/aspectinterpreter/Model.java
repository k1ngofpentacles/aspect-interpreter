package dev.movingcastle.aspectinterpreter;

import java.util.*;

/**
 * The interpretive model. Three kinds of evidence are combined for an unassigned ID:
 * <ol>
 *   <li><b>Neighbours</b> – assigned objects whose names share informative words
 *       ({@code andesite_lower_roof} ~ {@code andesite_roof}). Words are weighted by rarity and
 *       by how consistently they predict one aspect, so materials outweigh shapes.</li>
 *   <li><b>Learned word profiles</b> – the average aspect mix of every assigned object
 *       containing each word.</li>
 *   <li><b>Meaning</b> – the calibrated {@link Lexicon}; trusted more when no close neighbour exists.</li>
 * </ol>
 * Callers may add further evidence (recipes, block material, mob category) as {@link Source}s.
 */
final class Model {
    static final double SIM_MIN = 0.30, W_LEX = 0.5, W_KNN = 1.5, W_TOK = 0.6;
    /** Minimum share an aspect needs to be kept. Tuned so guesses carry as many aspects as the original layer. */
    static double KEEP = 0.08;
    /** Scarcity prior: aspect weights are multiplied by prevalence^beta, so rare aspects need stronger evidence. */
    static double SCARCITY_BETA = 0.0;
    /** Keep about as many aspects as the nearest assigned neighbours carry (the original layer's local scarcity). */
    static boolean NEIGHBOUR_COUNT = true;
    private static final double PREVALENCE_FLOOR = 0.003;
    static final int K = 5, MAX_ASPECTS = 4;
    private static final double LN_ASPECTS = Math.log(46);

    record Source(String name, Map<String, Double> dist, double weight) {}
    record Prediction(LinkedHashMap<String, Integer> aspects, double confidence, String basis) {}

    private record Profile(Map<String, Double> w, int n, double info, double medianTotal) {}

    private final Map<String, Map<String, Double>> train;
    private final Lexicon lex;
    private final Map<String, Set<String>> inverted = new HashMap<>();
    private final Map<String, Double> idf = new HashMap<>();
    private final Map<String, Profile> profiles = new HashMap<>();
    private final Map<String, Set<String>> tokenSets = new HashMap<>();
    /** Fraction of training objects carrying each aspect: the original layer's scarcity. */
    final Map<String, Double> prevalence = new HashMap<>();

    Model(Map<String, Map<String, Double>> train, Lexicon lex) {
        this.train = train;
        this.lex = lex;
        Map<String, Map<String, Double>> sums = new HashMap<>();
        Map<String, List<Double>> totals = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : train.entrySet()) {
            Set<String> toks = new LinkedHashSet<>(Tokens.of(e.getKey()));
            tokenSets.put(e.getKey(), toks);
            double tot = total(e.getValue());
            if (tot <= 0) continue;
            for (String t : toks) {
                Map<String, Double> s = sums.computeIfAbsent(t, k -> new HashMap<>());
                e.getValue().forEach((a, x) -> s.merge(a, x / tot, Double::sum));
                totals.computeIfAbsent(t, k -> new ArrayList<>()).add(tot);
                inverted.computeIfAbsent(t, k -> new HashSet<>()).add(e.getKey());
            }
        }
        int n = train.size();
        for (Map<String, Double> v : train.values()) for (String a : v.keySet()) prevalence.merge(a, 1.0 / Math.max(1, n), Double::sum);
        inverted.forEach((t, r) -> idf.put(t, Math.log((n + 1.0) / (r.size() + 1.0)) + 1));
        sums.forEach((t, s) -> {
            List<Double> tl = totals.get(t);
            int cnt = tl.size();
            Map<String, Double> dist = new HashMap<>();
            s.forEach((a, x) -> dist.put(a, x / cnt));
            double h = 0;
            for (double p : dist.values()) if (p > 0) h -= p * Math.log(p);
            double info = lex.neutral.contains(t) ? 0.05 : Math.max(0.15, 1 - h / LN_ASPECTS);
            Collections.sort(tl);
            profiles.put(t, new Profile(top(dist, 6), cnt, info, tl.get(cnt / 2)));
        });
    }

    boolean knows(String id) { return train.containsKey(id); }
    Set<String> ids() { return Collections.unmodifiableSet(train.keySet()); }
    Map<String, Double> known(String id) { return train.get(id); }

    private double info(String t) {
        Profile p = profiles.get(t);
        return p != null ? p.info() : (lex.neutral.contains(t) ? 0.05 : 0.6);
    }

    Prediction predict(String id, List<Source> extra, double fallbackTotal, Double forcedTotal) {
        List<String> tokens = Tokens.of(id);
        Set<String> T = new LinkedHashSet<>(tokens);
        String ns = Tokens.namespace(id);

        // 1. neighbours
        Map<String, Double> cand = new HashMap<>();
        for (String t : T) {
            Set<String> r = inverted.get(t);
            if (r == null || r.size() >= 4000) continue;
            double w = idf.get(t) * info(t);
            for (String x : r) cand.merge(x, w, Double::sum);
        }
        List<Map.Entry<String, Double>> pre = new ArrayList<>(cand.entrySet());
        pre.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<double[]> sims = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(300, pre.size()); i++) {
            String r = pre.get(i).getKey();
            if (r.equals(id)) continue;
            Set<String> uni = new HashSet<>(T);
            uni.addAll(tokenSets.get(r));
            double u = 0;
            for (String t : uni) u += idf.getOrDefault(t, 3.0) * info(t);
            double sim = pre.get(i).getValue() / u + (Tokens.namespace(r).equals(ns) ? 0.05 : 0);
            sims.add(new double[]{sim, ids.size()});
            ids.add(r);
        }
        sims.sort((a, b) -> Double.compare(b[0], a[0]));
        Map<String, Double> knn = new HashMap<>();
        double kt = 0, ks = 0, best = 0, kc = 0;
        for (int i = 0; i < Math.min(K, sims.size()); i++) {
            double sim = sims.get(i)[0];
            if (sim < SIM_MIN) break;
            if (i == 0) best = sim;
            Map<String, Double> v = train.get(ids.get((int) sims.get(i)[1]));
            double tot = total(v);
            double fs = sim;
            v.forEach((a, x) -> knn.merge(a, fs * x / tot, Double::sum));
            kt += sim * tot;
            kc += sim * v.size();
            ks += sim;
        }

        // 2. learned word profiles
        Map<String, Double> tok = new HashMap<>();
        double tt = 0, tsum = 0;
        for (String t : T) {
            Profile p = profiles.get(t);
            if (p == null) continue;
            double c = p.n() / (p.n() + 2.0) * p.info();
            p.w().forEach((a, x) -> tok.merge(a, c * x, Double::sum));
            tt += c * p.medianTotal();
            tsum += c;
        }

        // 3. meaning
        Map<String, Double> lx = new HashMap<>();
        int hits = 0;
        for (Lexicon.Match m : lex.matches(tokens)) {
            int len = 1 + (int) m.gram().chars().filter(ch -> ch == '_').count();
            m.weights().forEach((a, x) -> lx.merge(a, x * lex.calibrationFor(m.gram(), a) * len, Double::sum));
            hits++;
        }

        List<Source> sources = new ArrayList<>();
        sources.add(new Source("neighbours", normalise(knn), W_KNN * best));
        sources.add(new Source("words", normalise(tok), W_TOK * Math.min(1, tsum)));
        if (hits > 0) sources.add(new Source("meaning", normalise(lx), W_LEX * Math.min(1, 0.5 + 0.25 * hits) * Math.max(0.15, 1 - best)));
        if (extra != null) for (Source s : extra) sources.add(new Source(s.name(), normalise(s.dist()), s.weight()));

        double W = 0;
        for (Source s : sources) if (!s.dist().isEmpty()) W += s.weight();
        if (W <= 0) return null;
        Map<String, Double> dist = new HashMap<>();
        String basis = null;
        double basisW = 0;
        for (Source s : sources) {
            if (s.dist().isEmpty() || s.weight() <= 0) continue;
            double w = s.weight() / W;
            s.dist().forEach((a, x) -> dist.merge(a, w * x, Double::sum));
            if (s.weight() > basisW) { basisW = s.weight(); basis = s.name(); }
        }
        if (SCARCITY_BETA != 0) {
            dist.replaceAll((a, x) -> x * Math.pow(prevalence.getOrDefault(a, 0.0) + PREVALENCE_FLOOR, SCARCITY_BETA));
            double z0 = 0;
            for (double x : dist.values()) z0 += x;
            if (z0 > 0) { final double zz = z0; dist.replaceAll((a, x) -> x / zz); }
        }
        double totalAmount = forcedTotal != null ? forcedTotal
                : ks > 0 ? kt / ks : tsum > 0 ? tt / tsum : fallbackTotal;

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(dist.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        // how many aspects: as many as the neighbours typically carry, when they are close enough to say
        int limit = MAX_ASPECTS;
        double keep = KEEP;
        if (NEIGHBOUR_COUNT && ks > 0 && best >= 0.5) {
            limit = (int) Math.max(1, Math.min(MAX_ASPECTS, Math.round(kc / ks)));
            keep = Math.min(KEEP, 0.04);
        }
        List<Map.Entry<String, Double>> kept = new ArrayList<>();
        for (Map.Entry<String, Double> e : ranked) if (e.getValue() >= keep && kept.size() < limit) kept.add(e);
        if (kept.isEmpty()) kept.add(ranked.get(0));
        double z = 0;
        for (Map.Entry<String, Double> e : kept) z += e.getValue();
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : kept) out.put(e.getKey(), (int) Math.max(1, Math.round(totalAmount * e.getValue() / z)));
        return new Prediction(out, Math.min(1, W / 2), basis);
    }

    static double total(Map<String, Double> v) {
        double t = 0;
        for (double x : v.values()) t += x;
        return t;
    }

    static Map<String, Double> normalise(Map<String, Double> d) {
        double z = 0;
        for (double x : d.values()) z += x;
        if (z <= 0) return Map.of();
        Map<String, Double> o = new HashMap<>();
        for (Map.Entry<String, Double> e : d.entrySet()) o.put(e.getKey(), e.getValue() / z);
        return o;
    }

    private static Map<String, Double> top(Map<String, Double> d, int n) {
        List<Map.Entry<String, Double>> l = new ArrayList<>(d.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Double> o = new HashMap<>();
        for (int i = 0; i < Math.min(n, l.size()); i++) o.put(l.get(i).getKey(), l.get(i).getValue());
        return o;
    }
}

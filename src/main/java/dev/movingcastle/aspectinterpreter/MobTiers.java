package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.AspectDataMaps;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

import java.util.*;

/**
 * Threat tiers: a mob the interpreter guesses may never carry more than the pack's hand-written mobs of the same threat.
 *
 * <p>The tier comes from stats alone (max health, attack damage, hostility, boss status), by fixed thresholds chosen from
 * the stats of the pack's hand-written mobs (see the 1.11.0 notes). The ceilings come from those same hand-written mobs
 * and are computed from scratch at every launch ({@link #prepare}), never saved:
 * <ul>
 *   <li>total: the largest hand-written total in the tier, or the next largest if the largest is more than twice it;</li>
 *   <li>per aspect: the same rule over the hand-written amounts of that aspect in the tier; for an aspect no hand-written mob of the
 *       tier carries, the tier's largest single-aspect amount;</li>
 *   <li>a tier with no hand-written mobs takes the next lower tier's ceilings.</li>
 * </ul>
 */
final class MobTiers {
    private MobTiers() {}

    enum Tier { PASSIVE, WEAK, STRONG, MINI_BOSS, BOSS }

    /** Fixed thresholds (health in half-hearts as Minecraft counts it). */
    static final double BOSS_HEALTH = MobEnrichment.BOSS_HEALTH;   // 150: also what MobEnrichment calls a boss
    static final double MINI_HEALTH = 60, MINI_ATTACK = 12, STRONG_HEALTH = 30, STRONG_ATTACK = 5;

    /** Ceilings of one tier. */
    record Caps(int total, Map<String, Integer> perAspect, int maxSingle) {
        int cap(String aspect) { return perAspect.getOrDefault(aspect, maxSingle); }
    }

    record Stats(double health, double attack, boolean hostile, boolean boss) {}

    /** One hand-written mob, for computing ceilings. */
    record Sample(Tier tier, Map<String, Integer> aspects) {}

    static Tier classify(Stats s) {
        if (s.boss()) return Tier.BOSS;
        if (s.attack() >= MINI_ATTACK || (s.hostile() && s.health() >= MINI_HEALTH)) return Tier.MINI_BOSS;
        if (s.hostile() && (s.health() >= STRONG_HEALTH || s.attack() >= STRONG_ATTACK)) return Tier.STRONG;
        if (s.hostile()) return Tier.WEAK;
        return Tier.PASSIVE;
    }

    /** The highest hand-written value, unless it is more than twice the next highest (an outlier): then the next highest. */
    static int ceiling(List<Integer> values) {
        List<Integer> s = new ArrayList<>(values);
        s.sort(Collections.reverseOrder());
        if (s.isEmpty()) return 0;
        if (s.size() >= 2 && s.get(0) > 2 * s.get(1)) return s.get(1);
        return s.get(0);
    }

    /** Ceilings per tier from hand-written samples; a tier with no samples takes the next lower tier's. PASSIVE may stay absent. */
    static Map<Tier, Caps> capsFrom(Collection<Sample> samples) {
        Map<Tier, Caps> out = new EnumMap<>(Tier.class);
        Caps lower = null;
        for (Tier t : Tier.values()) {
            int single = 0;
            List<Integer> totals = new ArrayList<>();
            Map<String, List<Integer>> perValues = new TreeMap<>();
            boolean any = false;
            for (Sample s : samples) {
                if (s.tier() != t || s.aspects().isEmpty()) continue;
                any = true;
                int sum = 0;
                for (Map.Entry<String, Integer> e : s.aspects().entrySet()) {
                    sum += e.getValue();
                    single = Math.max(single, e.getValue());
                    perValues.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
                }
                totals.add(sum);
            }
            Map<String, Integer> per = new TreeMap<>();
            perValues.forEach((a, l) -> per.put(a, ceiling(l)));
            Caps c = any ? new Caps(ceiling(totals), per, single) : lower;
            if (c != null) { out.put(t, c); lower = c; }
        }
        return out;
    }

    /**
     * Holds a guessed mob to its tier: each aspect at most the tier's ceiling for it, then the total at most the tier's total
     * (all amounts scaled down together, each at least 1; the weakest aspects dropped only if that is not enough).
     * Deterministic: ties are broken by aspect id. Returns the input map itself if nothing changes.
     */
    static Map<String, Integer> clamp(Map<String, Integer> guess, Caps caps) {
        if (caps == null || guess.isEmpty()) return guess;
        Map<String, Integer> out = new LinkedHashMap<>();
        guess.forEach((a, x) -> out.put(a, Math.max(1, Math.min(x, caps.cap(a)))));
        int total = out.values().stream().mapToInt(Integer::intValue).sum();
        if (total > caps.total()) {
            double f = (double) caps.total() / total;
            out.replaceAll((a, x) -> Math.max(1, (int) Math.floor(x * f)));
            total = out.values().stream().mapToInt(Integer::intValue).sum();
            while (total > caps.total() && out.size() > 1) {
                String weakest = null;
                for (Map.Entry<String, Integer> e : out.entrySet())
                    if (weakest == null || e.getValue() < out.get(weakest) || (e.getValue().equals(out.get(weakest)) && e.getKey().compareTo(weakest) > 0)) weakest = e.getKey();
                total -= out.remove(weakest);
            }
        }
        return out.equals(guess) ? guess : out;
    }

    // ---------------------------------------------------------------- per launch

    private static volatile Map<Tier, Caps> caps = Map.of();

    /** Once per launch and reload, from the hand-written mobs of the running pack. */
    static void prepare() {
        List<Sample> samples = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            AspectList real = type.builtInRegistryHolder().getData(AspectDataMaps.ENTITY_ASPECTS);
            if (real == null || real.isEmpty()) continue;
            Map<String, Integer> m = new TreeMap<>();
            Knowledge.toMap(real).forEach((a, x) -> m.put(a, (int) Math.round(x)));
            samples.add(new Sample(tierOf(type), m));
        }
        caps = capsFrom(samples);
        StringJoiner j = new StringJoiner("; ");
        for (Tier t : Tier.values()) {
            Caps c = caps.get(t);
            j.add(t + (c == null ? " none" : " total<=" + c.total() + " single<=" + c.maxSingle()));
        }
        AspectInterpreter.LOG.info("Aspect Interpreter threat tiers from {} hand-written mobs: {}", samples.size(), j);
    }

    static Caps capsOf(Tier tier) { return caps.get(tier); }

    static Tier tierOf(EntityType<?> type) {
        return classify(statsOf(type));
    }

    @SuppressWarnings("unchecked")
    static Stats statsOf(EntityType<?> type) {
        double health = 0, attack = 0;
        try {
            if (DefaultAttributes.hasSupplier(type)) {
                var sup = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
                health = sup.getBaseValue(Attributes.MAX_HEALTH);
                if (sup.hasAttribute(Attributes.ATTACK_DAMAGE)) attack = sup.getBaseValue(Attributes.ATTACK_DAMAGE);
            }
        } catch (Throwable ignored) {
            // an entity whose attributes cannot be read counts as stat-less
        }
        return new Stats(health, attack, type.getCategory() == MobCategory.MONSTER, MobEnrichment.isBoss(type));
    }

    /** The guess held to its tier; also records the change for the report. */
    static Map<String, Integer> applyTo(EntityType<?> type, String id, Map<String, Integer> guess) {
        Tier tier = tierOf(type);
        Map<String, Integer> out = clamp(guess, caps.get(tier));
        if (out != guess) {
            int before = guess.values().stream().mapToInt(Integer::intValue).sum(), after = out.values().stream().mapToInt(Integer::intValue).sum();
            Additions.clamped(id, "tier " + tier + ": total " + before + " -> " + after);
        }
        return out;
    }
}

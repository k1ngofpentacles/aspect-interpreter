package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aura.BiomeAspects;
import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import com.leclowndu93150.thaumaturge.registry.TTDataMaps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.Tags;

import java.util.*;

/**
 * Mobs are the richest source of aspects, and the hardest to process: the place where variety, and dimension
 * flavour, belong. This pass enriches every mob the interpreter guesses (never a hand-written one, since 1.11.0), from four sources:
 *
 * <ol>
 *   <li><b>Habitat</b>: the biomes the mob spawns in, weighted by spawn weight: their landscape (from biome tags,
 *       or climate and name) blended with Thaumaturge's own biome aspects.</li>
 *   <li><b>Dimension</b>: for natives of another dimension, alienis (none for underworlds such as the Nether) and
 *       the lead flavour; further flavours from the dimension's pool only when they suit the mob (its name, traits,
 *       drops), so a wither skeleton takes exanimis and a ghast spiritus, but a strider neither.</li>
 *   <li><b>Behaviour</b>: traits from entity tags and properties (undead, arthropod, illager, aquatic,
 *       fire-immune, hostile).</li>
 *   <li><b>Drops</b>: the aspects of what it drops, at a lower weight.</li>
 * </ol>
 *
 * Habitat and dimension weigh more on fantasy mobs (a 0-1 score from real-animal names, fantasy words, fantasy
 * aspects, other dimensions, undeath and boss status). A balance pass then keeps only supported candidates, up to
 * a count that scales with the mob (3-4 for ordinary mobs, 4-5 for fantasy and other-dimension mobs, 6-7 for
 * bosses), and caps the total added. Rarer mobs get larger additions: bosses x2, structure-only spawns x1.5, rare
 * spawns x1.3. A guessed mob is finally held to its threat tier's hand-written ceiling ({@link MobTiers}).
 */
final class MobEnrichment {
    private MobEnrichment() {}

    static final double BOSS_HEALTH = 150, STRONG = 0.2, WEAK = 0.08, POOL_RELEVANCE = 0.25;
    static final double RARITY_BOSS = 2.0, RARITY_STRUCTURE = 1.5, RARITY_RARE = 1.3;
    private static final String T = "thaumaturge:";

    private static volatile Set<String> available = Set.of();

    /** The aspects this pack defines (empty before the first {@link #prepare}). */
    static Set<String> available() { return available; }

    /** Once per warm-up: which aspects this pack defines. */
    static void prepare(HolderLookup.Provider registries) {
        Set<String> a = new HashSet<>();
        if (registries != null) registries.lookup(InterpreterContributor.ASPECT_REGISTRY)
                .ifPresent(l -> l.listElementIds().forEach(k -> a.add(k.location().toString())));
        available = a;
    }

    // ---------------------------------------------------------------- classification

    static boolean isBoss(EntityType<?> type) {
        if (type.is(Tags.EntityTypes.BOSSES)) return true;
        return maxHealth(type) >= BOSS_HEALTH;
    }

    @SuppressWarnings("unchecked")
    private static double maxHealth(EntityType<?> type) {
        try {
            if (!DefaultAttributes.hasSupplier(type)) return 0;
            return DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type).getBaseValue(Attributes.MAX_HEALTH);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Multiplier for how much a mob's additions (or, for guessed mobs, its whole total) grow with its rarity. */
    static double rarity(EntityType<?> type, String id) {
        if (isBoss(type)) return RARITY_BOSS;
        if (DimensionContext.structureOnly(id)) return RARITY_STRUCTURE;
        Map<Holder<Biome>, Double> hab = DimensionContext.habitat(id);
        if (hab.isEmpty()) return 1.0;
        double maxWeight = 0;
        for (double w : hab.values()) maxWeight = Math.max(maxWeight, w);
        return maxWeight <= 5 || hab.size() <= 2 ? RARITY_RARE : 1.0;
    }

    private static String rarityLabel(double r) {
        return r == RARITY_BOSS ? "boss" : r == RARITY_STRUCTURE ? "structure-only" : r == RARITY_RARE ? "rare" : "common";
    }

    /** 0 = an ordinary animal, 1 = plainly a creature of fantasy. */
    static double fantasy(EntityType<?> type, String id, Map<String, Integer> base, String dim, boolean boss) {
        Lexicon lex = Knowledge.LEXICON;
        List<String> tokens = Tokens.of(id);
        String path = "_" + id.substring(id.indexOf(':') + 1) + "_";
        double f = 0.3;
        boolean animal = false, fantastic = false;
        for (String t : tokens) { animal |= lex.realAnimals.contains(t); fantastic |= lex.fantasyWords.contains(t); }
        if (!animal) for (String a : lex.realAnimals) if (a.contains("_") && path.contains("_" + a + "_")) { animal = true; break; }
        if (animal) f -= 0.5;
        if (fantastic) f += 0.4;
        if (dim != null) f += 0.4;
        if (type.is(EntityTypeTags.UNDEAD)) f += 0.2;
        if (boss) f += 0.2;
        int total = 0;
        for (int x : base.values()) total += x;
        if (total > 0) {
            double share = 0;
            for (Map.Entry<String, Integer> e : base.entrySet()) share += lex.fantasyAspects.getOrDefault(e.getKey(), 0.0) * e.getValue() / total;
            f += 0.6 * Math.min(1, share);
        }
        return Math.max(0, Math.min(1, f));
    }

    // ---------------------------------------------------------------- sources (each aspect -> 0..1)

    private static Map<String, Double> habitat(String id) {
        Map<Holder<Biome>, Double> hab = DimensionContext.habitat(id);
        Map<String, Double> out = new HashMap<>();
        double W = 0;
        for (Map.Entry<Holder<Biome>, Double> e : hab.entrySet()) {
            Map<String, Double> b = biomeAspects(e.getKey());
            if (b.isEmpty()) continue;
            double w = e.getValue();
            b.forEach((a, x) -> out.merge(a, w * x, Double::sum));
            W += w;
        }
        if (W > 0) { double fw = W; out.replaceAll((a, x) -> x / fw); }
        return out;
    }

    /**
     * A biome as habitat: its landscape (snowy -> gelum, forest -> herba, ocean -> aqua...) blended with Thaumaturge's
     * own biome aspects at half weight. Those are a primal palette for aura nodes (cold biomes are ordo, plains aer),
     * canon, but not a description of what lives there; they decide only where the landscape says nothing.
     */
    private static Map<String, Double> biomeAspects(Holder<Biome> biome) {
        Map<String, Double> land = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> h : Knowledge.LEXICON.habitats.entrySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(h.getKey().substring(1));
            if (rl != null && biome.is(TagKey.create(Registries.BIOME, rl))) h.getValue().forEach((a, x) -> land.merge(a, x, Math::max));
        }
        if (land.isEmpty()) {
            Biome b = biome.value();
            float temp = b.getBaseTemperature();
            if (temp < 0.15f) land.put(T + "gelum", 1.0);
            else if (temp > 1.5f && !b.hasPrecipitation()) { land.put(T + "ignis", 0.6); land.put(T + "terra", 0.4); }
            else biome.unwrapKey().ifPresent(k -> {
                for (Lexicon.Match m : Knowledge.LEXICON.matches(Tokens.of(k.location().toString())))
                    m.weights().forEach((a, x) -> land.merge(a, x, Math::max));
            });
        }
        Map<String, Double> palette = new HashMap<>();
        try {
            BiomeAspects data = biome.getData(TTDataMaps.BIOME_ASPECTS);
            if (data != null) {
                int i = 0;
                for (ResourceKey<IAspect> k : data.aspects()) palette.merge(k.location().toString(), 1.0 / ++i, Double::sum);
            }
        } catch (Throwable ignored) {}
        Map<String, Double> out = new HashMap<>(Model.normalise(land));
        Model.normalise(palette).forEach((a, x) -> out.merge(a, 0.5 * x, Double::sum));
        return Model.normalise(out);
    }

    private static Map<String, Double> traits(EntityType<?> type) {
        Map<String, Double> out = new HashMap<>();
        MobCategory cat = type.getCategory();
        for (Map.Entry<String, Map<String, Double>> t : Knowledge.LEXICON.traits.entrySet()) {
            String k = t.getKey();
            boolean has;
            if (k.startsWith("#")) {
                ResourceLocation rl = ResourceLocation.tryParse(k.substring(1));
                has = rl != null && type.is(TagKey.create(Registries.ENTITY_TYPE, rl));
            } else has = switch (k) {
                case "fire_immune" -> type.fireImmune();
                case "monster" -> cat == MobCategory.MONSTER;
                case "water" -> cat == MobCategory.WATER_CREATURE || cat == MobCategory.WATER_AMBIENT
                        || cat == MobCategory.UNDERGROUND_WATER_CREATURE || cat == MobCategory.AXOLOTLS;
                case "ambient" -> cat == MobCategory.AMBIENT;
                default -> false;
            };
            if (has) t.getValue().forEach((a, x) -> out.merge(a, x, Math::max));
        }
        return out;
    }

    private static Map<String, Double> drops(String id) {
        Model items = Knowledge.items();
        Map<String, Double> out = new HashMap<>();
        int n = 0;
        for (String it : DimensionContext.drops(id)) {
            if (!items.knows(it)) continue;
            Model.normalise(items.known(it)).forEach((a, x) -> out.merge(a, x, Double::sum));
            n++;
        }
        if (n > 0) { int fn = n; out.replaceAll((a, x) -> x / fn); }
        return out;
    }

    private static Map<String, Double> name(String id) {
        Map<String, Double> out = new HashMap<>();
        for (Lexicon.Match m : Knowledge.LEXICON.matches(Tokens.of(id)))
            m.weights().forEach((a, x) -> out.merge(a, x * Knowledge.LEXICON.calibrationFor(m.gram(), a), Math::max));
        return out;
    }

    // ---------------------------------------------------------------- enrichment

    private record Candidate(String aspect, double score, String source, boolean guaranteed) {}

    /**
     * The mob's aspects with additions, or null if nothing is added. {@code base} is the interpreter's guess (already
     * scaled by rarity). Only ever called for mobs without a hand-written entry ({@link HandWritten#entity}); a
     * hand-written mob is never enriched. Never changes an existing amount.
     */
    static LinkedHashMap<String, Integer> enrich(EntityType<?> type, String id, Map<String, Integer> base) {
        Config cfg = Config.get();
        if (!cfg.mobEnrichment || base.isEmpty() || type.getCategory() == MobCategory.MISC || cfg.excluded(id) || HandWritten.entity(type)) return null;
        int total = 0;
        for (int x : base.values()) total += x;
        if (total <= 0) return null;

        String dim = DimensionContext.nativeDim(id);
        boolean boss = isBoss(type);
        double r = rarity(type, id);
        double f = fantasy(type, id, base, dim, boss);
        double wPlace = 0.5 + 0.5 * f; // habitat and dimension weigh more on fantasy mobs

        Map<String, Double> score = new HashMap<>();
        Map<String, String> source = new HashMap<>();
        Map<String, Double> best = new HashMap<>();
        contribute(score, source, best, habitat(id), wPlace, "habitat");
        Map<String, Double> traits = traits(type);
        contribute(score, source, best, traits, 0.6, "behaviour");
        Map<String, Double> drops = drops(id);
        contribute(score, source, best, drops, 0.4, "drops");

        List<String> guaranteed = new ArrayList<>();
        if (dim != null) {
            for (String a : DimensionContext.guaranteed(dim)) if (available.contains(a)) guaranteed.add(a);
            Map<String, Double> name = name(id);
            Map<String, Double> pool = new HashMap<>();
            for (String a : DimensionContext.pool(dim)) {
                double rel = Math.max(name.getOrDefault(a, 0.0), Math.max(traits.getOrDefault(a, 0.0), Math.min(1, 1.5 * drops.getOrDefault(a, 0.0))));
                if (rel >= POOL_RELEVANCE) pool.put(a, rel);
            }
            contribute(score, source, best, pool, wPlace, "dimension");
        }

        int min, max;
        if (boss) { min = 6; max = 7; }
        else if (f >= 0.5 || dim != null) { min = 4; max = 5; }
        else { min = 3; max = 4; }

        List<Candidate> chosen = new ArrayList<>();
        int count = base.size();
        for (String a : guaranteed) {
            if (base.containsKey(a)) continue;
            chosen.add(new Candidate(a, 1.0, "dimension", true));
            count++;
        }
        List<Candidate> ranked = new ArrayList<>();
        score.forEach((a, s) -> {
            if (!base.containsKey(a) && !guaranteed.contains(a) && available.contains(a)) ranked.add(new Candidate(a, s, source.get(a), false));
        });
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::aspect));
        Set<Candidate> taken = new HashSet<>();
        for (Candidate c : ranked) {
            if (count >= max) break;
            if (c.score() >= STRONG) { chosen.add(c); taken.add(c); count++; }
        }
        for (Candidate c : ranked) {
            if (count >= min) break;
            if (!taken.contains(c) && c.score() >= WEAK) { chosen.add(c); count++; }
        }
        if (chosen.isEmpty()) return null;

        // amounts: sized like minor aspects of the original layer, scaled by rarity (already in the guess) and support
        double unit = total;
        Map<Candidate, Integer> amounts = new LinkedHashMap<>();
        for (Candidate c : chosen) {
            double factor = c.guaranteed() ? 0.6 + 0.4 * f : 0.6 + 0.4 * Math.min(1, c.score());
            amounts.put(c, (int) Math.max(1, Math.round(Knowledge.minorShare(c.aspect()) * unit * factor)));
        }

        // balance pass: the additions together stay a minority of the mob
        double cap = (boss ? 0.4 : 0.3 + 0.15 * f) * unit;
        List<Candidate> byWeakest = new ArrayList<>(amounts.keySet());
        byWeakest.sort(Comparator.comparingDouble(Candidate::score).thenComparing(Candidate::aspect));
        for (Candidate c : byWeakest) {
            if (sum(amounts) <= cap) break;
            if (!c.guaranteed()) amounts.remove(c);
        }
        int added = sum(amounts);
        if (added > cap && added > 0) {
            double s = cap / added;
            amounts.replaceAll((c, x) -> (int) Math.max(1, Math.round(x * s)));
        }
        if (amounts.isEmpty()) return null;

        LinkedHashMap<String, Integer> out = new LinkedHashMap<>(base);
        Map<String, StringJoiner> note = new LinkedHashMap<>();
        amounts.forEach((c, x) -> {
            out.merge(c.aspect(), x, Integer::sum);
            note.computeIfAbsent(c.source(), k -> new StringJoiner(" ")).add("+" + Additions.shortName(c.aspect()) + " " + x);
        });
        StringJoiner line = new StringJoiner("; ", String.format(Locale.ROOT, "fantasy %.2f, %s x%.1f%s | ",
                f, rarityLabel(r), r, dim == null ? "" : ", native to " + dim), "");
        note.forEach((src, j) -> line.add(src + ": " + j));
        Additions.entity(id, line.toString());
        return out;
    }

    private static void contribute(Map<String, Double> score, Map<String, String> source, Map<String, Double> best,
                                   Map<String, Double> values, double weight, String name) {
        values.forEach((a, x) -> {
            double c = weight * Math.min(1, x);
            score.merge(a, c, Double::sum);
            if (c > best.getOrDefault(a, 0.0)) { best.put(a, c); source.put(a, name); }
        });
    }

    private static int sum(Map<?, Integer> m) {
        int s = 0;
        for (int x : m.values()) s += x;
        return s;
    }
}

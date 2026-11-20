package dev.movingcastle.aspectinterpreter;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.*;

/**
 * The "where does this come from" layer. For every dimension it reads, from the running server:
 * <ul>
 *   <li>which creatures its biomes and structures spawn,</li>
 *   <li>which blocks its terrain and world features place,</li>
 *   <li>which items those creatures drop, and which items are named after them (buckets, eggs).</li>
 * </ul>
 * It then learns each dimension's <em>signature</em>: the aspects over-represented among objects already
 * assigned there (the End comes out alienis then tenebrae; the Nether ignis), blended with a small prior from
 * the lexicon. Interpreted objects that belong to a non-baseline dimension receive that signature as evidence,
 * and objects native to one get at least one point of its leading aspect.
 */
final class DimensionContext {
    private DimensionContext() {}

    static final double NATIVE_SHARE = 0.6, EVIDENCE_SHARE = 0.3;
    /** Half strength: in existing assignments a dimension is a secondary flavour (shulker shell: praemunio 10, alienis 5). */
    static final double DIMENSION_WEIGHT = 0.5;
    private static final double LIFT_MIN = 3.0, SHARE_MIN = 0.05;

    private static volatile Map<String, Map<String, Double>> itemDims = Map.of();
    private static volatile Map<String, Map<String, Double>> entityDims = Map.of();
    private static volatile Map<String, LinkedHashMap<String, Double>> signatures = Map.of();
    /** The aspect a native object is guaranteed: the defining prior aspect if the pack has it, else the learned lead. */
    private static volatile Map<String, String> leads = Map.of();
    private static volatile boolean computed;

    static boolean computed() { return computed; }

    // ---------------------------------------------------------------- queries

    static Map<String, Double> dims(String id, boolean entity) {
        return (entity ? entityDims : itemDims).getOrDefault(id, Map.of());
    }

    /** Evidence sources for every non-baseline dimension holding at least 30% of this object's association. */
    static List<Model.Source> sources(String id, boolean entity) {
        if (!Config.get().dimensionContext) return List.of();
        List<Model.Source> out = new ArrayList<>();
        dims(id, entity).forEach((dim, share) -> {
            Map<String, Double> sig = signatures.get(dim);
            if (sig != null && !sig.isEmpty() && share >= EVIDENCE_SHARE)
                out.add(new Model.Source("dimension", sig, DIMENSION_WEIGHT * share));
        });
        return out;
    }

    /** Guaranteed aspects per dimension, in order: alienis (every non-baseline dimension), then flavour candidates. */
    private static volatile Map<String, List<String>> guarantees = Map.of();

    /**
     * What a native object must carry: alienis, plus the dimension's top available flavour. Missing ones are added, sized like minor aspects in the original layer. Applies to
     * hand-written, recipe-derived and interpreted objects alike; nothing existing is ever replaced.
     */
    static Map<String, Integer> additions(String id, boolean entity, Set<String> present, int total) {
        if (!Config.get().dimensionContext || total <= 0) return Map.of();
        Map<String, Integer> add = new LinkedHashMap<>();
        for (Map.Entry<String, Double> d : dims(id, entity).entrySet()) {
            if (d.getValue() < NATIVE_SHARE) continue;
            List<String> g = guarantees.get(d.getKey());
            if (g == null || g.isEmpty()) continue;
            String core = g.get(0);
            if (!present.contains(core)) add.put(core, size(core, total));
            // the dimension's top flavour (infernum for the Nether when Thaumic Additions is installed, else ignis)
            if (g.size() > 1 && !present.contains(g.get(1))) add.put(g.get(1), size(g.get(1), total));
            break;
        }
        return add;
    }

    private static int size(String aspect, int total) {
        return (int) Math.max(1, Math.round(Knowledge.minorShare(aspect) * total));
    }

    /** For interpreted objects: merge the native additions into a guess. */
    static void ensureNative(LinkedHashMap<String, Integer> aspects, String id, boolean entity) {
        int total = 0;
        for (int x : aspects.values()) total += x;
        additions(id, entity, aspects.keySet(), total).forEach((a, x) -> aspects.merge(a, x, Integer::sum));
    }

    static String describe(String id, boolean entity) {
        StringJoiner j = new StringJoiner(" ");
        dims(id, entity).forEach((d, s) -> {
            if (s >= EVIDENCE_SHARE && signatures.containsKey(d)) j.add(String.format(Locale.ROOT, "%s %.0f%%", d.replace("minecraft:", ""), s * 100));
        });
        return j.length() == 0 ? "" : " [" + j + "]";
    }

    // ---------------------------------------------------------------- computation

    /** Runs once per server start / datapack reload, before the index build. Typically well under a second. */
    static synchronized void compute(MinecraftServer server) {
        Config cfg = Config.get();
        if (!cfg.dimensionContext || server == null) { computed = true; return; }
        long t0 = System.nanoTime();
        RegistryAccess access = server.registryAccess();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Registry<ConfiguredFeature<?, ?>> configured = access.registryOrThrow(Registries.CONFIGURED_FEATURE);
        Registry<PlacedFeature> placed = access.registryOrThrow(Registries.PLACED_FEATURE);

        Map<String, Map<String, Double>> ents = new HashMap<>();
        Map<String, Map<String, Double>> items = new HashMap<>();
        Map<Holder<Biome>, Set<String>> biomeDims = new HashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            ChunkGenerator gen = level.getChunkSource().getGenerator();
            Set<Holder<Biome>> biomes = gen.getBiomeSource().possibleBiomes();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<String> blocks = new HashSet<>();
            for (Holder<Biome> b : biomes) {
                biomeDims.computeIfAbsent(b, k -> new HashSet<>()).add(dim);
                MobSpawnSettings mobs = b.value().getMobSettings();
                for (MobCategory c : MobCategory.values())
                    for (MobSpawnSettings.SpawnerData sd : mobs.getMobs(c).unwrap())
                        bump(ents, EntityType.getKey(sd.type).toString(), dim, 1);
                for (HolderSet<PlacedFeature> step : b.value().getGenerationSettings().features())
                    for (Holder<PlacedFeature> pf : step)
                        collectBlocks(pf.value(), ops, configured, placed, seen, blocks);
            }
            if (gen instanceof NoiseBasedChunkGenerator noise) {
                try {
                    NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, noise.generatorSettings().value())
                            .result().ifPresent(j -> scanBlockIds(j, blocks, null, null, ops, seen));
                } catch (Throwable ignored) {}
            }
            for (String block : blocks) {
                Item it = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(block)).asItem();
                if (it != Items.AIR) bump(items, BuiltInRegistries.ITEM.getKey(it).toString(), dim, 1);
            }
        }

        // structure spawn overrides (pillager outposts, ocean monuments, modded structures...)
        for (Structure s : access.registryOrThrow(Registries.STRUCTURE)) {
            Set<String> sdims = new HashSet<>();
            for (Holder<Biome> b : s.biomes()) sdims.addAll(biomeDims.getOrDefault(b, Set.of()));
            if (sdims.isEmpty()) continue;
            for (StructureSpawnOverride o : s.spawnOverrides().values())
                for (MobSpawnSettings.SpawnerData sd : o.spawns().unwrap())
                    for (String d : sdims) bump(ents, EntityType.getKey(sd.type).toString(), d, 2);
        }

        Map<String, Map<String, Double>> entShares = normalise(ents);

        // what those creatures drop, and items named after them
        for (Map.Entry<String, Map<String, Double>> e : entShares.entrySet()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(e.getKey()));
            try {
                LootTable table = server.reloadableRegistries().getLootTable(type.getDefaultLootTable());
                Set<String> dropped = new HashSet<>();
                LootTable.DIRECT_CODEC.encodeStart(ops, table).result().ifPresent(j -> scanItemIds(j, dropped));
                for (String it : dropped) e.getValue().forEach((d, s) -> bump(items, it, d, s));
            } catch (Throwable ignored) {}
        }
        Map<String, String> entityPaths = new HashMap<>();
        for (String id : entShares.keySet()) {
            String path = id.substring(id.indexOf(':') + 1);
            if (path.length() >= 4) entityPaths.put(path, id);
        }
        for (Item it : BuiltInRegistries.ITEM) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it);
            String path = "_" + rl.getPath() + "_";
            for (Map.Entry<String, String> ep : entityPaths.entrySet()) {
                if (path.contains("_" + ep.getKey() + "_"))
                    entShares.get(ep.getValue()).forEach((d, s) -> bump(items, rl.toString(), d, 2 * s));
            }
        }

        itemDims = normalise(items);
        entityDims = entShares;
        Map<String, String> newLeads = new HashMap<>();
        signatures = learnSignatures(new TreeSet<>(itemDims.values().stream().flatMap(m -> m.keySet().stream()).toList()), access, newLeads);
        leads = newLeads;
        computed = true;
        StringJoiner sj = new StringJoiner("; ");
        signatures.forEach((d, s) -> sj.add(d + " -> " + String.join("/", s.keySet().stream().map(a -> a.replace("thaumaturge:", "")).toList())
                + " (guaranteed: " + leads.getOrDefault(d, "-").replace("thaumaturge:", "") + ")"));
        AspectInterpreter.LOG.info("Aspect Interpreter dimension context: {} items and {} entities placed in {} ms. Signatures: {}",
                itemDims.size(), entityDims.size(), (System.nanoTime() - t0) / 1_000_000, sj.length() == 0 ? "none" : sj);
    }

    /**
     * Signature per non-baseline dimension: alienis first (every other world is alien), then up to two flavours.
     * Flavours come from the lexicon's prior for that dimension when the pack has those aspects; otherwise from
     * the aspects most over-represented among its already-assigned natives; otherwise from its name.
     */
    private static Map<String, LinkedHashMap<String, Double>> learnSignatures(Set<String> dims, RegistryAccess access, Map<String, String> leadsOut) {
        Config cfg = Config.get();
        Model items = Knowledge.items(), ents = Knowledge.entities();
        Set<String> available = new HashSet<>();
        access.registry(InterpreterContributor.ASPECT_REGISTRY).ifPresent(r -> r.keySet().forEach(k -> available.add(k.toString())));
        List<String> core = new ArrayList<>(Knowledge.LEXICON.dimensionPrior("_core").keySet());
        core.removeIf(a -> !available.contains(a));

        Map<String, Double> global = new HashMap<>();
        int n = 0;
        for (String id : items.ids()) { addProfile(global, items.known(id), 1); n++; }
        int fn = n;
        global.replaceAll((a, x) -> x / Math.max(1, fn));

        Map<String, LinkedHashMap<String, Double>> out = new HashMap<>();
        Map<String, List<String>> guar = new HashMap<>();
        for (String dim : dims) {
            if (cfg.baselineDimensions.contains(dim)) continue;
            // flavours: config override, else lexicon prior, else learned, else name
            List<Map.Entry<String, Double>> flav = new ArrayList<>();
            Map<String, Double> override = cfg.dimensionAspects.get(dim);
            Map<String, Double> prior = override != null ? override : Knowledge.LEXICON.dimensionPrior(dim);
            prior.forEach((a, w) -> { if (available.contains(a) && !core.contains(a)) flav.add(Map.entry(a, w)); });
            if (flav.isEmpty()) learned(dim, items, ents, global).forEach((a, w) -> { if (available.contains(a) && !core.contains(a)) flav.add(Map.entry(a, w)); });
            if (flav.isEmpty()) prior(dim).forEach((a, w) -> { if (available.contains(a) && !core.contains(a)) flav.add(Map.entry(a, w)); });
            flav.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            LinkedHashMap<String, Double> sig = new LinkedHashMap<>();
            for (String c : core) sig.put(c, 1.0);
            for (int i = 0; i < Math.min(2, flav.size()); i++) sig.put(flav.get(i).getKey(), flav.get(i).getValue());
            if (sig.isEmpty()) continue;
            out.put(dim, sig);
            List<String> g = new ArrayList<>(core);
            for (Map.Entry<String, Double> f : flav) if (f.getValue() >= 0.8 || f == flav.get(0)) g.add(f.getKey());
            guar.put(dim, g);
            leadsOut.put(dim, String.join("+", g.subList(0, Math.min(g.size(), core.size() + 1))));
        }
        guarantees = guar;
        return out;
    }

    /** Aspects over-represented among a dimension's assigned natives (family-weighted, lift >= 3x, share >= 5%). */
    private static Map<String, Double> learned(String dim, Model items, Model ents, Map<String, Double> global) {
        Map<List<String>, Integer> fams = new HashMap<>();
        List<Map.Entry<String, Map<String, Double>>> natives = new ArrayList<>();
        collectNatives(itemDims, items, dim, natives);
        collectNatives(entityDims, ents, dim, natives);
        for (Map.Entry<String, Map<String, Double>> e : natives) fams.merge(family(e.getKey()), 1, Integer::sum);
        Map<String, Double> mean = new HashMap<>();
        double W = 0;
        for (Map.Entry<String, Map<String, Double>> e : natives) {
            double w = 1.0 / fams.get(family(e.getKey()));
            addProfile(mean, e.getValue(), w);
            W += w;
        }
        Map<String, Double> learned = new HashMap<>();
        if (W > 0) for (Map.Entry<String, Double> e : mean.entrySet()) {
            double share = e.getValue() / W, base = global.getOrDefault(e.getKey(), 1e-4);
            double lift = share / Math.max(base, 1e-4);
            if (lift >= LIFT_MIN && share >= SHARE_MIN) learned.put(e.getKey(), share * Math.log(lift));
        }
        return Model.normalise(learned);
    }

    private static Map<String, Double> prior(String dim) {
        Map<String, Double> p = new HashMap<>(Knowledge.LEXICON.dimensionPrior(dim));
        if (p.isEmpty())
            for (Lexicon.Match m : Knowledge.LEXICON.matches(Tokens.of(dim))) m.weights().forEach((a, x) -> p.merge(a, x, Double::sum));
        return p;
    }

    private static void collectNatives(Map<String, Map<String, Double>> dimsOf, Model model, String dim, List<Map.Entry<String, Map<String, Double>>> into) {
        dimsOf.forEach((id, shares) -> {
            if (shares.getOrDefault(dim, 0.0) >= NATIVE_SHARE && model.knows(id)) into.add(Map.entry(id, model.known(id)));
        });
    }

    private static List<String> family(String id) {
        List<String> f = new ArrayList<>();
        for (String t : Tokens.of(id)) if (!Knowledge.LEXICON.neutral.contains(t)) f.add(t);
        Collections.sort(f);
        return f.isEmpty() ? List.of(id) : f;
    }

    private static void addProfile(Map<String, Double> into, Map<String, Double> v, double w) {
        double t = Model.total(v);
        if (t <= 0) return;
        v.forEach((a, x) -> into.merge(a, w * x / t, Double::sum));
    }

    private static void collectBlocks(PlacedFeature pf, RegistryOps<JsonElement> ops, Registry<ConfiguredFeature<?, ?>> configured,
                                      Registry<PlacedFeature> placed, Set<Object> seen, Set<String> blocks) {
        if (!seen.add(pf)) return;
        collectConfigured(pf.feature().value(), ops, configured, placed, seen, blocks);
    }

    private static void collectConfigured(ConfiguredFeature<?, ?> cf, RegistryOps<JsonElement> ops, Registry<ConfiguredFeature<?, ?>> configured,
                                          Registry<PlacedFeature> placed, Set<Object> seen, Set<String> blocks) {
        if (!seen.add(cf)) return;
        try {
            ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, cf).result()
                    .ifPresent(j -> scanBlockIds(j, blocks, configured, placed, ops, seen));
        } catch (Throwable ignored) {
            // a few modded features have codecs that cannot encode; skip them
        }
    }

    /** Walks encoded worldgen JSON: block ids are recorded, references to other features are followed. */
    private static void scanBlockIds(JsonElement j, Set<String> blocks, Registry<ConfiguredFeature<?, ?>> configured,
                                     Registry<PlacedFeature> placed, RegistryOps<JsonElement> ops, Set<Object> seen) {
        if (j == null || j.isJsonNull()) return;
        if (j.isJsonArray()) { for (JsonElement e : j.getAsJsonArray()) scanBlockIds(e, blocks, configured, placed, ops, seen); return; }
        if (j.isJsonObject()) { for (Map.Entry<String, JsonElement> e : j.getAsJsonObject().entrySet()) scanBlockIds(e.getValue(), blocks, configured, placed, ops, seen); return; }
        if (!j.getAsJsonPrimitive().isString()) return;
        ResourceLocation rl = ResourceLocation.tryParse(j.getAsString());
        if (rl == null) return;
        if (BuiltInRegistries.BLOCK.containsKey(rl)) {
            Block b = BuiltInRegistries.BLOCK.get(rl);
            if (!b.defaultBlockState().isAir()) blocks.add(rl.toString());
        } else if (configured != null && configured.containsKey(rl)) {
            collectConfigured(configured.get(rl), ops, configured, placed, seen, blocks);
        } else if (placed != null && placed.containsKey(rl)) {
            collectBlocks(placed.get(rl), ops, configured, placed, seen, blocks);
        }
    }

    private static void scanItemIds(JsonElement j, Set<String> items) {
        if (j == null || j.isJsonNull()) return;
        if (j.isJsonArray()) { for (JsonElement e : j.getAsJsonArray()) scanItemIds(e, items); return; }
        if (j.isJsonObject()) { for (Map.Entry<String, JsonElement> e : j.getAsJsonObject().entrySet()) scanItemIds(e.getValue(), items); return; }
        if (!j.getAsJsonPrimitive().isString()) return;
        ResourceLocation rl = ResourceLocation.tryParse(j.getAsString());
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl) && BuiltInRegistries.ITEM.get(rl) != Items.AIR) items.add(rl.toString());
    }

    private static void bump(Map<String, Map<String, Double>> m, String id, String dim, double w) {
        m.computeIfAbsent(id, k -> new HashMap<>()).merge(dim, w, Double::sum);
    }

    private static Map<String, Map<String, Double>> normalise(Map<String, Map<String, Double>> m) {
        Map<String, Map<String, Double>> out = new HashMap<>();
        m.forEach((id, d) -> out.put(id, Model.normalise(d)));
        return out;
    }
}

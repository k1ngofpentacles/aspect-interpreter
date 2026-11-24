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
import net.minecraft.tags.TagKey;
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
 *   <li>which creatures its biomes and structures spawn, in which biomes and how often,</li>
 *   <li>which blocks its terrain and world features place (used only to learn what an unlisted dimension is like),</li>
 *   <li>which items creatures drop, and which items are named after them (buckets, eggs).</li>
 * </ul>
 * Each non-baseline dimension gets a <em>signature</em>: alienis (unless it is an underworld, like the Nether),
 * then its flavours, from the lexicon or learned from its natives. Dimension flavour belongs to mobs
 * ({@link MobEnrichment}); among items, only filled-in ones that come from such a mob receive it. Blocks never do.
 */
final class DimensionContext {
    private DimensionContext() {}

    static final double NATIVE_SHARE = 0.6, EVIDENCE_SHARE = 0.3;
    /** Half strength: in existing assignments a dimension is a secondary flavour (shulker shell: praemunio 10, alienis 5). */
    static final double DIMENSION_WEIGHT = 0.5;
    private static final double LIFT_MIN = 3.0, SHARE_MIN = 0.05;

    /** Items that come from mobs (drops, items named after them), by the mobs' dimensions. */
    private static volatile Map<String, Map<String, Double>> itemDims = Map.of();
    /** Items placed by terrain and features; only for learning unlisted dimensions' signatures. */
    private static volatile Map<String, Map<String, Double>> blockDims = Map.of();
    /** Spawn biomes and weights per entity, structure-only flags, and drops per entity. */
    private static volatile Map<String, Map<Holder<Biome>, Double>> habitats = Map.of();
    private static volatile Set<String> structureOnly = Set.of();
    private static volatile Map<String, Set<String>> drops = Map.of();
    private static volatile Set<String> underworlds = Set.of();
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

    /** Per dimension: the aspects every native mob carries (alienis unless an underworld, then the lead flavour). */
    private static volatile Map<String, List<String>> guarantees = Map.of();
    /** Per dimension: further flavours a native mob may take when they suit it (see {@link MobEnrichment}). */
    private static volatile Map<String, List<String>> pools = Map.of();

    /** The non-baseline dimension this entity is native to (at least 60% of its spawns), or null. */
    static String nativeDim(String entityId) {
        if (!Config.get().dimensionContext) return null;
        for (Map.Entry<String, Double> d : dims(entityId, true).entrySet())
            if (d.getValue() >= NATIVE_SHARE && guarantees.containsKey(d.getKey())) return d.getKey();
        return null;
    }

    static List<String> guaranteed(String dim) { return guarantees.getOrDefault(dim, List.of()); }

    static List<String> pool(String dim) { return pools.getOrDefault(dim, List.of()); }

    static boolean underworld(String dim) { return underworlds.contains(dim); }

    static Map<Holder<Biome>, Double> habitat(String entityId) { return habitats.getOrDefault(entityId, Map.of()); }

    static boolean structureOnly(String entityId) { return structureOnly.contains(entityId); }

    static Set<String> drops(String entityId) { return drops.getOrDefault(entityId, Set.of()); }

    /**
     * For a filled-in item that comes from a mob native to another dimension (cosmic cod, its bucket): the
     * dimension's guaranteed aspects, sized like minor aspects in the original layer. Nothing for blocks.
     */
    static Map<String, Integer> dropAdditions(String itemId, Set<String> present, int total) {
        if (!Config.get().dimensionContext || total <= 0) return Map.of();
        Map<String, Integer> add = new LinkedHashMap<>();
        for (Map.Entry<String, Double> d : dims(itemId, false).entrySet()) {
            if (d.getValue() < NATIVE_SHARE) continue;
            List<String> g = guarantees.get(d.getKey());
            if (g == null || g.isEmpty()) continue;
            for (String a : g) if (!present.contains(a)) add.put(a, size(a, total));
            break;
        }
        return add;
    }

    private static int size(String aspect, int total) {
        return (int) Math.max(1, Math.round(Knowledge.minorShare(aspect) * total));
    }

    static String describe(String id, boolean entity) {
        StringJoiner j = new StringJoiner(" ");
        dims(id, entity).forEach((d, s) -> {
            if (s >= EVIDENCE_SHARE && signatures.containsKey(d)) j.add(String.format(Locale.ROOT, "%s %.0f%%", d.replace("minecraft:", ""), s * 100));
        });
        return j.length() == 0 ? "" : " [" + j + "]";
    }

    // ---------------------------------------------------------------- computation

    private static final TagKey<Biome> IS_OVERWORLD = TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("is_overworld"));
    private static final TagKey<Biome> C_IS_OVERWORLD = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", "is_overworld"));

    /** A baseline biome: one the baseline dimension generates, or one tagged as an overworld biome. */
    private static boolean isBaselineBiome(Holder<Biome> b, Set<Holder<Biome>> baselineBiomes) {
        return baselineBiomes.contains(b) || b.is(IS_OVERWORLD) || b.is(C_IS_OVERWORLD);
    }

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
        Map<String, Map<String, Double>> blockItems = new HashMap<>();
        Map<String, Map<Holder<Biome>, Double>> habs = new HashMap<>();
        Set<String> biomeSpawners = new HashSet<>(), structureSpawners = new HashSet<>();
        Map<Holder<Biome>, Set<String>> biomeDims = new HashMap<>();

        // A biome borrowed from the baseline belongs to the baseline wherever it appears. Thaumaturge Tinker's
        // Bedrock dimension is one flat minecraft:beach, and that must not make every beach mob an alien native.
        Set<Holder<Biome>> baselineBiomes = new HashSet<>();
        String baseline = cfg.baselineDimensions.isEmpty() ? "minecraft:overworld" : cfg.baselineDimensions.get(0);
        for (ServerLevel level : server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            if (cfg.baselineDimensions.contains(dim)) {
                baselineBiomes.addAll(level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes());
                baseline = dim;
            }
        }
        int borrowed = 0;

        for (ServerLevel level : server.getAllLevels()) {
            String levelDim = level.dimension().location().toString();
            ChunkGenerator gen = level.getChunkSource().getGenerator();
            Set<Holder<Biome>> biomes = gen.getBiomeSource().possibleBiomes();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<String> blocks = new HashSet<>();
            for (Holder<Biome> b : biomes) {
                boolean lent = !cfg.baselineDimensions.contains(levelDim) && isBaselineBiome(b, baselineBiomes);
                if (lent) borrowed++;
                String dim = lent ? baseline : levelDim;
                biomeDims.computeIfAbsent(b, k -> new HashSet<>()).add(dim);
                MobSpawnSettings mobs = b.value().getMobSettings();
                for (MobCategory c : MobCategory.values())
                    for (MobSpawnSettings.SpawnerData sd : mobs.getMobs(c).unwrap()) {
                        String eid = EntityType.getKey(sd.type).toString();
                        bump(ents, eid, dim, 1);
                        habs.computeIfAbsent(eid, k -> new HashMap<>()).merge(b, (double) Math.max(1, sd.getWeight().asInt()), Math::max);
                        biomeSpawners.add(eid);
                    }
                if (lent) continue; // its features are the baseline's, not this dimension's
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
                if (it != Items.AIR) bump(blockItems, BuiltInRegistries.ITEM.getKey(it).toString(), levelDim, 1);
            }
        }

        // structure spawn overrides (pillager outposts, ocean monuments, modded structures...)
        for (Structure s : access.registryOrThrow(Registries.STRUCTURE)) {
            Set<String> sdims = new HashSet<>();
            for (Holder<Biome> b : s.biomes()) sdims.addAll(biomeDims.getOrDefault(b, Set.of()));
            if (sdims.isEmpty()) continue;
            for (StructureSpawnOverride o : s.spawnOverrides().values())
                for (MobSpawnSettings.SpawnerData sd : o.spawns().unwrap()) {
                    String eid = EntityType.getKey(sd.type).toString();
                    structureSpawners.add(eid);
                    for (String d : sdims) bump(ents, eid, d, 2);
                }
        }

        Map<String, Map<String, Double>> entShares = normalise(ents);

        // what every mob drops; for natives of a dimension, those drops come from there too
        Map<String, Set<String>> dropMap = new HashMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) continue;
            String eid = EntityType.getKey(type).toString();
            try {
                LootTable table = server.reloadableRegistries().getLootTable(type.getDefaultLootTable());
                Set<String> dropped = new HashSet<>();
                LootTable.DIRECT_CODEC.encodeStart(ops, table).result().ifPresent(j -> scanItemIds(j, dropped));
                if (dropped.isEmpty()) continue;
                dropMap.put(eid, dropped);
                Map<String, Double> shares = entShares.get(eid);
                if (shares != null) for (String it : dropped) shares.forEach((d, s) -> bump(items, it, d, s));
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
        blockDims = normalise(blockItems);
        entityDims = entShares;
        habitats = habs;
        drops = dropMap;
        Set<String> sOnly = new HashSet<>(structureSpawners);
        sOnly.removeAll(biomeSpawners);
        structureOnly = sOnly;
        underworlds = new HashSet<>(Knowledge.LEXICON.underworlds);
        Map<String, String> newLeads = new HashMap<>();
        Set<String> allDims = new TreeSet<>();
        for (ServerLevel level : server.getAllLevels()) allDims.add(level.dimension().location().toString());
        signatures = learnSignatures(allDims, access, newLeads);
        leads = newLeads;
        computed = true;
        StringJoiner sj = new StringJoiner("; ");
        signatures.forEach((d, s) -> sj.add(d + " -> " + String.join("/", s.keySet().stream().map(a -> a.replace("thaumaturge:", "")).toList())
                + " (guaranteed: " + leads.getOrDefault(d, "-").replace("thaumaturge:", "") + ")"));
        AspectInterpreter.LOG.info("Aspect Interpreter dimension context: {} mob-derived items and {} entities placed ({} borrowed baseline biomes credited to the baseline) in {} ms. Signatures: {}",
                itemDims.size(), entityDims.size(), borrowed, (System.nanoTime() - t0) / 1_000_000, sj.length() == 0 ? "none" : sj);
    }

    /**
     * Signature per non-baseline dimension: alienis first (every other world is alien, except underworlds such as
     * the Nether, which is this world's below rather than an outer one), then up to two flavours.
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
        Map<String, List<String>> poolOut = new HashMap<>();
        for (String dim : dims) {
            if (cfg.baselineDimensions.contains(dim)) continue;
            List<String> dimCore = underworlds.contains(dim) ? List.of() : core;
            // flavours: config override, else lexicon prior, else learned, else name
            List<Map.Entry<String, Double>> flav = new ArrayList<>();
            Map<String, Double> override = cfg.dimensionAspects.get(dim);
            Map<String, Double> prior = override != null ? override : Knowledge.LEXICON.dimensionPrior(dim);
            prior.forEach((a, w) -> { if (available.contains(a) && !dimCore.contains(a)) flav.add(Map.entry(a, w)); });
            if (flav.isEmpty()) learned(dim, items, ents, global).forEach((a, w) -> { if (available.contains(a) && !dimCore.contains(a)) flav.add(Map.entry(a, w)); });
            if (flav.isEmpty()) prior(dim).forEach((a, w) -> { if (available.contains(a) && !dimCore.contains(a)) flav.add(Map.entry(a, w)); });
            flav.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            LinkedHashMap<String, Double> sig = new LinkedHashMap<>();
            for (String c : dimCore) sig.put(c, 1.0);
            for (int i = 0; i < Math.min(2, flav.size()); i++) sig.put(flav.get(i).getKey(), flav.get(i).getValue());
            if (sig.isEmpty()) continue;
            out.put(dim, sig);
            // guaranteed: the core (if any) and the lead flavour; the rest of the flavours form the pool
            List<String> g = new ArrayList<>(dimCore);
            if (!flav.isEmpty()) g.add(flav.get(0).getKey());
            guar.put(dim, g);
            List<String> p = new ArrayList<>();
            for (String a : Knowledge.LEXICON.dimensionPool(dim)) if (available.contains(a) && !g.contains(a)) p.add(a);
            if (p.isEmpty()) for (int i = 1; i < flav.size(); i++) p.add(flav.get(i).getKey());
            poolOut.put(dim, p);
            leadsOut.put(dim, String.join("+", g) + (p.isEmpty() ? "" : ", may add " + String.join("/", p.stream().map(Additions::shortName).toList())));
        }
        guarantees = guar;
        pools = poolOut;
        return out;
    }

    /** Aspects over-represented among a dimension's assigned natives (family-weighted, lift >= 3x, share >= 5%). */
    private static Map<String, Double> learned(String dim, Model items, Model ents, Map<String, Double> global) {
        Map<List<String>, Integer> fams = new HashMap<>();
        List<Map.Entry<String, Map<String, Double>>> natives = new ArrayList<>();
        collectNatives(itemDims, items, dim, natives);
        collectNatives(blockDims, items, dim, natives);
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

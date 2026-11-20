package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.*;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.*;

/**
 * Registered after Thaumaturge's own crucible/infusion/crafting/cooking contributors, so it is
 * only asked about items those could not resolve. Every leaf it fills in lets Thaumaturge's own
 * recipe logic finish the items crafted from it.
 *
 * Cost model: everything here runs once, inside Thaumaturge's index build, which Thaumaturge then
 * caches to disk. Name-model predictions are memoised per build because Thaumaturge resolves the
 * whole registry twice (before and after split-conservation).
 */
final class InterpreterContributor implements IAspectRecipeContributor {
    private static final int MAX_RECIPES_PER_ITEM = 4;
    private static final int MAX_OPTIONS_PER_INGREDIENT = 2;

    private final Map<Item, List<Recipe<?>>> byOutput = new HashMap<>();
    private final Map<Item, Model.Prediction> memo = new HashMap<>();

    private static final Set<String> FILLED = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean building;
    private static long buildNanos;
    private static int asked, filled;

    @Override
    public void beginBuild(RecipeManager recipes, HolderLookup.Provider registries) {
        long t0 = System.nanoTime();
        Knowledge.rebuildIfStale();
        CraftComplexity.prepare(recipes, registries);
        if (!DimensionContext.computed()) DimensionContext.compute(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer());
        AssignmentLog.clearItems();
        Additions.clear();
        byOutput.clear();
        memo.clear();
        asked = filled = 0;
        FILLED.clear();
        buildNanos = 0;
        building = true;
        if (!Config.get().items) return;
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                ItemStack out = r.getResultItem(registries);
                if (out == null || out.isEmpty()) continue;
                List<Recipe<?>> l = byOutput.computeIfAbsent(out.getItem(), k -> new ArrayList<>(2));
                if (l.size() < MAX_RECIPES_PER_ITEM) l.add(r);
            } catch (Throwable ignored) {
                // some recipe types need a level for getResultItem; skip them
            }
        }
        buildNanos += System.nanoTime() - t0;
    }

    /** Called at the end of every server tick; logs and reports once after an index build. */
    static void finishBuildIfDone() {
        if (!building) return;
        building = false;
        AspectInterpreter.LOG.info("Aspect Interpreter: filled {} of {} unresolved items; additions to existing items: {}; {} ms of our own work inside Thaumaturge's index build",
                filled, asked, Additions.summary(), buildNanos / 1_000_000);
        // never learn from our own work: exclude guessed items and items we added to from future cache learning
        Set<String> ours = new HashSet<>(FILLED);
        ours.addAll(Additions.itemIds());
        InterpretedIds.save(ours);
        AspectInterpreter.recordBuild(Knowledge.learnedFromCache > 0);
        AssignmentLog.write();
    }

    @Override
    public Optional<AspectList> derive(Item item, RecipeManager recipes, HolderLookup.Provider registries, IAspectIndex index) {
        Config cfg = Config.get();
        if (!cfg.items) return Optional.empty();
        long t0 = System.nanoTime();
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        try {
            if (cfg.excluded(id)) return Optional.empty();
            if (!memo.containsKey(item)) asked++;
            Model.Prediction p = memo.get(item);
            if (p == null) {
                p = predict(item, id, registries, index);
                if (p != null) memo.put(item, p);
            }
            if (p == null || p.confidence() < cfg.minConfidence) {
                AssignmentLog.item(id, null, p == null ? 0 : p.confidence(), p == null ? "no evidence" : "below min_confidence");
                return Optional.empty();
            }
            AspectList list = build(p.aspects(), registries);
            AssignmentLog.item(id, list.isEmpty() ? null : p.aspects(), p.confidence(), p.basis() + DimensionContext.describe(id, false));
            if (list.isEmpty()) return Optional.empty();
            if (FILLED.add(id)) filled++;
            return Optional.of(list);
        } catch (Throwable t) {
            AspectInterpreter.LOG.debug("Aspect Interpreter skipped {}", id, t);
            return Optional.empty();
        } finally {
            buildNanos += System.nanoTime() - t0;
        }
    }

    private Model.Prediction predict(Item item, String id, HolderLookup.Provider registries, IAspectIndex index) {
        List<Model.Source> extra = new ArrayList<>();
        Double forcedTotal = null;

        // Recipes of types Thaumaturge doesn't read (Create processing, butchering, ...). Ingredient lookups go
        // through Thaumaturge's own memoised resolver, so each item is still resolved at most once per pass.
        for (Recipe<?> r : byOutput.getOrDefault(item, List.of())) {
            int needed = 0, found = 0;
            Map<String, Double> sum = new HashMap<>();
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) continue;
                ItemStack[] opts = ing.getItems();
                if (opts.length == 0) continue;
                needed++;
                for (int i = 0; i < Math.min(MAX_OPTIONS_PER_INGREDIENT, opts.length); i++) {
                    if (opts[i].getItem() == item) continue;
                    AspectList a = index.of(opts[i].getItem());
                    if (a != null && !a.isEmpty()) {
                        Knowledge.toMap(a).forEach((k, v) -> sum.merge(k, v, Double::sum));
                        found++;
                        break;
                    }
                }
            }
            if (needed == 0 || found == 0) continue;
            double frac = (double) found / needed;
            if (frac >= 0.5) {
                int outCount = Math.max(1, r.getResultItem(registries).getCount());
                extra.add(new Model.Source("recipe", sum, 2.0 * frac));
                forcedTotal = Math.max(1, Model.total(sum) * 0.75 / outCount / frac);
                break;
            }
        }

        extra.addAll(DimensionContext.sources(id, false));

        Model.Source material = ItemFeatures.of(item);
        if (material != null) extra.add(material);

        if (item instanceof SpawnEggItem egg) {
            EntityType<?> type = egg.getType(new ItemStack(item));
            Map<String, Double> e = EntityInterpreter.knownOrGuess(type);
            if (!e.isEmpty()) extra.add(new Model.Source("creature", e, 1.2));
            extra.add(new Model.Source("egg", Map.of("thaumaturge:victus", 1.0), 0.4));
            if (forcedTotal == null) forcedTotal = 10.0;
        }
        Model.Prediction p = Knowledge.items().predict(id, extra, 8, forcedTotal);
        if (p != null) Additions.forGuess(item, id, p.aspects());
        return p;
    }

    static final ResourceKey<net.minecraft.core.Registry<IAspect>> ASPECT_REGISTRY = TTAspects.AER.registryKey();

    /** Drops aspects that this pack doesn't define (e.g. an addon aspect learned from another pack) and rescales the rest. */
    static AspectList build(Map<String, Integer> aspects, HolderLookup.Provider registries) {
        Optional<? extends HolderLookup.RegistryLookup<IAspect>> lookup = registries.lookup(ASPECT_REGISTRY);
        if (lookup.isEmpty()) return AspectList.EMPTY;
        List<AspectInstance> out = new ArrayList<>();
        int dropped = 0, total = 0;
        for (Map.Entry<String, Integer> e : aspects.entrySet()) {
            total += e.getValue();
            ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
            Optional<? extends Holder<IAspect>> h = rl == null ? Optional.empty() : lookup.get().get(ResourceKey.create(ASPECT_REGISTRY, rl));
            if (h.isPresent()) out.add(new AspectInstance(h.get(), e.getValue()));
            else dropped += e.getValue();
        }
        if (out.isEmpty()) return AspectList.EMPTY;
        if (dropped > 0) {
            double scale = (double) total / (total - dropped);
            List<AspectInstance> scaled = new ArrayList<>();
            for (AspectInstance i : out) scaled.add(new AspectInstance(i.aspect(), (int) Math.max(1, Math.round(i.amount() * scale))));
            out = scaled;
        }
        return AspectList.ofEntries(out);
    }
}

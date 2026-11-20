package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.AspectDataMaps;
import com.leclowndu93150.thaumaturge.api.aspect.AspectInstance;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The crafting-complexity layer.
 *
 * Thaumaturge's recipe rule conserves: a crafted item gets 75% of its ingredients' aspects and never gains
 * one, so variety shrinks along recipe chains (oak stairs are herba 5, exactly like planks). Its authors
 * put workmanship in by hand instead: fabrico on worked things, ordo on bricks and storage blocks, machina
 * on mechanisms. This layer extends that intent to every recipe-derived item.
 *
 * Each crafting step beyond the first refinement (log to planks, ore to ingot) adds craftsmanship of that
 * step's kind: <b>ordo</b> when the result is a regular form (stairs, slabs, walls, bricks), <b>machina</b>
 * for machine processing, otherwise <b>fabrico</b>. Amounts grow with the number of steps, capped at 30% of
 * the item's material, and pass on to later products through Thaumaturge's own recipe rule. Items with
 * hand-written aspects are never touched.
 */
public final class CraftComplexity {
    private CraftComplexity() {}

    private record Step(String typeId, int distinctInputs, List<Item> inputs) {}

    private static volatile RecipeManager preparedFor;
    private static final Map<Item, List<Step>> RECIPES = new HashMap<>();
    private static final Map<Item, Integer> DEPTH = new HashMap<>();

    public static synchronized void prepare(RecipeManager recipes, HolderLookup.Provider registries) {
        if (preparedFor == recipes) return;
        RECIPES.clear();
        DEPTH.clear();
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                ItemStack out = r.getResultItem(registries);
                if (out == null || out.isEmpty()) continue;
                ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
                List<Item> inputs = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    ItemStack[] opts = ing.getItems();
                    if (opts.length > 0) inputs.add(opts[0].getItem());
                }
                if (inputs.isEmpty()) continue;
                RECIPES.computeIfAbsent(out.getItem(), k -> new ArrayList<>(2))
                        .add(new Step(type == null ? "" : type.toString(), new HashSet<>(inputs).size(), inputs));
            } catch (Throwable ignored) {
                // some recipe types need a level for getResultItem
            }
        }
        preparedFor = recipes;
    }

    /** Steps from raw material: 0 for hand-assigned or recipe-less items, else 1 + the deepest input of the shallowest recipe. */
    private static int depth(Item item, Set<Item> visiting) {
        Integer known = DEPTH.get(item);
        if (known != null) return known;
        List<Step> steps = RECIPES.get(item);
        if (steps == null || hasHandAspects(item) || !visiting.add(item) || visiting.size() > 64) return 0;
        int best = Integer.MAX_VALUE;
        for (Step s : steps) {
            int deepest = 0;
            for (Item in : s.inputs()) if (in != item) deepest = Math.max(deepest, depth(in, visiting));
            best = Math.min(best, deepest + 1);
        }
        visiting.remove(item);
        DEPTH.put(item, best);
        return best;
    }

    private static boolean hasHandAspects(Item item) {
        AspectList base = item.builtInRegistryHolder().getData(AspectDataMaps.BASE_ASPECTS);
        return base != null && !base.isEmpty();
    }

    /** "form", "machine", "assembly" or null (cooking, magic: nothing to add). Uses the simplest recipe for the item. */
    private static String kind(Item item, String id) {
        Lexicon lex = Knowledge.LEXICON;
        Step simplest = null;
        for (Step s : RECIPES.getOrDefault(item, List.of()))
            if (simplest == null || s.distinctInputs() < simplest.distinctInputs()) simplest = s;
        if (simplest == null || Lexicon.matchesType(lex.noCraftTypes, simplest.typeId())) return null;
        if (Lexicon.matchesType(lex.machineTypes, simplest.typeId())) return "machine";
        if (simplest.typeId().equals("minecraft:stonecutting")) return "form";
        for (String t : Tokens.of(id)) if (lex.formWords.contains(t)) return "form";
        return "assembly";
    }

    /** Craftsmanship to add, as aspect id -> amount, for an item whose recipe-derived material totals {@code materialTotal}. */
    static Map<String, Integer> craftsmanship(Item item, int materialTotal) {
        // hand-written items keep the workmanship their authors chose
        Config cfg = Config.get();
        if (!cfg.craftingComplexity || materialTotal <= 0 || preparedFor == null || hasHandAspects(item)) return Map.of();
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        if (cfg.excluded(id)) return Map.of();
        int points;
        String kind;
        synchronized (CraftComplexity.class) {
            points = depth(item, new HashSet<>()) - 1;
            kind = points > 0 ? kind(item, id) : null;
        }
        if (kind == null) return Map.of();
        String aspect = Knowledge.LEXICON.craftAspects.get(kind);
        if (aspect == null) return Map.of();
        Lexicon lex = Knowledge.LEXICON;
        int per = (int) Math.max(1, Math.round(lex.craftPerStep * materialTotal));
        int cap = (int) Math.max(1, Math.round(lex.craftMaxShare * materialTotal));
        return Map.of(aspect, Math.min(cap, points * per));
    }

}

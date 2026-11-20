package dev.movingcastle.aspectinterpreter;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Evidence read from what an item physically is: its block's material, food, tool or armour data. */
final class ItemFeatures {
    private ItemFeatures() {}

    private static final String T = "thaumaturge:";
    private static final Map<SoundType, String> MATERIAL = new IdentityHashMap<>();

    static {
        for (SoundType s : List.of(SoundType.WOOD, SoundType.BAMBOO_WOOD, SoundType.CHERRY_WOOD, SoundType.NETHER_WOOD, SoundType.STEM,
                SoundType.GRASS, SoundType.WET_GRASS, SoundType.CROP, SoundType.HARD_CROP, SoundType.VINE, SoundType.CAVE_VINES, SoundType.MOSS,
                SoundType.MOSS_CARPET, SoundType.AZALEA, SoundType.FLOWERING_AZALEA, SoundType.AZALEA_LEAVES, SoundType.CHERRY_LEAVES,
                SoundType.BIG_DRIPLEAF, SoundType.SMALL_DRIPLEAF, SoundType.SWEET_BERRY_BUSH, SoundType.BAMBOO, SoundType.BAMBOO_SAPLING,
                SoundType.CHERRY_SAPLING, SoundType.ROOTS, SoundType.HANGING_ROOTS, SoundType.MANGROVE_ROOTS, SoundType.FUNGUS,
                SoundType.NETHER_WART, SoundType.WART_BLOCK, SoundType.LILY_PAD, SoundType.PINK_PETALS, SoundType.SPORE_BLOSSOM,
                SoundType.NETHER_SPROUTS, SoundType.WEEPING_VINES, SoundType.TWISTING_VINES, SoundType.SCAFFOLDING, SoundType.LADDER,
                SoundType.CHISELED_BOOKSHELF, SoundType.GLOW_LICHEN))
            MATERIAL.put(s, T + "herba");
        for (SoundType s : List.of(SoundType.STONE, SoundType.DEEPSLATE, SoundType.DEEPSLATE_BRICKS, SoundType.DEEPSLATE_TILES,
                SoundType.POLISHED_DEEPSLATE, SoundType.TUFF, SoundType.TUFF_BRICKS, SoundType.POLISHED_TUFF, SoundType.CALCITE,
                SoundType.DRIPSTONE_BLOCK, SoundType.POINTED_DRIPSTONE, SoundType.BASALT, SoundType.NETHERRACK, SoundType.NETHER_BRICKS,
                SoundType.NETHER_ORE, SoundType.NYLIUM, SoundType.MUD, SoundType.MUD_BRICKS, SoundType.PACKED_MUD, SoundType.ROOTED_DIRT,
                SoundType.SAND, SoundType.SUSPICIOUS_SAND, SoundType.GRAVEL, SoundType.SUSPICIOUS_GRAVEL, SoundType.CORAL_BLOCK,
                SoundType.DECORATED_POT, SoundType.DECORATED_POT_CRACKED, SoundType.GILDED_BLACKSTONE))
            MATERIAL.put(s, T + "terra");
        for (SoundType s : List.of(SoundType.METAL, SoundType.ANVIL, SoundType.CHAIN, SoundType.COPPER, SoundType.COPPER_BULB,
                SoundType.COPPER_GRATE, SoundType.LANTERN, SoundType.NETHERITE_BLOCK, SoundType.ANCIENT_DEBRIS, SoundType.LODESTONE,
                SoundType.NETHER_GOLD_ORE, SoundType.HEAVY_CORE, SoundType.VAULT, SoundType.TRIAL_SPAWNER))
            MATERIAL.put(s, T + "metallum");
        for (SoundType s : List.of(SoundType.GLASS, SoundType.AMETHYST, SoundType.AMETHYST_CLUSTER, SoundType.SMALL_AMETHYST_BUD,
                SoundType.MEDIUM_AMETHYST_BUD, SoundType.LARGE_AMETHYST_BUD))
            MATERIAL.put(s, T + "vitreus");
        MATERIAL.put(SoundType.WOOL, T + "bestia");
        MATERIAL.put(SoundType.SNOW, T + "gelum");
        MATERIAL.put(SoundType.POWDER_SNOW, T + "gelum");
        MATERIAL.put(SoundType.BONE_BLOCK, T + "mortuus");
        MATERIAL.put(SoundType.SOUL_SAND, T + "spiritus");
        MATERIAL.put(SoundType.SOUL_SOIL, T + "spiritus");
        MATERIAL.put(SoundType.SLIME_BLOCK, T + "victus");
        MATERIAL.put(SoundType.HONEY_BLOCK, T + "vinculum");
        MATERIAL.put(SoundType.COBWEB, T + "vinculum");
        MATERIAL.put(SoundType.FROGSPAWN, T + "victus");
        MATERIAL.put(SoundType.FROGLIGHT, T + "lux");
        MATERIAL.put(SoundType.SHROOMLIGHT, T + "lux");
        MATERIAL.put(SoundType.CANDLE, T + "lux");
        MATERIAL.put(SoundType.SPONGE, T + "aqua");
        MATERIAL.put(SoundType.WET_SPONGE, T + "aqua");
        for (SoundType s : List.of(SoundType.SCULK, SoundType.SCULK_VEIN, SoundType.SCULK_SENSOR, SoundType.SCULK_CATALYST, SoundType.SCULK_SHRIEKER))
            MATERIAL.put(s, T + "tenebrae");
    }

    /**
     * What sort of thing an item is, for family comparisons: "block:" plus its material (or "other"), "food",
     * "gear" (tools, weapons, armour) or "item".
     */
    static String kind(Item item) {
        try {
            if (item instanceof BlockItem bi) {
                String m = MATERIAL.get(bi.getBlock().defaultBlockState().getSoundType());
                return "block:" + (m == null ? "other" : m.substring(m.indexOf(':') + 1));
            }
            DataComponentMap c = item.components();
            if (c.has(DataComponents.FOOD)) return "food";
            if (c.has(DataComponents.TOOL) || item instanceof ArmorItem) return "gear";
            ItemAttributeModifiers mods = c.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (mods != null) for (ItemAttributeModifiers.Entry e : mods.modifiers())
                if (e.attribute().equals(Attributes.ATTACK_DAMAGE) && e.modifier().amount() >= 2) return "gear";
        } catch (Throwable ignored) {}
        return "item";
    }

    /** One combined source; weight scales with how much physical information was found. */
    static Model.Source of(Item item) {
        Map<String, Double> d = new HashMap<>();
        double strength = 0;
        try {
            if (item instanceof BlockItem bi) {
                BlockState st = bi.getBlock().defaultBlockState();
                String m = MATERIAL.get(st.getSoundType());
                if (m != null) { d.merge(m, 1.0, Double::sum); strength += 0.5; }
                int light = st.getLightEmission();
                if (light > 0) { d.merge(T + "lux", light / 15.0, Double::sum); strength += 0.3; }
            }
            DataComponentMap c = item.components();
            FoodProperties food = c.get(DataComponents.FOOD);
            if (food != null) { d.merge(T + "victus", 1.0, Double::sum); strength += 0.4; }
            if (c.has(DataComponents.TOOL)) { d.merge(T + "instrumentum", 1.0, Double::sum); strength += 0.4; }
            ItemAttributeModifiers mods = c.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (mods != null) {
                for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
                    if (e.attribute().equals(Attributes.ATTACK_DAMAGE) && e.modifier().amount() >= 2) {
                        d.merge(T + "aversio", 1.0, Double::sum);
                        strength += 0.4;
                        break;
                    }
                }
            }
            if (item instanceof ArmorItem) { d.merge(T + "praemunio", 1.0, Double::sum); strength += 0.5; }
            if (c.has(DataComponents.FIRE_RESISTANT)) { d.merge(T + "ignis", 0.3, Double::sum); strength += 0.1; }
        } catch (Throwable t) {
            return null;
        }
        return d.isEmpty() ? null : new Model.Source("material", d, Math.min(0.8, strength));
    }
}

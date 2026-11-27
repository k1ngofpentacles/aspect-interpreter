package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.AspectDataMaps;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/**
 * The one definition of "hand-written": an item with a non-empty {@code base_aspects} data-map entry, or an entity type
 * with an {@code entity_aspects} entry, from any mod (Thaumaturge's own included). Hand-written objects get nothing
 * from this mod: no guess, no family, craftsmanship, function, dimension or mob-enrichment additions. Every layer asks here.
 *
 * <p>Items follow Thaumaturge's own rule (an empty entry is not a declaration; Thaumaturge derives such an item itself).
 * No empty entity entry exists in the pack; if one ever does it still counts, because an author wrote it.
 */
final class HandWritten {
    private HandWritten() {}

    static boolean item(Item item) {
        AspectList a = item.builtInRegistryHolder().getData(AspectDataMaps.BASE_ASPECTS);
        return a != null && !a.isEmpty();
    }

    static boolean entity(EntityType<?> type) {
        return type.builtInRegistryHolder().getData(AspectDataMaps.ENTITY_ASPECTS) != null;
    }
}

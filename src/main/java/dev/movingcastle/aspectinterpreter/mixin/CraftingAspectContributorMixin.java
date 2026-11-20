package dev.movingcastle.aspectinterpreter.mixin;

import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.api.aspect.IAspectIndex;
import com.leclowndu93150.thaumaturge.content.aspect.CraftingAspectContributor;
import dev.movingcastle.aspectinterpreter.Additions;
import dev.movingcastle.aspectinterpreter.CraftComplexity;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Thaumaturge's crafting rule conserves material only. After it has derived an item from a crafting
 * recipe, add what that rule cannot: the item's dimension, its function, and craftsmanship for the extra
 * steps that went into it (see {@link Additions}). Hand-written items never reach this method.
 */
@Mixin(value = CraftingAspectContributor.class, remap = false)
public abstract class CraftingAspectContributorMixin {
    @Inject(method = "derive(Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/crafting/RecipeManager;Lnet/minecraft/core/HolderLookup$Provider;Lcom/leclowndu93150/thaumaturge/api/aspect/IAspectIndex;)Ljava/util/Optional;",
            at = @At("RETURN"), cancellable = true, require = 0) // optional: if Thaumaturge changes, only this layer turns off
    private void aspectInterpreter$craftsmanship(Item item, RecipeManager recipes, HolderLookup.Provider registries, IAspectIndex index,
                                                  CallbackInfoReturnable<Optional<AspectList>> cir) {
        Optional<AspectList> result = cir.getReturnValue();
        if (result == null || result.isEmpty()) return;
        try {
            CraftComplexity.prepare(recipes, registries);
            AspectList enriched = Additions.forDerived(item, result.get(), registries);
            if (enriched != result.get()) cir.setReturnValue(Optional.of(enriched));
        } catch (Throwable ignored) {
            // never break Thaumaturge's own derivation
        }
    }
}

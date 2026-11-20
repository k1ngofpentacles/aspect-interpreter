package dev.movingcastle.aspectinterpreter.mixin;

import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.content.aspect.AspectIndexBuilder;
import dev.movingcastle.aspectinterpreter.Additions;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Thaumaturge collects every hand-written item aspect list here, before resolving recipes. Dimension additions
 * (alienis and the dimension's flavour for natives of non-Overworld dimensions) are added to these entries,
 * never replacing anything, so Thaumaturge's own recipe rule carries them on to everything crafted from them.
 */
@Mixin(value = AspectIndexBuilder.class, remap = false)
public abstract class AspectIndexBuilderMixin {
    @Inject(method = "collectBase(Lnet/minecraft/core/HolderLookup$Provider;)Ljava/util/Map;",
            at = @At("RETURN"), require = 0) // optional: if Thaumaturge changes, only this addition turns off
    private static void aspectInterpreter$dimensionAdditions(HolderLookup.Provider registries, CallbackInfoReturnable<Map<Item, AspectList>> cir) {
        Map<Item, AspectList> base = cir.getReturnValue();
        if (base == null) return;
        try {
            base.replaceAll((item, list) -> list == null || list.isEmpty() ? list : Additions.forAuthored(item, list, registries));
        } catch (Throwable ignored) {
            // never break Thaumaturge's own index build
        }
    }
}

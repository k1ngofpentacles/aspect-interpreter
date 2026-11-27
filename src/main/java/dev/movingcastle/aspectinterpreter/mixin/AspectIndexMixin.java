package dev.movingcastle.aspectinterpreter.mixin;

import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.api.aspect.IAspectIndex;
import com.leclowndu93150.thaumaturge.content.aspect.AspectIndex;
import dev.movingcastle.aspectinterpreter.Overlay;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every item-aspect lookup in Thaumaturge (tooltips, scanning, the crucible, the deconstruction table, JEI, wands, the
 * server's own checks) is {@code AspectIndexAccess.index().of(...)}, that is {@code AspectIndex.of(Item)} or
 * {@code AspectIndex.of(ItemStack)}. This serves the interpreter's overlay (see {@link Overlay}) from those two methods,
 * so Thaumaturge's index and its cache file stay purely Thaumaturge's. Required: without it the overlay would silently do
 * nothing.
 */
@Mixin(value = AspectIndex.class, remap = false)
public abstract class AspectIndexMixin {
    @Inject(method = "of(Lnet/minecraft/world/item/Item;)Lcom/leclowndu93150/thaumaturge/api/aspect/AspectList;", at = @At("RETURN"), cancellable = true)
    private void aspectInterpreter$overlayItem(Item item, CallbackInfoReturnable<AspectList> cir) {
        AspectList ours = Overlay.get(item);
        if (ours != null) cir.setReturnValue(ours);
    }

    @Inject(method = "of(Lnet/minecraft/world/item/ItemStack;)Lcom/leclowndu93150/thaumaturge/api/aspect/AspectList;", at = @At("RETURN"), cancellable = true)
    private void aspectInterpreter$overlayStack(ItemStack stack, CallbackInfoReturnable<AspectList> cir) {
        if (stack.isEmpty()) return;
        AspectList ours = Overlay.get(stack.getItem());
        if (ours == null) return;
        // a variant (the wand) or an essentia container's own contents win over the item's base value; only the base is replaced
        AspectList base = Overlay.rawOf((IAspectIndex) this, stack.getItem());
        if (cir.getReturnValue().equals(base)) cir.setReturnValue(ours);
    }
}

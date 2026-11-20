package dev.movingcastle.aspectinterpreter.mixin;

import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.content.aspect.EntityAspects;
import dev.movingcastle.aspectinterpreter.EntityInterpreter;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every Thaumaturge entity-aspect read (scanning, goggles, wand charging, nodes...) goes through this method. */
@Mixin(value = EntityAspects.class, remap = false)
public abstract class EntityAspectsMixin {
    @Inject(method = "of(Lnet/minecraft/world/entity/EntityType;)Lcom/leclowndu93150/thaumaturge/api/aspect/AspectList;",
            at = @At("RETURN"), cancellable = true)
    private static void aspectInterpreter$fallback(EntityType<?> type, CallbackInfoReturnable<AspectList> cir) {
        // The table holds a guess for unassigned types, or the real entry plus additions; real entries are never reduced.
        AspectList fromTable = EntityInterpreter.guess(type);
        if (fromTable != null && !fromTable.isEmpty()) cir.setReturnValue(fromTable);
    }
}

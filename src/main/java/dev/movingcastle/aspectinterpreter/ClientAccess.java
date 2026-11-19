package dev.movingcastle.aspectinterpreter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;

/** Only loaded on the physical client. */
final class ClientAccess {
    private ClientAccess() {}

    static HolderLookup.Provider registries() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.registryAccess() : null;
    }
}

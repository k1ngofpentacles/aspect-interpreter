package dev.movingcastle.aspectinterpreter;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Only loaded on the physical client: forget the server's overlay and entity table when leaving a world. */
final class ClientHooks {
    private ClientHooks() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            Overlay.clear();
            EntityInterpreter.invalidate();
        });
    }
}

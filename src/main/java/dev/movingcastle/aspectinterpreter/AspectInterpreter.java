package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.RegisterAspectContributorsEvent;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import org.slf4j.Logger;

/**
 * Wiring only. Thaumaturge builds (or loads) its own index untouched by this mod; {@link OverlayBuilder} then computes our
 * values from scratch and {@code AspectIndexMixin} serves them on top. Nothing of ours is kept between launches except the
 * readable record {@code assignments.json}, and Thaumaturge's index cache file is never touched.
 */
@Mod(AspectInterpreter.MODID)
public final class AspectInterpreter {
    public static final String MODID = "aspect_interpreter";
    /** The jar's version (from the manifest via mods.toml), written into the record. */
    static volatile String VERSION = "dev";
    static final Logger LOG = LogUtils.getLogger();

    public AspectInterpreter(IEventBus modBus, ModContainer container) {
        VERSION = container.getModInfo().getVersion().toString();
        modBus.addListener(RegisterAspectContributorsEvent.class, e -> e.register(new InterpreterContributor()));
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> {
            var registrar = e.registrar("2");
            registrar.playToClient(EntityTablePayload.TYPE, EntityTablePayload.CODEC,
                    (payload, ctx) -> ctx.enqueueWork(() -> EntityInterpreter.acceptRemote(payload.table())));
            registrar.playToClient(OverlayPayload.TYPE, OverlayPayload.CODEC,
                    (payload, ctx) -> ctx.enqueueWork(payload::accept));
        });
        NeoForge.EVENT_BUS.addListener(DataMapsUpdatedEvent.class, e -> {
            if (e.getCause() != DataMapsUpdatedEvent.UpdateCause.SERVER_RELOAD) return; // clients get the server's tables
            Knowledge.markStale();
            EntityInterpreter.invalidate();
            OverlayBuilder.request();
        });
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> OverlayBuilder.request());
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> OverlayBuilder.stop());
        // a joining player gets whatever is computed so far; a later computation is sent to everyone
        NeoForge.EVENT_BUS.addListener(OnDatapackSyncEvent.class, e -> {
            if (e.getPlayer() == null || !OverlayBuilder.ready()) return;
            PacketDistributor.sendToPlayer(e.getPlayer(), OverlayPayload.current());
            PacketDistributor.sendToPlayer(e.getPlayer(), new EntityTablePayload(EntityInterpreter.table()));
        });
        // Thaumaturge builds its index in a server tick (Pre); ours follows in the same tick's Post once the index exists.
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> OverlayBuilder.onTick(e.getServer()));
        if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.register();
    }
}

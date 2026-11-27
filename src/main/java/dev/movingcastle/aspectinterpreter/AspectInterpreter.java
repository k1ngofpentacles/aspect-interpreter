package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.RegisterAspectContributorsEvent;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

@Mod(AspectInterpreter.MODID)
public final class AspectInterpreter {
    public static final String MODID = "aspect_interpreter";
    /** The jar's version (from the manifest via mods.toml); part of the cache signature, so every release rebuilds once. */
    static volatile String VERSION = "dev";
    static final Logger LOG = LogUtils.getLogger();

    public AspectInterpreter(IEventBus modBus, ModContainer container) {
        VERSION = container.getModInfo().getVersion().toString();
        modBus.addListener(RegisterAspectContributorsEvent.class, e -> e.register(new InterpreterContributor()));
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> e.registrar("1").playToClient(
                EntityTablePayload.TYPE, EntityTablePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> EntityInterpreter.acceptRemote(payload.table()))));
        NeoForge.EVENT_BUS.addListener(DataMapsUpdatedEvent.class, e -> {
            if (e.getCause() != DataMapsUpdatedEvent.UpdateCause.SERVER_RELOAD) return; // clients get the server's table
            Knowledge.markStale();
            EntityInterpreter.invalidate();
        });
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, e -> invalidateStaleThaumaturgeCache());
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> refresh(e.getServer()));
        NeoForge.EVENT_BUS.addListener(OnDatapackSyncEvent.class, e -> {
            if (e.getPlayer() == null) {
                refresh(e.getPlayerList().getServer());
                PacketDistributor.sendToAllPlayers(new EntityTablePayload(EntityInterpreter.table()));
            } else {
                PacketDistributor.sendToPlayer(e.getPlayer(), new EntityTablePayload(EntityInterpreter.table()));
            }
        });
        // Thaumaturge builds its index synchronously inside one server tick; report once that tick ends.
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> InterpreterContributor.finishBuildIfDone());
    }

    /** After an index build: redo the tables that were made at startup, and send the new entity table to connected players. */
    static void refreshAfterBuild(MinecraftServer server) {
        if (server == null) return;
        EntityInterpreter.invalidate();
        DimensionContext.compute(server);
        refresh(server);
        if (!server.getPlayerList().getPlayers().isEmpty())
            PacketDistributor.sendToAllPlayers(new EntityTablePayload(EntityInterpreter.table()));
    }

    private static void refresh(MinecraftServer server) {
        try {
            Knowledge.rebuildIfStale();
            Canon.compute(server);
            DimensionContext.compute(server);
            EntityInterpreter.warmUp();
            AssignmentLog.write();
        } catch (Throwable t) {
            LOG.warn("Aspect Interpreter refresh failed", t);
        }
    }

    /**
     * Thaumaturge caches its finished index in config/thaumaturge/aspect_index.json and only rebuilds when items,
     * base aspects or recipes change. That fingerprint does not include this mod, so the cache is moved aside
     * (once) whenever this mod's version or config changed since the last build. One launch is enough: the build learns
     * from Thaumaturge's own values in the same launch (see {@link CleanPass}). Stamp format: {@code <signature>;<mode>}
     * (the mode is kept for old stamps and no longer read).
     */
    private static void invalidateStaleThaumaturgeCache() {
        try {
            Config.get(); // make sure the config file exists before it is hashed
            Path cfgDir = FMLPaths.CONFIGDIR.get();
            String signature = signature(cfgDir);
            String[] previous = readStamp(cfgDir);
            Path cache = cfgDir.resolve("thaumaturge").resolve("aspect_index.json");
            boolean changed = !signature.equals(previous[0]);
            // a valid cache without matching clean training values would leave a launch with no clean learning: rebuild instead
            boolean lacksClean = !changed && Files.exists(cache) && CleanPass.cacheLacksCleanValues();
            if (!changed && !lacksClean) return;
            if (Files.exists(cache)) {
                Files.move(cache, cache.resolveSibling("aspect_index.before_interpreter.json"), StandardCopyOption.REPLACE_EXISTING);
                LOG.info(changed
                        ? "Aspect Interpreter: moved Thaumaturge's aspect index cache aside so it rebuilds once with interpreted aspects"
                        : "Aspect Interpreter: no clean training values match Thaumaturge's index cache; moved it aside so it rebuilds once");
            }
            if (changed) writeStamp(cfgDir, signature, "pending");
        } catch (Exception e) {
            LOG.warn("Aspect Interpreter could not check Thaumaturge's index cache", e);
        }
    }

    /** Called after each index build this mod took part in. */
    static void recordBuild() {
        try {
            Path cfgDir = FMLPaths.CONFIGDIR.get();
            writeStamp(cfgDir, signature(cfgDir), "index");
        } catch (Exception e) {
            LOG.warn("Aspect Interpreter could not record its build", e);
        }
    }

    private static Path stampFile(Path cfgDir) {
        return cfgDir.resolve("aspect_interpreter").resolve("applied_signature.txt");
    }

    private static String[] readStamp(Path cfgDir) throws Exception {
        Path stamp = stampFile(cfgDir);
        if (!Files.exists(stamp)) return new String[]{"", ""};
        String[] parts = Files.readString(stamp, StandardCharsets.UTF_8).trim().split(";", 2);
        return new String[]{parts[0], parts.length > 1 ? parts[1] : "index"};
    }

    private static void writeStamp(Path cfgDir, String signature, String mode) throws Exception {
        Path stamp = stampFile(cfgDir);
        Files.createDirectories(stamp.getParent());
        Files.writeString(stamp, signature + ";" + mode, StandardCharsets.UTF_8);
    }

    private static String signature(Path cfgDir) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(VERSION.getBytes(StandardCharsets.UTF_8));
        Path cfg = cfgDir.resolve("aspect_interpreter.json");
        if (Files.exists(cfg)) md.update(Files.readAllBytes(cfg));
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

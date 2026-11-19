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
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

@Mod(AspectInterpreter.MODID)
public final class AspectInterpreter {
    public static final String MODID = "aspect_interpreter";
    static final String VERSION = "1.2.0";
    static final Logger LOG = LogUtils.getLogger();

    public AspectInterpreter(IEventBus modBus, ModContainer container) {
        modBus.addListener(RegisterAspectContributorsEvent.class, e -> e.register(new InterpreterContributor()));
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent.class, e -> {
            Knowledge.markStale();
            EntityInterpreter.invalidate();
        });
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, e -> invalidateStaleThaumaturgeCache());
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> refresh());
        NeoForge.EVENT_BUS.addListener(OnDatapackSyncEvent.class, e -> {
            if (e.getPlayer() == null) refresh();
        });
        // Thaumaturge builds its index synchronously inside one server tick; report once that tick ends.
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> InterpreterContributor.finishBuildIfDone());
    }

    private static void refresh() {
        try {
            Knowledge.rebuildIfStale();
            EntityInterpreter.warmUp();
            AssignmentLog.write();
        } catch (Throwable t) {
            LOG.warn("Aspect Interpreter refresh failed", t);
        }
    }

    /**
     * Thaumaturge caches its finished index in config/thaumaturge/aspect_index.json and only rebuilds when items,
     * base aspects or recipes change. That fingerprint does not include this mod, so the cache is moved aside
     * (once) whenever:
     * <ul>
     *   <li>this mod's version or config changed since the last build, or</li>
     *   <li>the last build had to learn without an index cache (fresh install). Thaumaturge has since written one,
     *       so one more rebuild lets the interpreter learn from it. This happens at most once per signature.</li>
     * </ul>
     * Stamp format: {@code <signature>;<mode>} where mode is {@code pending}, {@code base} or {@code index}.
     */
    private static void invalidateStaleThaumaturgeCache() {
        try {
            Config.get(); // make sure the config file exists before it is hashed
            Path cfgDir = FMLPaths.CONFIGDIR.get();
            String signature = signature(cfgDir);
            String[] previous = readStamp(cfgDir);
            Path cache = cfgDir.resolve("thaumaturge").resolve("aspect_index.json");
            boolean changed = !signature.equals(previous[0]);
            boolean secondPass = !changed && "base".equals(previous[1]);
            if (!changed && !secondPass) return;
            if (Files.exists(cache)) {
                Files.move(cache, cache.resolveSibling("aspect_index.before_interpreter.json"), StandardCopyOption.REPLACE_EXISTING);
                LOG.info(changed
                        ? "Aspect Interpreter: moved Thaumaturge's aspect index cache aside so it rebuilds once with interpreted aspects"
                        : "Aspect Interpreter: rebuilding once more now that Thaumaturge's index cache exists to learn from");
            }
            // a second pass never schedules a third: it is recorded as index-trained whatever happens
            writeStamp(cfgDir, signature, secondPass ? "index" : "pending");
        } catch (Exception e) {
            LOG.warn("Aspect Interpreter could not check Thaumaturge's index cache", e);
        }
    }

    /** Called after each index build this mod took part in. */
    static void recordBuild(boolean learnedFromIndex) {
        try {
            Path cfgDir = FMLPaths.CONFIGDIR.get();
            String[] previous = readStamp(cfgDir);
            String mode = learnedFromIndex || "index".equals(previous[1]) ? "index" : "base";
            writeStamp(cfgDir, signature(cfgDir), mode);
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

package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.content.aspect.AspectIndex;
import com.leclowndu93150.thaumaturge.content.aspect.AspectIndexBuilder;
import com.leclowndu93150.thaumaturge.content.aspect.AspectIndexHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes everything this mod serves, from scratch, at every launch and every data reload (clean slate: nothing is read
 * from or saved for the next launch except the readable record {@code assignments.json}).
 *
 * <ol>
 *   <li>Wait for Thaumaturge's own index (loaded from its cache or built; it holds only Thaumaturge's values, because
 *       this mod's contributor and crafting mixin act only inside step 4).</li>
 *   <li>Train the model on that index plus the hand-written data maps.</li>
 *   <li>Canon, dimension context, families, crafting depth; the entity table (guessed mobs, enriched, held to their threat tier).</li>
 *   <li>Run Thaumaturge's own derivation once more with this mod switched on. The items whose value then differs from the
 *       real index are the overlay: our guesses, our additions, and everything crafted from those. Simple and
 *       deterministic: nothing is re-derived by hand, so crafted products follow Thaumaturge's own rules.</li>
 *   <li>Serve it ({@link Overlay}), write the record, and send it to connected clients.</li>
 * </ol>
 */
final class OverlayBuilder {
    private OverlayBuilder() {}

    /** The index object the next computation must differ from (set on a data reload, when the old index is still held). */
    private static volatile AspectIndex waitingPast = AspectIndex.EMPTY;
    private static volatile boolean pending;
    /** Duration of the last computation in ms, for logs and the pack test. */
    static volatile long lastMillis;

    /** A server start or data reload: compute as soon as Thaumaturge has a new index. */
    static void request() {
        waitingPast = AspectIndexHolder.getConcrete();
        pending = true;
    }

    /** End of every server tick; only a pointer check unless a computation is pending. */
    static void onTick(MinecraftServer server) {
        if (!pending) return;
        AspectIndex real = AspectIndexHolder.getConcrete();
        if (real == AspectIndex.EMPTY || real == waitingPast) return; // Thaumaturge has not (re)built yet
        pending = false;
        try {
            compute(server, real);
        } catch (Throwable t) {
            AspectInterpreter.LOG.warn("Aspect Interpreter could not compute its overlay; Thaumaturge's own values stand", t);
            Overlay.clear();
        }
    }

    static void stop() {
        pending = false;
        Overlay.clear();
        EntityInterpreter.invalidate();
        Knowledge.markStale();
    }

    static synchronized void compute(MinecraftServer server, AspectIndex real) {
        long t0 = System.nanoTime();
        Config cfg = Config.get();
        removeOldFiles();
        // 2. training: Thaumaturge's own values (this launch's index) and the hand-written data maps
        Knowledge.rebuild(cfg.learnFromThaumaturgeCache ? Knowledge.cleanFrom(real) : Map.of());
        // 3. the tables that need the model
        Canon.compute(server);
        DimensionContext.compute(server);
        FamilyLayer.prepare();
        CraftComplexity.prepare(server.getRecipeManager(), server.registryAccess());
        EntityInterpreter.invalidate();
        EntityInterpreter.warmUp();
        // 4. the overlay
        Map<Item, AspectList> overlay = new HashMap<>();
        AssignmentLog.clearItems();
        Additions.clear();
        if (cfg.items) {
            AspectIndex ours;
            Overlay.building(true);
            try {
                ours = Overlay.raw(() -> AspectIndexBuilder.build(server.getRecipeManager(), server.registryAccess()));
            } finally {
                Overlay.building(false);
            }
            for (Item item : BuiltInRegistries.ITEM) {
                if (HandWritten.item(item)) continue; // hand-written: nothing of ours, ever
                AspectList mine = Overlay.rawOf(ours, item);
                if (mine == null || mine.isEmpty()) continue;
                if (!Knowledge.toMap(mine).equals(Knowledge.toMap(Overlay.rawOf(real, item)))) overlay.put(item, mine);
            }
        }
        // 5. serve, record, send
        Overlay.set(overlay);
        AssignmentLog.write();
        lastMillis = (System.nanoTime() - t0) / 1_000_000;
        AspectInterpreter.LOG.info("Aspect Interpreter: overlay of {} items over Thaumaturge's index of {} ({}); {} guessed mobs; additions: {}; {} ms (clean slate, nothing saved but the record)",
                overlay.size(), real.size(), Additions.summary(), EntityInterpreter.table().size(), Additions.entitySummary(), lastMillis);
        if (!server.getPlayerList().getPlayers().isEmpty()) sendToAll();
    }

    /** Files of our own that earlier versions left in our config folder; nothing reads them any more. */
    private static void removeOldFiles() {
        try {
            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("aspect_interpreter");
            for (String old : new String[]{"clean_training.json", "interpreted_items.txt", "applied_signature.txt", "assignments.txt"})
                java.nio.file.Files.deleteIfExists(dir.resolve(old));
        } catch (Exception e) {
            AspectInterpreter.LOG.debug("Aspect Interpreter could not remove old files", e);
        }
    }

    static void sendToAll() {
        PacketDistributor.sendToAllPlayers(OverlayPayload.current());
        PacketDistributor.sendToAllPlayers(new EntityTablePayload(EntityInterpreter.table()));
    }

    /** True once the first computation of this launch has finished. */
    static boolean ready() { return !pending && EntityInterpreter.isReady(); }

    /** Sorted copy for tests. */
    static Map<String, Map<String, Integer>> snapshot() { return new TreeMap<>(Overlay.sorted()); }
}

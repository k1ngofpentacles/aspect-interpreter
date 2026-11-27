package dev.movingcastle.aspectinterpreter;

import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Server to client: the item overlay (item id to aspect list), so the client's tooltips, scanning and crafting readouts
 * agree with the server. A client gets Thaumaturge's own index from Thaumaturge and this on top of it.
 */
record OverlayPayload(Map<ResourceLocation, AspectList> items) implements CustomPacketPayload {
    static final Type<OverlayPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AspectInterpreter.MODID, "item_overlay"));

    static final StreamCodec<RegistryFriendlyByteBuf, OverlayPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.items().size());
                p.items().forEach((id, list) -> {
                    buf.writeResourceLocation(id);
                    AspectList.STREAM_CODEC.encode(buf, list);
                });
            },
            buf -> {
                int n = buf.readVarInt();
                Map<ResourceLocation, AspectList> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) m.put(buf.readResourceLocation(), AspectList.STREAM_CODEC.decode(buf));
                return new OverlayPayload(m);
            });

    /** The server's current overlay, sorted by item id. */
    static OverlayPayload current() {
        Map<ResourceLocation, AspectList> m = new TreeMap<>();
        Overlay.snapshot().forEach((item, list) -> m.put(BuiltInRegistries.ITEM.getKey(item), list));
        return new OverlayPayload(m);
    }

    /** Client side: adopt the server's overlay. */
    void accept() {
        Map<Item, AspectList> m = new java.util.HashMap<>();
        items.forEach((id, list) -> {
            if (BuiltInRegistries.ITEM.containsKey(id)) m.put(BuiltInRegistries.ITEM.get(id), list);
        });
        Overlay.set(m);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

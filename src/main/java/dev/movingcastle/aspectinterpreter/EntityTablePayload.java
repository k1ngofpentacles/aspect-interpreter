package dev.movingcastle.aspectinterpreter;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** Server → client: the finished entity guess table (entity id → aspect id → amount). A few kilobytes. */
record EntityTablePayload(Map<String, Map<String, Integer>> table) implements CustomPacketPayload {
    static final Type<EntityTablePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AspectInterpreter.MODID, "entity_table"));

    static final StreamCodec<RegistryFriendlyByteBuf, EntityTablePayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeMap(p.table(), FriendlyByteBuf::writeUtf,
                    (b, m) -> b.writeMap(m, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeVarInt)),
            buf -> new EntityTablePayload(buf.readMap(FriendlyByteBuf::readUtf,
                    b -> b.readMap(FriendlyByteBuf::readUtf, FriendlyByteBuf::readVarInt))));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

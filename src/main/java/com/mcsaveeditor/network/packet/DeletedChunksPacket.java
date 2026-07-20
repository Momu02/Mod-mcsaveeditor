package com.mcsaveeditor.network.packet;

import com.mcsaveeditor.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record DeletedChunksPacket(List<ChunkPos> chunks) {
    public static final int MAX_RESULTS = 256;

    public static void encode(DeletedChunksPacket packet, FriendlyByteBuf buf) {
        int size = Math.min(packet.chunks.size(), MAX_RESULTS);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            ChunkPos pos = packet.chunks.get(i);
            buf.writeVarInt(pos.x);
            buf.writeVarInt(pos.z);
        }
    }

    public static DeletedChunksPacket decode(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_RESULTS);
        List<ChunkPos> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            chunks.add(new ChunkPos(buf.readVarInt(), buf.readVarInt()));
        }
        return new DeletedChunksPacket(chunks);
    }

    public static void handle(DeletedChunksPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.handleDeletedChunks(packet)));
        context.setPacketHandled(true);
    }
}

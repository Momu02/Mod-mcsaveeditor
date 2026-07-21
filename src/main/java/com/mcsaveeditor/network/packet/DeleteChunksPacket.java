package com.mcsaveeditor.network.packet;

import com.mcsaveeditor.server.ServerChunkEraseService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record DeleteChunksPacket(ChunkOperation operation, List<ChunkPos> chunks) {
    public static final int MAX_DELETE_COUNT = 4096;

    public static void encode(DeleteChunksPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.operation);
        int size = Math.min(packet.chunks.size(), MAX_DELETE_COUNT);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            ChunkPos pos = packet.chunks.get(i);
            buf.writeVarInt(pos.x);
            buf.writeVarInt(pos.z);
        }
    }

    public static DeleteChunksPacket decode(FriendlyByteBuf buf) {
        ChunkOperation operation = buf.readEnum(ChunkOperation.class);
        int size = Math.min(buf.readVarInt(), MAX_DELETE_COUNT);
        List<ChunkPos> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            chunks.add(new ChunkPos(buf.readVarInt(), buf.readVarInt()));
        }
        return new DeleteChunksPacket(operation, chunks);
    }

    public static void handle(DeleteChunksPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                ServerChunkEraseService.handleDeleteRequest(sender, packet);
            }
        });
        context.setPacketHandled(true);
    }
}

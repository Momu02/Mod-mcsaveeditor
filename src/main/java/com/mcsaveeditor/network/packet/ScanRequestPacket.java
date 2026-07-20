package com.mcsaveeditor.network.packet;

import com.mcsaveeditor.server.ServerChunkScanService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ScanRequestPacket(int centerChunkX, int centerChunkZ, int radiusChunks) {
    public static final int MAX_RADIUS = 512;

    public static void encode(ScanRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.centerChunkX);
        buf.writeVarInt(packet.centerChunkZ);
        buf.writeVarInt(packet.radiusChunks);
    }

    public static ScanRequestPacket decode(FriendlyByteBuf buf) {
        return new ScanRequestPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(ScanRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                ServerChunkScanService.handleScanRequest(sender, packet);
            }
        });
        context.setPacketHandled(true);
    }
}

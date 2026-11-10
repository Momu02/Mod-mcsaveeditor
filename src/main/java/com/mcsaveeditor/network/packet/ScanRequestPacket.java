package com.mcsaveeditor.network.packet;

import com.mcsaveeditor.server.ServerChunkScanService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ScanRequestPacket(int centerChunkX, int centerChunkZ, boolean limitRadius, int radiusChunks,
                                boolean limitInhabitedTime, int maxInhabitedMinutes) {
    public static final int MAX_RADIUS = 512;

    public static void encode(ScanRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.centerChunkX);
        buf.writeVarInt(packet.centerChunkZ);
        buf.writeBoolean(packet.limitRadius);
        buf.writeVarInt(packet.radiusChunks);
        buf.writeBoolean(packet.limitInhabitedTime);
        buf.writeVarInt(packet.maxInhabitedMinutes);
    }

    public static ScanRequestPacket decode(FriendlyByteBuf buf) {
        return new ScanRequestPacket(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
                buf.readBoolean(), buf.readVarInt());
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

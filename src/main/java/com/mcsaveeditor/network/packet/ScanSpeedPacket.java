package com.mcsaveeditor.network.packet;

import com.mcsaveeditor.server.ServerChunkScanService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ScanSpeedPacket(int chunksPerTick) {

    public static void encode(ScanSpeedPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.chunksPerTick);
    }

    public static ScanSpeedPacket decode(FriendlyByteBuf buf) {
        return new ScanSpeedPacket(buf.readVarInt());
    }

    public static void handle(ScanSpeedPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                ServerChunkScanService.handleScanSpeedRequest(sender, packet);
            }
        });
        context.setPacketHandled(true);
    }
}

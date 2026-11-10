package com.mcsaveeditor.network.packet;

import com.mcsaveeditor.server.ServerChunkScanService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record CancelScanPacket() {
    public static void encode(CancelScanPacket packet, FriendlyByteBuf buf) {
    }

    public static CancelScanPacket decode(FriendlyByteBuf buf) {
        return new CancelScanPacket();
    }

    public static void handle(CancelScanPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                ServerChunkScanService.cancelScan(sender, true);
            }
        });
        context.setPacketHandled(true);
    }
}

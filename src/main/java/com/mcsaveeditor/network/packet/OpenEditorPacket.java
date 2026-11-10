package com.mcsaveeditor.network.packet;

import com.mcsaveeditor.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenEditorPacket(int centerChunkX, int centerChunkZ) {
    public static void encode(OpenEditorPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.centerChunkX);
        buf.writeVarInt(packet.centerChunkZ);
    }

    public static OpenEditorPacket decode(FriendlyByteBuf buf) {
        return new OpenEditorPacket(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(OpenEditorPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.openEditor(packet.centerChunkX(), packet.centerChunkZ())));
        context.setPacketHandled(true);
    }
}

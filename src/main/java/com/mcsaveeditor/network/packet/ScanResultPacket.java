package com.mcsaveeditor.network.packet;

import com.mcsaveeditor.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record ScanResultPacket(List<ChunkTimeEntry> chunks) {
    public static final int MAX_RESULTS = 8192;

    public static void encode(ScanResultPacket packet, FriendlyByteBuf buf) {
        int size = Math.min(packet.chunks.size(), MAX_RESULTS);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            ChunkTimeEntry entry = packet.chunks.get(i);
            buf.writeVarInt(entry.chunkX());
            buf.writeVarInt(entry.chunkZ());
            buf.writeVarLong(entry.inhabitedTicks());
        }
    }

    public static ScanResultPacket decode(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_RESULTS);
        List<ChunkTimeEntry> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            chunks.add(new ChunkTimeEntry(buf.readVarInt(), buf.readVarInt(), buf.readVarLong()));
        }
        return new ScanResultPacket(chunks);
    }

    public static void handle(ScanResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.handleScanResult(packet)));
        context.setPacketHandled(true);
    }
}

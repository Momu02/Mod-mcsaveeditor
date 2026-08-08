package com.mcsaveeditor.client;

import com.mcsaveeditor.client.gui.ChunkEditorScreen;
import com.mcsaveeditor.client.promo.PromoClient;
import com.mcsaveeditor.network.packet.DeletedChunksPacket;
import com.mcsaveeditor.network.packet.ScanResultPacket;
import net.minecraft.client.Minecraft;

public final class ClientPacketHandlers {
    private ClientPacketHandlers() {
    }

    public static void openEditor(int centerChunkX, int centerChunkZ) {
        ChunkEditorScreen editor = new ChunkEditorScreen(centerChunkX, centerChunkZ);
        Minecraft.getInstance().setScreen(PromoClient.withPromo(editor));
    }

    public static void handleScanResult(ScanResultPacket packet) {
        if (Minecraft.getInstance().screen instanceof ChunkEditorScreen screen) {
            screen.acceptScanResult(packet.chunks());
        }
    }

    public static void handleDeletedChunks(DeletedChunksPacket packet) {
        if (Minecraft.getInstance().screen instanceof ChunkEditorScreen screen) {
            screen.acceptProcessedChunks(packet.operation(), packet.chunks());
        }
    }
}

package com.mcsaveeditor.network;

import com.mcsaveeditor.MCSaveEditorMod;
import com.mcsaveeditor.network.packet.DeleteChunksPacket;
import com.mcsaveeditor.network.packet.OpenEditorPacket;
import com.mcsaveeditor.network.packet.ScanRequestPacket;
import com.mcsaveeditor.network.packet.ScanResultPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class ModNetworking {
    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(MCSaveEditorMod.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private ModNetworking() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, ScanRequestPacket.class, ScanRequestPacket::encode, ScanRequestPacket::decode,
                ScanRequestPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, ScanResultPacket.class, ScanResultPacket::encode, ScanResultPacket::decode,
                ScanResultPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DeleteChunksPacket.class, DeleteChunksPacket::encode, DeleteChunksPacket::decode,
                DeleteChunksPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id, OpenEditorPacket.class, OpenEditorPacket::encode, OpenEditorPacket::decode,
                OpenEditorPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}

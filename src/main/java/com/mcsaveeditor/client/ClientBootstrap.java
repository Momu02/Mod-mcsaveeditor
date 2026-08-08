package com.mcsaveeditor.client;

import net.minecraftforge.common.MinecraftForge;

public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.register(ClientBootstrap.class);
    }
}

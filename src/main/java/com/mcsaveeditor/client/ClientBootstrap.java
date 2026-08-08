package com.mcsaveeditor.client;

import com.mcsaveeditor.client.promo.PromoClient;
import net.minecraftforge.common.MinecraftForge;

public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.register(ClientBootstrap.class);
        MinecraftForge.EVENT_BUS.register(PromoClient.class);
    }
}

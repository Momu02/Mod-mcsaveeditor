package com.mcsaveeditor.client;

import com.mcsaveeditor.promo.PromoInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Injects a single unobtrusive "join AmityMC" button into the bottom-right
 * corner of the title screen. No splash, no popups, no chat spam — players
 * only see the button on the main menu, and clicking it connects straight
 * to the server.
 */
public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void init() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(ClientBootstrap.class);
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen title)) {
            return;
        }
        int bw = 130;
        int bx = title.width - bw - 8;
        int by = title.height - 30;
        Button button = Button.builder(Component.literal("加入 AmityMC").withStyle(ChatFormatting.GOLD),
                        b -> joinServer())
                .bounds(bx, by, bw, 20)
                .build();
        event.addListener(button);
    }

    private static void joinServer() {
        Minecraft mc = Minecraft.getInstance();
        ServerAddress address = ServerAddress.parseString(PromoInfo.SERVER_IP);
        ServerData data = new ServerData(PromoInfo.SERVER_NAME, PromoInfo.SERVER_IP, false);
        ConnectScreen.startConnecting(mc.screen, mc, address, data, false);
    }
}

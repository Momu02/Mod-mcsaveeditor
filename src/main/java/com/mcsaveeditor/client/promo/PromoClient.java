package com.mcsaveeditor.client.promo;

import com.mcsaveeditor.client.PromoConfig;
import com.mcsaveeditor.promo.PromoInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Client-side promotion engine. Shows the AmityMC splash when the player
 * enters a world, injects a "join" button onto the title screen, sends
 * periodic chat reminders with a click-to-join link, and provides the
 * direct-join entry point used by the editor banner.
 */
public final class PromoClient {
    private static final int SPLASH_DELAY_TICKS = 40;

    private static boolean splashPending;
    private static int splashDelay;
    private static long lastReminderMs;

    private PromoClient() {
    }

    // ─── Direct join / website ─────────────────────────────────────────

    public static void joinServer() {
        Minecraft mc = Minecraft.getInstance();
        ServerData data = new ServerData(PromoInfo.SERVER_NAME, PromoInfo.SERVER_IP, false);
        mc.setScreen(new ConnectScreen(mc.screen, mc, data));
    }

    public static void openWebsite() {
        Util.getPlatform().openUri(PromoInfo.WEBSITE_URL);
    }

    // ─── World join → forced splash ────────────────────────────────────

    /**
     * Wraps the given screen with the forced promo splash when the splash is
     * enabled, so e.g. the chunk editor is only reachable after the player
     * has seen the AmityMC page. Returns the original screen untouched when
     * the promotion is disabled.
     */
    public static Screen withPromo(Screen screen) {
        if (!PromoConfig.splashEnabled()) {
            return screen;
        }
        Minecraft mc = Minecraft.getInstance();
        if (isOnPromotedServer(mc)) {
            return screen;
        }
        return new PromoScreen(PromoConfig.splashSeconds(), screen);
    }

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!PromoConfig.splashEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (isOnPromotedServer(mc)) {
            return;
        }
        splashPending = true;
        splashDelay = 0;
        lastReminderMs = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        splashPending = false;
    }

    private static boolean isOnPromotedServer(Minecraft mc) {
        ServerData current = mc.getCurrentServer();
        return current != null && current.ip != null
                && current.ip.toLowerCase().contains(PromoInfo.SERVER_IP.toLowerCase());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        if (splashPending) {
            splashDelay++;
            if (mc.screen == null && mc.player != null && mc.level != null
                    && splashDelay >= SPLASH_DELAY_TICKS) {
                splashPending = false;
                mc.setScreen(new PromoScreen(PromoConfig.splashSeconds()));
            }
            return;
        }

        if (PromoConfig.chatReminders() && PromoConfig.reminderMinutes() > 0
                && mc.player != null && mc.level != null && mc.screen == null
                && !isOnPromotedServer(mc)) {
            sendPeriodicReminder();
        }
    }

    private static void sendPeriodicReminder() {
        long intervalMs = PromoConfig.reminderMinutes() * 60_000L;
        long now = System.currentTimeMillis();
        if (lastReminderMs != 0 && now - lastReminderMs < intervalMs) {
            return;
        }
        lastReminderMs = now;
        Minecraft mc = Minecraft.getInstance();
        mc.player.displayClientMessage(reminderMessage(), false);
    }

    private static Component reminderMessage() {
        Component prefix = Component.literal("[AmityMC] ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        Component text = Component.literal("欢迎游玩生存服务器！服务器 IP: ")
                .withStyle(ChatFormatting.GRAY);
        Component ip = Component.literal(PromoInfo.SERVER_IP)
                .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, PromoInfo.SERVER_IP)));
        Component click = Component.literal(" 点击加入 →")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/amitymc join")));
        return prefix.copy().append(text).append(ip).append(click);
    }

    // ─── Title screen button ───────────────────────────────────────────

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!PromoConfig.titleButton()) {
            return;
        }
        if (!(event.getScreen() instanceof TitleScreen title)) {
            return;
        }
        int bw = 130;
        int bx = title.width - bw - 8;
        int by = title.height - 30;
        Button button = Button.builder(Component.literal("加入 AmityMC 服务器").withStyle(ChatFormatting.GOLD),
                        b -> joinServer())
                .bounds(bx, by, bw, 20)
                .build();
        event.addListener(button);
    }

    // ─── Client command: /amitymc [join|promo] ─────────────────────────

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("amitymc")
                .then(Commands.literal("join").executes(ctx -> {
                    joinServer();
                    return 1;
                }))
                .then(Commands.literal("promo").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen(new PromoScreen(PromoConfig.splashSeconds()));
                    return 1;
                }))
                .executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen(new PromoScreen(PromoConfig.splashSeconds()));
                    return 1;
                }));
    }
}

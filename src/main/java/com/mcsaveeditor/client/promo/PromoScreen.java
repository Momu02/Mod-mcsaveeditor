package com.mcsaveeditor.client.promo;

import com.mcsaveeditor.promo.PromoInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Forced promotion splash shown when the player enters a world. The player
 * must wait {@code splashSeconds} before the skip button unlocks; the
 * "join server" and "visit website" buttons work immediately.
 */
public class PromoScreen extends Screen {
    private final int splashSeconds;
    private final Screen nextScreen;
    private int ticks;
    private Button skipButton;

    public PromoScreen(int splashSeconds) {
        this(splashSeconds, null);
    }

    /** @param nextScreen screen to open after the player skips (e.g. the editor), may be null */
    public PromoScreen(int splashSeconds, Screen nextScreen) {
        super(Component.literal("AmityMC"));
        this.splashSeconds = Math.max(0, splashSeconds);
        this.nextScreen = nextScreen;
    }

    @Override
    protected void init() {
        int panelW = 320;
        int panelH = 190;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;
        int bw = 200;
        int bx = x + (panelW - bw) / 2;
        int by = y + 100;

        addRenderableWidget(Button.builder(Component.literal("立即加入服务器").withStyle(ChatFormatting.GOLD),
                        button -> joinServer())
                .bounds(bx, by, bw, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("访问官网 amitymc.com"),
                        button -> openWebsite())
                .bounds(bx, by + 24, bw, 20)
                .build());
        skipButton = Button.builder(skipLabel(), button -> onClose())
                .bounds(bx, by + 48, bw, 20)
                .build();
        skipButton.active = splashSeconds <= 0;
        addRenderableWidget(skipButton);
    }

    @Override
    public void onClose() {
        if (nextScreen != null) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(nextScreen);
        } else {
            super.onClose();
        }
    }

    private Component skipLabel() {
        int remain = Math.max(0, splashSeconds - ticks / 20);
        return Component.literal(remain > 0 ? "跳过（" + remain + " 秒）" : "跳过");
    }

    @Override
    public void tick() {
        super.tick();
        if (skipButton != null && !skipButton.active) {
            ticks++;
            if (ticks / 20 >= splashSeconds) {
                skipButton.active = true;
                skipButton.setMessage(skipLabel());
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int panelW = 320;
        int panelH = 190;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        graphics.fill(x, y, x + panelW, y + panelH, 0xCC151A1E);
        graphics.fill(x, y, x + panelW, y + 2, 0xFFF2C230);
        graphics.fill(x, y + panelH - 1, x + panelW, y + panelH, 0xFFF2C230);

        int cx = width / 2;
        graphics.drawCenteredString(font,
                Component.literal(PromoInfo.SERVER_NAME).withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)
                        .withBold(true)),
                cx, y + 22, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.literal(PromoInfo.SLOGAN).withStyle(ChatFormatting.YELLOW),
                cx, y + 44, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.literal("服务器 IP: " + PromoInfo.SERVER_IP).withStyle(ChatFormatting.AQUA),
                cx, y + 62, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.literal("官网: " + PromoInfo.WEBSITE_URL).withStyle(ChatFormatting.GREEN),
                cx, y + 80, 0xFFFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void joinServer() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConnectScreen(this, mc,
                new ServerData(PromoInfo.SERVER_NAME, PromoInfo.SERVER_IP, false)));
    }

    private void openWebsite() {
        Util.getPlatform().openUri(PromoInfo.WEBSITE_URL);
    }
}

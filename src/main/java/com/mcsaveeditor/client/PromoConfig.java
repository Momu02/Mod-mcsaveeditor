package com.mcsaveeditor.client;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Client-side settings for the AmityMC promotion system. Every toggle is
 * client-side only (promotions run entirely on the client), so nothing here
 * affects the mod's server-side chunk tools.
 */
public final class PromoConfig {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue SPLASH_ENABLED;
    private static final ForgeConfigSpec.IntValue SPLASH_SECONDS;
    private static final ForgeConfigSpec.IntValue REMINDER_MINUTES;
    private static final ForgeConfigSpec.BooleanValue TITLE_BUTTON;
    private static final ForgeConfigSpec.BooleanValue EDITOR_BANNER;
    private static final ForgeConfigSpec.BooleanValue CHAT_REMINDERS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SPLASH_ENABLED = builder.comment("是否在进入存档时展示 AmityMC 推广页")
                .define("promo.splashEnabled", true);
        SPLASH_SECONDS = builder.comment("推广页最短停留秒数（未到时间无法跳过）")
                .defineInRange("promo.splashSeconds", 5, 0, 30);
        REMINDER_MINUTES = builder.comment("聊天栏推广提醒间隔（分钟，0 = 关闭）")
                .defineInRange("promo.reminderMinutes", 5, 0, 120);
        TITLE_BUTTON = builder.comment("主菜单是否显示『加入 AmityMC』按钮")
                .define("promo.titleButton", true);
        EDITOR_BANNER = builder.comment("区块编辑器侧边栏是否显示推广横幅")
                .define("promo.editorBanner", true);
        CHAT_REMINDERS = builder.comment("是否启用游戏内聊天栏周期性推广提醒")
                .define("promo.chatReminders", true);
        SPEC = builder.build();
    }

    private PromoConfig() {
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    public static boolean splashEnabled() {
        return SPLASH_ENABLED.get();
    }

    public static int splashSeconds() {
        return SPLASH_SECONDS.get();
    }

    public static int reminderMinutes() {
        return REMINDER_MINUTES.get();
    }

    public static boolean titleButton() {
        return TITLE_BUTTON.get();
    }

    public static boolean editorBanner() {
        return EDITOR_BANNER.get();
    }

    public static boolean chatReminders() {
        return CHAT_REMINDERS.get();
    }
}

package com.mcsaveeditor.promo;

/**
 * Central place for every piece of AmityMC branding the promotion system
 * shows to players. Change these constants and every splash screen, chat
 * reminder, title button and editor banner follows automatically.
 */
public final class PromoInfo {
    private PromoInfo() {
    }

    /** Server display name used on the splash screen and buttons. */
    public static final String SERVER_NAME = "AmityMC";

    /** Server address (host or host:port) used for the direct-join action. */
    public static final String SERVER_IP = "amitymc.com";

    /** Public website URL opened by the "visit website" action. */
    public static final String WEBSITE_URL = "https://amitymc.com";

    /** Short slogan shown under the server name on the splash screen. */
    public static final String SLOGAN = "原汁原味生存 · 长期稳定 · 玩家至上";

    /** Extra promotional lines drawn on the splash screen. */
    public static final String[] TAGLINES = {
            "在线人数常年火爆，欢迎入驻！",
            "本工具由 AmityMC 服务器提供支持"
    };
}

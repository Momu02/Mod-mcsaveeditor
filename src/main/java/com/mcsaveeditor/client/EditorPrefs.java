package com.mcsaveeditor.client;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Client-side preferences. Every editor parameter is remembered here and
 * restored the next time the editor screen opens (saved on screen close).
 */
public final class EditorPrefs {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue LIMIT_RADIUS;
    private static final ForgeConfigSpec.IntValue RADIUS;
    private static final ForgeConfigSpec.BooleanValue LIMIT_TIME;
    private static final ForgeConfigSpec.IntValue MINUTES;
    private static final ForgeConfigSpec.IntValue SCAN_SPEED;
    private static final ForgeConfigSpec.BooleanValue RANDOM_SEED;
    private static final ForgeConfigSpec.LongValue TARGET_SEED;
    private static final ForgeConfigSpec.ConfigValue<String> BIOME;
    private static final ForgeConfigSpec.DoubleValue CELL_SIZE;
    private static final ForgeConfigSpec.DoubleValue PAN_X;
    private static final ForgeConfigSpec.DoubleValue PAN_Z;
    private static final ForgeConfigSpec.ConfigValue<String> PROTECTED_CATEGORIES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        LIMIT_RADIUS = builder.comment("是否限制扫描半径（勾选）").define("scan.limitRadius", false);
        RADIUS = builder.comment("扫描区块半径").defineInRange("scan.radius", 128, 1, 512);
        LIMIT_TIME = builder.comment("是否限制停留时间（勾选）").define("scan.limitInhabitedTime", false);
        MINUTES = builder.comment("最长停留分钟").defineInRange("scan.maxMinutes", 5, 0, Integer.MAX_VALUE);
        SCAN_SPEED = builder.comment("扫描速度（区块/刻）").defineInRange("scan.speed", 32, 1, 256);
        RANDOM_SEED = builder.comment("重建地形使用随机种子").define("regenerate.randomSeed", true);
        TARGET_SEED = builder.comment("重建地形目标种子（随机种子关闭时生效）")
                .defineInRange("regenerate.seed", 0L, Long.MIN_VALUE, Long.MAX_VALUE);
        BIOME = builder.comment("重建地形群系 id（空 = 原版世界随机）").define("regenerate.biome", "");
        CELL_SIZE = builder.comment("地图缩放（像素/区块）").defineInRange("map.cellSize", 14.0D, 1.0D, 48.0D);
        PAN_X = builder.comment("地图水平平移（区块）").defineInRange("map.panX", 0.0D, -1.0E7D, 1.0E7D);
        PAN_Z = builder.comment("地图垂直平移（区块）").defineInRange("map.panZ", 0.0D, -1.0E7D, 1.0E7D);
        PROTECTED_CATEGORIES = builder.comment("融合策略：启用的保护分类 id（逗号分隔，空 = 默认）")
                .define("regenerate.protectedCategories", "");
        SPEC = builder.build();
    }

    private EditorPrefs() {
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    public static void save() {
        SPEC.save();
    }

    public static boolean limitRadius() {
        return LIMIT_RADIUS.get();
    }

    public static void setLimitRadius(boolean value) {
        LIMIT_RADIUS.set(value);
    }

    public static int radius() {
        return RADIUS.get();
    }

    public static void setRadius(int value) {
        RADIUS.set(value);
    }

    public static boolean limitInhabitedTime() {
        return LIMIT_TIME.get();
    }

    public static void setLimitInhabitedTime(boolean value) {
        LIMIT_TIME.set(value);
    }

    public static int maxMinutes() {
        return MINUTES.get();
    }

    public static void setMaxMinutes(int value) {
        MINUTES.set(value);
    }

    public static int scanSpeed() {
        return SCAN_SPEED.get();
    }

    public static void setScanSpeed(int value) {
        SCAN_SPEED.set(value);
    }

    public static boolean randomSeed() {
        return RANDOM_SEED.get();
    }

    public static void setRandomSeed(boolean value) {
        RANDOM_SEED.set(value);
    }

    public static long targetSeed() {
        return TARGET_SEED.get();
    }

    public static void setTargetSeed(long value) {
        TARGET_SEED.set(value);
    }

    public static String biome() {
        return BIOME.get();
    }

    public static void setBiome(String value) {
        BIOME.set(value == null ? "" : value);
    }

    public static double cellSize() {
        return CELL_SIZE.get();
    }

    public static void setCellSize(double value) {
        CELL_SIZE.set(value);
    }

    public static double panX() {
        return PAN_X.get();
    }

    public static void setPanX(double value) {
        PAN_X.set(value);
    }

    public static double panZ() {
        return PAN_Z.get();
    }

    public static void setPanZ(double value) {
        PAN_Z.set(value);
    }

    public static String protectedCategories() {
        return PROTECTED_CATEGORIES.get();
    }

    public static void setProtectedCategories(String value) {
        PROTECTED_CATEGORIES.set(value == null ? "" : value);
    }
}

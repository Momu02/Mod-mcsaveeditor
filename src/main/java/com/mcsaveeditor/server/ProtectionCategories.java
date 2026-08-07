package com.mcsaveeditor.server;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.GlassPaneBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Centralized block classification used by the terrain merger.
 *
 * <p>MC 1.20.1 has a rich built-in tag system ({@link BlockTags}, data-driven
 * from {@code data/minecraft/tags/blocks/*.json}), which is what this class is
 * built on. The GUI exposes a subset as toggleable protection categories, each
 * mapping to one or more tags (see {@link #CATEGORIES}).</p>
 */
public final class ProtectionCategories {

    /** Mod-provided tags filling gaps in the vanilla tag set. */
    public static final TagKey<net.minecraft.world.level.block.Block> CONCRETE =
            modTag("concrete");
    public static final TagKey<net.minecraft.world.level.block.Block> GLAZED_TERRACOTTA =
            modTag("glazed_terracotta");
    public static final TagKey<net.minecraft.world.level.block.Block> POLISHED_STONE =
            modTag("polished_stone");
    public static final TagKey<net.minecraft.world.level.block.Block> GLASS_PANES =
            modTag("glass_panes");

    /** A user-facing protection category: id, label, mapped tags. */
    public record Category(String id, String label, List<TagKey<net.minecraft.world.level.block.Block>> tags,
                           boolean defaultOn) {
    }

    /**
     * Categories offered in the editor GUI.
     *
     * <p>Built-looking categories (glass/concrete/terracotta/wool/planks/stone
     * bricks) are protected by default, preserving the classic behaviour.
     * Natural-looking categories (dirt/sand/stone/snow/ice) are off by default
     * and can be enabled e.g. to keep a dirt foundation while rebuilding.</p>
     */
    public static final List<Category> CATEGORIES = List.of(
            new Category("dirt", "泥土", List.of(BlockTags.DIRT), false),
            new Category("sand", "沙", List.of(BlockTags.SAND), false),
            new Category("stone", "石头", List.of(BlockTags.BASE_STONE_OVERWORLD, BlockTags.BASE_STONE_NETHER), false),
            new Category("snow", "雪", List.of(BlockTags.SNOW), false),
            new Category("ice", "冰", List.of(BlockTags.ICE), false),
            new Category("glass", "玻璃", List.of(BlockTags.IMPERMEABLE, GLASS_PANES), true),
            new Category("concrete", "混凝土", List.of(CONCRETE), true),
            new Category("terracotta", "陶瓦", List.of(BlockTags.TERRACOTTA, GLAZED_TERRACOTTA), true),
            new Category("wool", "羊毛", List.of(BlockTags.WOOL, BlockTags.WOOL_CARPETS), true),
            new Category("planks", "木板", List.of(BlockTags.PLANKS), true),
            new Category("stone_bricks", "石砖", List.of(BlockTags.STONE_BRICKS, POLISHED_STONE), true)
    );

    private ProtectionCategories() {
    }

    private static TagKey<net.minecraft.world.level.block.Block> modTag(String name) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation("mcsaveeditor", name));
    }

    /**
     * Blocks that are structurally player-made and always preserved (unless
     * explicitly excluded): furniture, doors, torches, containers, …
     */
    static boolean isCoreBuildBlock(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        var block = state.getBlock();
        if (block instanceof StairBlock
                || block instanceof SlabBlock
                || block instanceof WallBlock
                || block instanceof FenceBlock
                || block instanceof FenceGateBlock
                || block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof BedBlock
                || block instanceof GlassPaneBlock
                || block instanceof IronBarsBlock
                || block instanceof LadderBlock
                || block instanceof LanternBlock
                || block instanceof ChainBlock
                || block instanceof SignBlock
                || block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof BasePressurePlateBlock
                || block instanceof TorchBlock
                || block instanceof BaseEntityBlock) {
            return true;
        }
        if (state.is(BlockTags.BEDS)
                || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.DOORS)
                || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.BUTTONS)
                || state.is(BlockTags.RAILS)
                || state.is(BlockTags.ANVIL)
                || state.is(BlockTags.BANNERS)) {
            return true;
        }
        return state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.SMOOTH_STONE)
                || state.is(Blocks.BRICKS)
                || state.is(Blocks.BOOKSHELF)
                || state.is(Blocks.CRAFTING_TABLE)
                || state.is(Blocks.FURNACE)
                || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER)
                || state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.BARREL)
                || state.is(Blocks.ENDER_CHEST)
                || state.is(Blocks.ENCHANTING_TABLE)
                || state.is(Blocks.IRON_BLOCK)
                || state.is(Blocks.GOLD_BLOCK)
                || state.is(Blocks.DIAMOND_BLOCK)
                || state.is(Blocks.EMERALD_BLOCK)
                || state.is(Blocks.NETHERITE_BLOCK)
                || state.is(Blocks.COPPER_BLOCK)
                || state.is(Blocks.CUT_COPPER)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.FARMLAND)
                || state.is(Blocks.HAY_BLOCK);
    }

    /**
     * Blocks that are naturally generated terrain and must never be treated as
     * player-built by default (a player can still opt in via categories).
     */
    static boolean isNaturalTerrainBlock(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)) {
            return true;
        }
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.FLOWERS)) {
            return true;
        }
        if (state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES)
                || state.is(BlockTags.EMERALD_ORES) || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.DIAMOND_ORES)) {
            return true;
        }
        return state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.DRIPSTONE_BLOCK)
                || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.SMOOTH_BASALT)
                || state.is(Blocks.AMETHYST_BLOCK)
                || state.is(Blocks.BUDDING_AMETHYST)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.BEDROCK)
                || state.is(Blocks.NETHERRACK)
                || state.is(Blocks.END_STONE)
                || state.is(Blocks.SOUL_SAND)
                || state.is(Blocks.SOUL_SOIL)
                || state.is(Blocks.GLOWSTONE)
                || state.is(Blocks.BLACKSTONE)
                || state.is(Blocks.BASALT)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.MOSS_CARPET)
                || state.is(Blocks.SCULK)
                || state.is(Blocks.SCULK_VEIN)
                || state.getBlock() instanceof BushBlock;
    }

    /**
     * Small decorative blocks that are easy to place by accident and should
     * not mark a whole column as a building on their own.
     */
    static boolean isFlimsyBuildBlock(BlockState state) {
        var block = state.getBlock();
        return block instanceof TorchBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof BasePressurePlateBlock
                || block instanceof CarpetBlock
                || block instanceof SnowLayerBlock
                || block instanceof SignBlock
                || state.is(BlockTags.RAILS)
                || state.is(BlockTags.BUTTONS)
                || state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.BANNERS);
    }

    /** True if the block belongs to the given category's tags. */
    static boolean isInCategory(BlockState state, Category category) {
        for (TagKey<net.minecraft.world.level.block.Block> tag : category.tags()) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }
}

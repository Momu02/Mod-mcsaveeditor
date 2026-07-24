package com.mcsaveeditor.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.GlassBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ChunkTerrainMerger {
    private static final int DEEP_BLEND_DEPTH = 24;
    private static final int EDGE_BLEND_BLOCKS = 12;

    private ChunkTerrainMerger() {
    }

    static CompoundTag merge(ServerLevel sourceWorld, TerrainRegenerationSession session,
                             ChunkAccess generatedNew, ChunkPos chunkPos,
                             CompoundTag currentTag, Set<Long> selectedChunks, long targetSeed) {
        ChunkAccess current = ChunkSerializer.read(
                sourceWorld, sourceWorld.getPoiManager(), chunkPos, currentTag);

        int minY = sourceWorld.getMinBuildHeight();
        int maxY = sourceWorld.getMaxBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int blockX = chunkPos.getMinBlockX() + localX;
                int blockZ = chunkPos.getMinBlockZ() + localZ;
                double blend = edgeBlendFactor(blockX, blockZ, selectedChunks, targetSeed);
                int oldH = groundSurfaceY(current, blockX, blockZ, minY, maxY);
                int newH = groundSurfaceY(generatedNew, blockX, blockZ, minY, maxY);
                int targetH = (int) Math.round(lerp(oldH, newH, blend));

                for (int y = minY; y < maxY; y++) {
                    pos.set(blockX, y, blockZ);
                    BlockState currentState = current.getBlockState(pos);
                    BlockState newState = generatedNew.getBlockState(pos);

                    if (isArtificialBuildBlock(currentState) && canPreserve(currentState, generatedNew, pos)) {
                        if (currentState != newState) {
                            generatedNew.setBlockState(pos, currentState, false);
                            generatedNew.removeBlockEntity(pos);
                        }
                        continue;
                    }

                    if (blend >= 0.999D) {
                        continue;
                    }
                    if (blend <= 0.001D) {
                        if (currentState != newState) {
                            generatedNew.setBlockState(pos, currentState, false);
                            generatedNew.removeBlockEntity(pos);
                        }
                        continue;
                    }

                    BlockState blended = blendBlockState(
                            current, generatedNew, samplePos, blockX, y, blockZ,
                            oldH, newH, targetH, blend, targetSeed, minY, maxY);
                    if (blended != newState) {
                        generatedNew.setBlockState(pos, blended, false);
                        generatedNew.removeBlockEntity(pos);
                    }
                }
            }
        }

        mergeBiomes(current, generatedNew, chunkPos, selectedChunks, targetSeed);
        mergeBlockEntities(currentTag, current, generatedNew, selectedChunks, targetSeed);

        CompoundTag tag = ChunkSerializer.write(session.genWorld(), generatedNew);
        if (currentTag != null) {
            copyIfPresent(currentTag, tag, "InhabitedTime");
            copyIfPresent(currentTag, tag, "LastUpdate");
            copyIfPresent(currentTag, tag, "block_ticks");
            copyIfPresent(currentTag, tag, "fluid_ticks");
            copyIfPresent(currentTag, tag, "ForgeCaps");
        }
        return tag;
    }

    private static boolean isArtificialBuildBlock(BlockState state) {
        if (state.isAir() || isFluid(state) || isNaturalTerrainBlock(state)) {
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
                || block instanceof GlassBlock
                || block instanceof StainedGlassBlock
                || block instanceof StainedGlassPaneBlock
                || block instanceof IronBarsBlock
                || block instanceof LadderBlock
                || block instanceof LanternBlock
                || block instanceof ChainBlock
                || block instanceof SignBlock
                || block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof BasePressurePlateBlock
                || block instanceof TorchBlock
                || block instanceof CarpetBlock
                || block instanceof BaseEntityBlock) {
            return true;
        }
        if (state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOOL)
                || state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.BEDS)
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
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.CRACKED_STONE_BRICKS)
                || state.is(Blocks.CHISELED_STONE_BRICKS)
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
                || state.is(Blocks.GLASS)
                || state.is(Blocks.TINTED_GLASS)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.FARMLAND)
                || state.is(Blocks.HAY_BLOCK)
                || isConcrete(state)
                || isGlazedTerracotta(state)
                || isPolishedStonework(state);
    }

    private static boolean isNaturalTerrainBlock(BlockState state) {
        if (state.isAir() || isFluid(state)) {
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

    private static boolean isConcrete(BlockState state) {
        return state.is(Blocks.WHITE_CONCRETE) || state.is(Blocks.ORANGE_CONCRETE)
                || state.is(Blocks.MAGENTA_CONCRETE) || state.is(Blocks.LIGHT_BLUE_CONCRETE)
                || state.is(Blocks.YELLOW_CONCRETE) || state.is(Blocks.LIME_CONCRETE)
                || state.is(Blocks.PINK_CONCRETE) || state.is(Blocks.GRAY_CONCRETE)
                || state.is(Blocks.LIGHT_GRAY_CONCRETE) || state.is(Blocks.CYAN_CONCRETE)
                || state.is(Blocks.PURPLE_CONCRETE) || state.is(Blocks.BLUE_CONCRETE)
                || state.is(Blocks.BROWN_CONCRETE) || state.is(Blocks.GREEN_CONCRETE)
                || state.is(Blocks.RED_CONCRETE) || state.is(Blocks.BLACK_CONCRETE);
    }

    private static boolean isGlazedTerracotta(BlockState state) {
        return state.is(Blocks.WHITE_GLAZED_TERRACOTTA) || state.is(Blocks.ORANGE_GLAZED_TERRACOTTA)
                || state.is(Blocks.MAGENTA_GLAZED_TERRACOTTA) || state.is(Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA)
                || state.is(Blocks.YELLOW_GLAZED_TERRACOTTA) || state.is(Blocks.LIME_GLAZED_TERRACOTTA)
                || state.is(Blocks.PINK_GLAZED_TERRACOTTA) || state.is(Blocks.GRAY_GLAZED_TERRACOTTA)
                || state.is(Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA) || state.is(Blocks.CYAN_GLAZED_TERRACOTTA)
                || state.is(Blocks.PURPLE_GLAZED_TERRACOTTA) || state.is(Blocks.BLUE_GLAZED_TERRACOTTA)
                || state.is(Blocks.BROWN_GLAZED_TERRACOTTA) || state.is(Blocks.GREEN_GLAZED_TERRACOTTA)
                || state.is(Blocks.RED_GLAZED_TERRACOTTA) || state.is(Blocks.BLACK_GLAZED_TERRACOTTA);
    }

    private static boolean isPolishedStonework(BlockState state) {
        return state.is(Blocks.POLISHED_ANDESITE) || state.is(Blocks.POLISHED_DIORITE)
                || state.is(Blocks.POLISHED_GRANITE) || state.is(Blocks.POLISHED_DEEPSLATE)
                || state.is(Blocks.POLISHED_BLACKSTONE) || state.is(Blocks.POLISHED_BLACKSTONE_BRICKS)
                || state.is(Blocks.POLISHED_BASALT) || state.is(Blocks.CUT_SANDSTONE)
                || state.is(Blocks.CUT_RED_SANDSTONE) || state.is(Blocks.QUARTZ_BLOCK)
                || state.is(Blocks.QUARTZ_BRICKS) || state.is(Blocks.QUARTZ_PILLAR)
                || state.is(Blocks.PURPUR_BLOCK) || state.is(Blocks.PURPUR_PILLAR)
                || state.is(Blocks.PRISMARINE_BRICKS) || state.is(Blocks.DARK_PRISMARINE)
                || state.is(Blocks.SEA_LANTERN) || state.is(Blocks.DEEPSLATE_BRICKS)
                || state.is(Blocks.DEEPSLATE_TILES) || state.is(Blocks.MUD_BRICKS)
                || state.is(Blocks.NETHER_BRICKS) || state.is(Blocks.RED_NETHER_BRICKS)
                || state.is(Blocks.SMOOTH_SANDSTONE) || state.is(Blocks.SMOOTH_RED_SANDSTONE)
                || state.is(Blocks.SMOOTH_QUARTZ);
    }

    private static double edgeBlendFactor(int blockX, int blockZ, Set<Long> selectedChunks, long seed) {
        int dist = distanceToOutside(blockX, blockZ, selectedChunks, EDGE_BLEND_BLOCKS);
        if (dist >= EDGE_BLEND_BLOCKS) {
            return 1.0D;
        }
        double linear = (double) dist / (double) EDGE_BLEND_BLOCKS;
        double smooth = linear * linear * (3.0D - 2.0D * linear);
        double jitter = (smoothNoise(blockX, blockZ, seed) - 0.5D) * 0.16D;
        return clamp(smooth + jitter, 0.0D, 1.0D);
    }

    private static int distanceToOutside(int blockX, int blockZ, Set<Long> selectedChunks, int maxDist) {
        int min = maxDist;
        for (int dx = -maxDist; dx <= maxDist; dx++) {
            for (int dz = -maxDist; dz <= maxDist; dz++) {
                int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
                if (chebyshev >= min) {
                    continue;
                }
                long key = ChunkPos.asLong((blockX + dx) >> 4, (blockZ + dz) >> 4);
                if (!selectedChunks.contains(key)) {
                    min = chebyshev;
                    if (min == 0) {
                        return 0;
                    }
                }
            }
        }
        return min;
    }

    private static BlockState blendBlockState(ChunkAccess oldTerrain, ChunkAccess newTerrain,
                                              BlockPos.MutableBlockPos samplePos,
                                              int x, int y, int z,
                                              int oldH, int newH, int targetH,
                                              double blend, long seed, int minY, int maxY) {
        int depth = targetH - y;
        if (depth < 0) {
            samplePos.set(x, y, z);
            BlockState oldAbs = oldTerrain.getBlockState(samplePos);
            BlockState newAbs = newTerrain.getBlockState(samplePos);
            boolean oldFluid = isFluid(oldAbs);
            boolean newFluid = isFluid(newAbs);
            if (blend >= 0.5D) {
                if (newFluid) {
                    return newAbs;
                }
                if (oldFluid) {
                    return oldAbs;
                }
                return Blocks.AIR.defaultBlockState();
            }
            if (oldFluid) {
                return oldAbs;
            }
            if (newFluid) {
                return newAbs;
            }
            return Blocks.AIR.defaultBlockState();
        }

        BlockState fromOld;
        BlockState fromNew;
        if (depth > DEEP_BLEND_DEPTH) {
            samplePos.set(x, y, z);
            fromOld = oldTerrain.getBlockState(samplePos);
            fromNew = newTerrain.getBlockState(samplePos);
        } else {
            int oldY = clamp(oldH - depth, minY, maxY - 1);
            int newY = clamp(newH - depth, minY, maxY - 1);
            samplePos.set(x, oldY, z);
            fromOld = oldTerrain.getBlockState(samplePos);
            samplePos.set(x, newY, z);
            fromNew = newTerrain.getBlockState(samplePos);
        }

        double pick = clamp(blend + (smoothNoise(x, z, seed ^ 0x9E3779B97F4A7C15L) - 0.5D) * 0.18D, 0.0D, 1.0D);
        if (pick >= 0.5D) {
            if (fromNew.isAir() && !fromOld.isAir() && depth > 0) {
                return fromOld;
            }
            return fromNew;
        }
        if (fromOld.isAir() && !fromNew.isAir() && depth > 0) {
            return fromNew;
        }
        return fromOld;
    }

    private static int groundSurfaceY(ChunkAccess chunk, int blockX, int blockZ, int minY, int maxY) {
        int localX = blockX & 15;
        int localZ = blockZ & 15;
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ) - 1;
        if (top >= minY && top < maxY) {
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(blockX, top, blockZ);
            BlockState state = chunk.getBlockState(probe);
            if (!state.isAir() && !isFluid(state) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) {
                return top;
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, 0, blockZ);
        for (int y = maxY - 1; y >= minY; y--) {
            pos.setY(y);
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir() || isFluid(state) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                continue;
            }
            return y;
        }
        return minY;
    }

    private static boolean isFluid(BlockState state) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void copyIfPresent(CompoundTag source, CompoundTag target, String key) {
        if (source.contains(key)) {
            target.put(key, source.get(key).copy());
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeBiomes(ChunkAccess current, ChunkAccess generatedNew,
                                    ChunkPos chunkPos, Set<Long> selectedChunks, long seed) {
        LevelChunkSection[] oldSections = current.getSections();
        LevelChunkSection[] newSections = generatedNew.getSections();
        for (int sectionIndex = 0; sectionIndex < Math.min(newSections.length, newSections.length); sectionIndex++) {
            if (newSections[sectionIndex] == null) {
                continue;
            }
            PalettedContainer<Holder<Biome>> target =
                    (PalettedContainer<Holder<Biome>>) newSections[sectionIndex].getBiomes();
            for (int quartX = 0; quartX < 4; quartX++) {
                for (int quartZ = 0; quartZ < 4; quartZ++) {
                    int blockX = chunkPos.getMinBlockX() + quartX * 4 + 2;
                    int blockZ = chunkPos.getMinBlockZ() + quartZ * 4 + 2;
                    boolean useOld = edgeBlendFactor(blockX, blockZ, selectedChunks, seed) < 0.5D
                            && sectionIndex < oldSections.length && oldSections[sectionIndex] != null;
                    for (int quartY = 0; quartY < 4; quartY++) {
                        Holder<Biome> biome = useOld
                                ? oldSections[sectionIndex].getNoiseBiome(quartX, quartY, quartZ)
                                : target.get(quartX, quartY, quartZ);
                        target.getAndSetUnchecked(quartX, quartY, quartZ, biome);
                    }
                }
            }
        }
    }

    private static double smoothNoise(int blockX, int blockZ, long seed) {
        int cellX = Math.floorDiv(blockX, 8);
        int cellZ = Math.floorDiv(blockZ, 8);
        double localX = Math.floorMod(blockX, 8) / 8.0D;
        double localZ = Math.floorMod(blockZ, 8) / 8.0D;
        localX = localX * localX * (3.0D - 2.0D * localX);
        localZ = localZ * localZ * (3.0D - 2.0D * localZ);
        double north = lerp(latticeNoise(cellX, cellZ, seed), latticeNoise(cellX + 1, cellZ, seed), localX);
        double south = lerp(latticeNoise(cellX, cellZ + 1, seed), latticeNoise(cellX + 1, cellZ + 1, seed), localX);
        return lerp(north, south, localZ);
    }

    private static double latticeNoise(int x, int z, long seed) {
        long value = seed ^ (long) x * 341873128712L ^ (long) z * 132897987541L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static double lerp(double first, double second, double amount) {
        return first + (second - first) * amount;
    }

    private static boolean canPreserve(BlockState state, ChunkAccess terrain, BlockPos pos) {
        var block = state.getBlock();
        BlockPos belowPos = pos.below();
        BlockState below = terrain.getBlockState(belowPos);
        if (block instanceof DoublePlantBlock doublePlant) {
            if (state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
                return below.is(doublePlant) && below.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER;
            }
            return canHostPlant(below);
        }
        if (block instanceof BushBlock) {
            return canHostPlant(below);
        }
        if (block instanceof SnowLayerBlock || block instanceof CarpetBlock) {
            return isSolidGround(below, terrain, belowPos);
        }
        if (block instanceof TorchBlock && !(block instanceof WallTorchBlock)) {
            return isSolidGround(below, terrain, belowPos)
                    || below.is(BlockTags.FENCES)
                    || below.is(BlockTags.WALLS);
        }
        if (block instanceof DoorBlock) {
            return isSolidGround(below, terrain, belowPos);
        }
        if (block instanceof SugarCaneBlock) {
            return below.is(BlockTags.DIRT) || below.is(BlockTags.SAND) || below.is(block);
        }
        if (block instanceof CactusBlock) {
            return below.is(BlockTags.SAND) || below.is(block);
        }
        if (block instanceof ChorusFlowerBlock) {
            return below.is(Blocks.END_STONE) || below.is(Blocks.CHORUS_PLANT);
        }
        return true;
    }

    private static boolean canHostPlant(BlockState below) {
        return below.is(BlockTags.DIRT)
                || below.is(Blocks.FARMLAND)
                || below.is(BlockTags.NYLIUM)
                || below.is(Blocks.SOUL_SAND)
                || below.is(Blocks.SOUL_SOIL)
                || below.is(Blocks.MOSS_BLOCK);
    }

    private static boolean isSolidGround(BlockState below, ChunkAccess terrain, BlockPos pos) {
        return below.isSolidRender(terrain, pos) || below.is(BlockTags.LEAVES);
    }

    private static void mergeBlockEntities(CompoundTag currentTag, ChunkAccess current, ChunkAccess generatedNew,
                                           Set<Long> selectedChunks, long seed) {
        Map<BlockPos, CompoundTag> currentBes = readBlockEntities(currentTag);

        for (BlockPos placePos : new HashSet<>(getBlockEntityMap(generatedNew).keySet())) {
            generatedNew.removeBlockEntity(placePos);
        }

        for (Map.Entry<BlockPos, CompoundTag> entry : currentBes.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = generatedNew.getBlockState(pos);
            if (isArtificialBuildBlock(state) && canLoadBlockEntity(entry.getValue(), state)) {
                BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, entry.getValue());
                if (blockEntity != null) {
                    generatedNew.setBlockEntity(blockEntity);
                }
            }
        }

        Map<BlockPos, CompoundTag> generatedBes = saveBlockEntities(generatedNew);
        for (Map.Entry<BlockPos, CompoundTag> entry : generatedBes.entrySet()) {
            BlockPos pos = entry.getKey();
            if (getBlockEntityMap(generatedNew).containsKey(pos)) {
                continue;
            }
            if (edgeBlendFactor(pos.getX(), pos.getZ(), selectedChunks, seed) < 0.5D) {
                continue;
            }
            BlockState state = generatedNew.getBlockState(pos);
            if (canLoadBlockEntity(entry.getValue(), state)) {
                BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, entry.getValue());
                if (blockEntity != null) {
                    generatedNew.setBlockEntity(blockEntity);
                }
            }
        }
    }

    private static boolean canLoadBlockEntity(CompoundTag tag, BlockState blockState) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("id"));
        if (id == null) {
            return false;
        }
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).orElse(null);
        return type != null && type.isValid(blockState);
    }

    private static Map<BlockPos, CompoundTag> readBlockEntities(CompoundTag chunkTag) {
        Map<BlockPos, CompoundTag> result = new HashMap<>();
        ListTag list = chunkTag.getList("block_entities", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i).copy();
            BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            result.put(pos, tag);
        }
        return result;
    }

    private static Map<BlockPos, CompoundTag> saveBlockEntities(ChunkAccess chunk) {
        Map<BlockPos, CompoundTag> result = new HashMap<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : getBlockEntityMap(chunk).entrySet()) {
            result.put(entry.getKey(), entry.getValue().saveWithFullMetadata());
        }
        return result;
    }

    private static Map<BlockPos, BlockEntity> getBlockEntityMap(ChunkAccess chunk) {
        if (chunk instanceof LevelChunk lc) {
            return lc.getBlockEntities();
        }
        if (chunk instanceof ProtoChunk pc) {
            return pc.getBlockEntities();
        }
        return Map.of();
    }
}

package com.mcsaveeditor.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TorchBlock;
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
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ChunkTerrainMerger {
    private static final int DEEP_BLEND_DEPTH = 24;
    private static final int BUILDING_HALO_BLOCKS = 4;
    static final int EDGE_BLEND_BLOCKS = 96;
    private static final int[][] SIX_NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private ChunkTerrainMerger() {
    }

    static CompoundTag merge(ServerLevel sourceWorld, ChunkAccess generatedNew, ChunkPos chunkPos,
                             CompoundTag currentTag, Set<Long> selectedChunks, long targetSeed, int blendBlocks,
                             ProtectionConfig config) {
        ChunkAccess current = ChunkSerializer.read(
                sourceWorld, sourceWorld.getPoiManager(), chunkPos, currentTag);

        int minY = sourceWorld.getMinBuildHeight();
        int maxY = sourceWorld.getMaxBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();

        boolean deepChunk = isDeepChunk(chunkPos, selectedChunks, blendBlocks);
        boolean[] buildingColumn = findBuildingColumns(current, chunkPos, minY, maxY, config);

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int blockX = chunkPos.getMinBlockX() + localX;
                int blockZ = chunkPos.getMinBlockZ() + localZ;
                double edge = deepChunk ? 1.0D
                        : edgeBlendFactor(blockX, blockZ, selectedChunks, targetSeed, blendBlocks);
                double blend = effectiveBlend(edge, buildingColumn, localX, localZ);
                int oldH = groundSurfaceY(current, blockX, blockZ, minY, maxY, config);
                int newH = groundSurfaceY(generatedNew, blockX, blockZ, minY, maxY, config);
                int refH = (int) Math.round(lerp(oldH, newH, blend));

                for (int y = minY; y < maxY; y++) {
                    pos.set(blockX, y, blockZ);
                    BlockState currentState = current.getBlockState(pos);
                    BlockState newState = generatedNew.getBlockState(pos);

                    if (isProtectedBlock(currentState, config) && canPreserve(currentState, generatedNew, pos)) {
                        if (currentState != newState) {
                            generatedNew.setBlockState(pos, currentState, false);
                            generatedNew.removeBlockEntity(pos);
                        }
                        continue;
                    }

                    if (!config.isExcluded(currentState) && isPlayerWood(currentState) && blend < 0.999D) {
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
                            refH, oldH, newH, blend, targetSeed, minY, maxY);
                    if (blended != newState) {
                        generatedNew.setBlockState(pos, blended, false);
                        generatedNew.removeBlockEntity(pos);
                    }
                }
            }
        }

        preserveTreeCanopies(current, generatedNew, chunkPos, selectedChunks, targetSeed, blendBlocks, buildingColumn, minY, maxY, config);

        mergeBiomes(current, generatedNew, chunkPos, selectedChunks, targetSeed, blendBlocks, buildingColumn, config);
        mergeBlockEntities(currentTag, current, generatedNew, selectedChunks, targetSeed, blendBlocks, buildingColumn, config);

        CompoundTag tag = ChunkSerializer.write(sourceWorld, generatedNew);
        if (currentTag != null) {
            copyIfPresent(currentTag, tag, "InhabitedTime");
            copyIfPresent(currentTag, tag, "LastUpdate");
            copyIfPresent(currentTag, tag, "block_ticks");
            copyIfPresent(currentTag, tag, "fluid_ticks");
            copyIfPresent(currentTag, tag, "ForgeCaps");
        }
        return tag;
    }

    private static boolean isProtectedBlock(BlockState state, ProtectionConfig config) {
        if (state.isAir() || isFluid(state) || config.isExcluded(state)) {
            return false;
        }
        for (ProtectionCategories.Category category : ProtectionCategories.CATEGORIES) {
            if (config.isEnabled(category) && ProtectionCategories.isInCategory(state, category)) {
                return true;
            }
        }
        return ProtectionCategories.isCoreBuildBlock(state);
    }

    private static boolean isPlayerWood(BlockState state) {
        return state.is(BlockTags.LOGS) && !state.is(BlockTags.OVERWORLD_NATURAL_LOGS);
    }

    private static double edgeBlendFactor(int blockX, int blockZ, Set<Long> selectedChunks, long seed, int blendBlocks) {
        int dist = distanceToOutside(blockX, blockZ, selectedChunks, blendBlocks);
        if (dist >= blendBlocks) {
            return 1.0D;
        }
        double linear = (double) dist / (double) blendBlocks;
        return linear * linear * (3.0D - 2.0D * linear);
    }

    private static boolean isDeepChunk(ChunkPos chunkPos, Set<Long> selectedChunks, int blendBlocks) {
        int blockX = chunkPos.getMinBlockX();
        int blockZ = chunkPos.getMinBlockZ();
        return distanceToOutside(blockX, blockZ, selectedChunks, blendBlocks) >= blendBlocks
                && distanceToOutside(blockX + 15, blockZ, selectedChunks, blendBlocks) >= blendBlocks
                && distanceToOutside(blockX, blockZ + 15, selectedChunks, blendBlocks) >= blendBlocks
                && distanceToOutside(blockX + 15, blockZ + 15, selectedChunks, blendBlocks) >= blendBlocks;
    }

    private static boolean[] findBuildingColumns(ChunkAccess current, ChunkPos chunkPos, int minY, int maxY,
                                                 ProtectionConfig config) {
        boolean[] buildingColumn = new boolean[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                pos.set(chunkPos.getMinBlockX() + localX, 0, chunkPos.getMinBlockZ() + localZ);
                boolean structural = false;
                boolean hasLog = false;
                boolean hasLeaf = false;
                int logCount = 0;
                for (int y = minY; y < maxY; y++) {
                    pos.setY(y);
                    BlockState state = current.getBlockState(pos);
                    if (isProtectedBlock(state, config) && !ProtectionCategories.isFlimsyBuildBlock(state)) {
                        structural = true;
                    }
                    if (state.is(BlockTags.LOGS)) {
                        hasLog = true;
                        logCount++;
                    } else if (state.is(BlockTags.LEAVES)) {
                        hasLeaf = true;
                    }
                }
                buildingColumn[localX * 16 + localZ] = structural || (hasLog && !hasLeaf && logCount >= 2);
            }
        }
        caveBuildingColumns(current, chunkPos, minY, maxY, config, buildingColumn);
        return buildingColumn;
    }

    /**
     * Marks whole columns as preserved when protected blocks form an
     * underground connected domain of at least two blocks (a cave dwelling):
     * the surrounding stone walls and the terrain above are kept intact so the
     * rebuilt terrain does not carve into the cave.
     */
    private static void caveBuildingColumns(ChunkAccess current, ChunkPos chunkPos, int minY, int maxY,
                                            ProtectionConfig config, boolean[] buildingColumn) {
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        Set<Long> unvisited = new HashSet<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                if (buildingColumn[localX * 16 + localZ]) {
                    continue;
                }
                for (int y = minY; y < maxY; y++) {
                    pos.set(minBlockX + localX, y, minBlockZ + localZ);
                    if (isProtectedBlock(current.getBlockState(pos), config)) {
                        unvisited.add(pos.asLong());
                    }
                }
            }
        }
        ArrayDeque<BlockPos.MutableBlockPos> stack = new ArrayDeque<>();
        while (!unvisited.isEmpty()) {
            long seed = unvisited.iterator().next();
            unvisited.remove(seed);
            Set<Long> domain = new HashSet<>();
            domain.add(seed);
            stack.push(new BlockPos.MutableBlockPos(BlockPos.getX(seed), BlockPos.getY(seed), BlockPos.getZ(seed)));
            while (!stack.isEmpty()) {
                BlockPos.MutableBlockPos node = stack.pop();
                int nodeX = node.getX();
                int nodeY = node.getY();
                int nodeZ = node.getZ();
                for (int[] dir : SIX_NEIGHBORS) {
                    int neighborX = nodeX + dir[0];
                    int neighborY = nodeY + dir[1];
                    int neighborZ = nodeZ + dir[2];
                    if (neighborY < minY || neighborY >= maxY
                            || neighborX < minBlockX || neighborX >= minBlockX + 16
                            || neighborZ < minBlockZ || neighborZ >= minBlockZ + 16) {
                        continue;
                    }
                    long neighborKey = BlockPos.asLong(neighborX, neighborY, neighborZ);
                    if (unvisited.remove(neighborKey)) {
                        domain.add(neighborKey);
                        stack.push(new BlockPos.MutableBlockPos(neighborX, neighborY, neighborZ));
                    }
                }
            }
            if (domain.size() >= 2) {
                for (long member : domain) {
                    buildingColumn[(BlockPos.getX(member) & 15) * 16 + (BlockPos.getZ(member) & 15)] = true;
                }
            }
        }
    }

    private static double effectiveBlend(double edge, boolean[] buildingColumn, int localX, int localZ) {
        int index = localX * 16 + localZ;
        if (buildingColumn[index]) {
            return 0.0D;
        }
        int dist = buildingDistance(buildingColumn, localX, localZ);
        if (dist >= BUILDING_HALO_BLOCKS) {
            return edge;
        }
        double t = (double) dist / (double) BUILDING_HALO_BLOCKS;
        return edge * (t * t * (3.0D - 2.0D * t));
    }

    private static double effectiveBlend(int blockX, int blockZ, Set<Long> selectedChunks, long seed,
                                         int blendBlocks, boolean[] buildingColumn) {
        double edge = edgeBlendFactor(blockX, blockZ, selectedChunks, seed, blendBlocks);
        return effectiveBlend(edge, buildingColumn, blockX & 15, blockZ & 15);
    }

    private static int buildingDistance(boolean[] buildingColumn, int localX, int localZ) {
        int minDist = Integer.MAX_VALUE;
        for (int dx = -BUILDING_HALO_BLOCKS; dx <= BUILDING_HALO_BLOCKS; dx++) {
            int columnX = localX + dx;
            if (columnX < 0 || columnX > 15) {
                continue;
            }
            for (int dz = -BUILDING_HALO_BLOCKS; dz <= BUILDING_HALO_BLOCKS; dz++) {
                int columnZ = localZ + dz;
                if (columnZ < 0 || columnZ > 15) {
                    continue;
                }
                if (buildingColumn[columnX * 16 + columnZ]) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dz));
                    if (dist < minDist) {
                        minDist = dist;
                    }
                }
            }
        }
        return minDist == Integer.MAX_VALUE ? BUILDING_HALO_BLOCKS : minDist;
    }

    private static void preserveTreeCanopies(ChunkAccess current, ChunkAccess generatedNew, ChunkPos chunkPos,
                                             Set<Long> selectedChunks, long seed, int blendBlocks,
                                             boolean[] buildingColumn, int minY, int maxY, ProtectionConfig config) {
        boolean[] canopyZone = new boolean[256];
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int blockX = chunkPos.getMinBlockX() + localX;
                int blockZ = chunkPos.getMinBlockZ() + localZ;
                double edge = edgeBlendFactor(blockX, blockZ, selectedChunks, seed, blendBlocks);
                canopyZone[localX * 16 + localZ] = effectiveBlend(edge, buildingColumn, localX, localZ) < 0.999D;
            }
        }

        Set<Long> visited = new HashSet<>();
        ArrayDeque<BlockPos.MutableBlockPos> stack = new ArrayDeque<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                if (!canopyZone[localX * 16 + localZ]) {
                    continue;
                }
                for (int y = minY; y < maxY; y++) {
                    pos.set(minBlockX + localX, y, minBlockZ + localZ);
                    BlockState state = current.getBlockState(pos);
                    if (config.isExcluded(state) || !isPlayerWood(state)) {
                        continue;
                    }
                    long key = pos.asLong();
                    if (!visited.add(key)) {
                        continue;
                    }
                    stack.push(new BlockPos.MutableBlockPos().set(pos));
                    while (!stack.isEmpty()) {
                        BlockPos.MutableBlockPos node = stack.pop();
                        BlockState nodeState = current.getBlockState(node);
                        if (!nodeState.is(BlockTags.LOGS) && !nodeState.is(BlockTags.LEAVES)) {
                            continue;
                        }
                        BlockState newState = generatedNew.getBlockState(node);
                        if (nodeState != newState) {
                            generatedNew.setBlockState(node, nodeState, false);
                            generatedNew.removeBlockEntity(node);
                        }
                        int nodeX = node.getX();
                        int nodeY = node.getY();
                        int nodeZ = node.getZ();
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                    if (dx == 0 && dy == 0 && dz == 0) {
                                        continue;
                                    }
                                    int neighborX = nodeX + dx;
                                    int neighborY = nodeY + dy;
                                    int neighborZ = nodeZ + dz;
                                    if (neighborY < minY || neighborY >= maxY) {
                                        continue;
                                    }
                                    if (neighborX < minBlockX || neighborX >= minBlockX + 16
                                            || neighborZ < minBlockZ || neighborZ >= minBlockZ + 16) {
                                        continue;
                                    }
                                    if (!canopyZone[(neighborX & 15) * 16 + (neighborZ & 15)]) {
                                        continue;
                                    }
                                    BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos(
                                            neighborX, neighborY, neighborZ);
                                    if (visited.add(neighbor.asLong())) {
                                        stack.push(neighbor);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static int distanceToOutside(int blockX, int blockZ, Set<Long> selectedChunks, int maxDist) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        int maxChunks = (maxDist + 15) >> 4;
        for (int d = 0; d <= maxChunks; d++) {
            for (int dx = -d; dx <= d; dx++) {
                if (!selectedChunks.contains(ChunkPos.asLong(chunkX + dx, chunkZ - d))) {
                    return d * 16;
                }
                if (!selectedChunks.contains(ChunkPos.asLong(chunkX + dx, chunkZ + d))) {
                    return d * 16;
                }
            }
            for (int dz = -d + 1; dz <= d - 1; dz++) {
                if (!selectedChunks.contains(ChunkPos.asLong(chunkX - d, chunkZ + dz))) {
                    return d * 16;
                }
                if (!selectedChunks.contains(ChunkPos.asLong(chunkX + d, chunkZ + dz))) {
                    return d * 16;
                }
            }
        }
        return maxDist;
    }

    private static BlockState blendBlockState(ChunkAccess oldTerrain, ChunkAccess newTerrain,
                                              BlockPos.MutableBlockPos samplePos,
                                              int x, int y, int z,
                                              int refH, int oldH, int newH,
                                              double blend, long seed, int minY, int maxY) {
        int depth = refH - y;
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
            } else {
                if (oldFluid) {
                    return oldAbs;
                }
                if (newFluid) {
                    return newAbs;
                }
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

        double pick = materialPick(x, z, blend, seed);
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

    private static double materialPick(int blockX, int blockZ, double blend, long seed) {
        double jitter = (smoothNoise(blockX, blockZ, seed ^ 0x9E3779B97F4A7C15L) - 0.5D) * 0.12D;
        return clamp(blend + jitter, 0.0D, 1.0D);
    }

    private static int groundSurfaceY(ChunkAccess chunk, int blockX, int blockZ, int minY, int maxY,
                                      ProtectionConfig config) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, maxY - 1, blockZ);
        for (int y = maxY - 1; y >= minY; y--) {
            pos.setY(y);
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir() || isFluid(state) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                    || isProtectedBlock(state, config)) {
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
                                    ChunkPos chunkPos, Set<Long> selectedChunks, long seed, int blendBlocks,
                                    boolean[] buildingColumn, ProtectionConfig config) {
        LevelChunkSection[] oldSections = current.getSections();
        LevelChunkSection[] newSections = generatedNew.getSections();
        for (int sectionIndex = 0; sectionIndex < Math.min(oldSections.length, newSections.length); sectionIndex++) {
            if (newSections[sectionIndex] == null) {
                continue;
            }
            PalettedContainer<Holder<Biome>> target =
                    (PalettedContainer<Holder<Biome>>) newSections[sectionIndex].getBiomes();
            for (int quartX = 0; quartX < 4; quartX++) {
                for (int quartZ = 0; quartZ < 4; quartZ++) {
                    int blockX = chunkPos.getMinBlockX() + quartX * 4 + 2;
                    int blockZ = chunkPos.getMinBlockZ() + quartZ * 4 + 2;
                    boolean useOld = effectiveBlend(blockX, blockZ, selectedChunks, seed, blendBlocks, buildingColumn) < 0.5D
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
                                           Set<Long> selectedChunks, long seed, int blendBlocks,
                                           boolean[] buildingColumn, ProtectionConfig config) {
        Map<BlockPos, CompoundTag> currentBes = readBlockEntities(currentTag);

        for (BlockPos placePos : new HashSet<>(getBlockEntityMap(generatedNew).keySet())) {
            generatedNew.removeBlockEntity(placePos);
        }

        for (Map.Entry<BlockPos, CompoundTag> entry : currentBes.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = generatedNew.getBlockState(pos);
            if (isProtectedBlock(state, config) && canLoadBlockEntity(entry.getValue(), state)) {
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
            if (effectiveBlend(pos.getX(), pos.getZ(), selectedChunks, seed, blendBlocks, buildingColumn) < 0.5D) {
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

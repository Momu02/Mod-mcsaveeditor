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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ChunkTerrainMerger {
    private static final int DEEP_BLEND_DEPTH = 24;

    private ChunkTerrainMerger() {
    }

    static CompoundTag merge(ServerLevel sourceWorld, TerrainRegenerationSession session,
                             TerrainRegenerationSession.TerrainPair terrain, ChunkPos chunkPos,
                             @Nullable CompoundTag currentTag, int transitionDistance, int maxTransition,
                             long targetSeed) {
        ChunkAccess oldTerrain = terrain.oldTerrain();
        ChunkAccess newTerrain = terrain.newTerrain();
        ChunkAccess current = currentTag == null
                ? oldTerrain
                : ChunkSerializer.read(session.oldWorld(), session.oldWorld().getPoiManager(), chunkPos, currentTag);

        int minY = sourceWorld.getMinBuildHeight();
        int maxY = sourceWorld.getMaxBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int blockX = chunkPos.getMinBlockX() + localX;
                int blockZ = chunkPos.getMinBlockZ() + localZ;
                double blend = blendFactor(transitionDistance, maxTransition, blockX, blockZ, targetSeed);
                int oldH = groundSurfaceY(oldTerrain, blockX, blockZ, minY, maxY);
                int newH = groundSurfaceY(newTerrain, blockX, blockZ, minY, maxY);
                int targetH = (int) Math.round(lerp(oldH, newH, blend));

                for (int y = minY; y < maxY; y++) {
                    pos.set(blockX, y, blockZ);
                    BlockState currentState = current.getBlockState(pos);
                    BlockState oldState = oldTerrain.getBlockState(pos);
                    if (currentState != oldState) {
                        if (canPreserve(currentState, newTerrain, pos)) {
                            newTerrain.setBlockState(pos, currentState, false);
                        }
                        newTerrain.removeBlockEntity(pos);
                        continue;
                    }
                    if (blend >= 0.999D) {
                        continue;
                    }
                    BlockState blended = blendBlockState(
                            oldTerrain, newTerrain, samplePos, blockX, y, blockZ,
                            oldH, newH, targetH, blend, targetSeed, minY, maxY);
                    if (blended != newTerrain.getBlockState(pos)) {
                        newTerrain.setBlockState(pos, blended, false);
                        newTerrain.removeBlockEntity(pos);
                    }
                }
            }
        }

        mergeBiomes(oldTerrain, newTerrain, chunkPos, transitionDistance, maxTransition, targetSeed);
        mergeBlockEntities(currentTag, oldTerrain, newTerrain, transitionDistance, maxTransition, targetSeed);
        CompoundTag result = ChunkSerializer.write(session.newWorld(), newTerrain);
        if (currentTag != null) {
            copyIfPresent(currentTag, result, "InhabitedTime");
            copyIfPresent(currentTag, result, "LastUpdate");
            copyIfPresent(currentTag, result, "block_ticks");
            copyIfPresent(currentTag, result, "fluid_ticks");
            copyIfPresent(currentTag, result, "ForgeCaps");
        }
        return result;
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
                return newAbs;
            }
            if (oldFluid) {
                return oldAbs;
            }
            if (newFluid) {
                return newAbs;
            }
            return oldAbs;
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
            if (isAirLike(fromOld) && oldY < oldH && depth > 0) {
                samplePos.set(x, clamp(oldH - Math.max(depth, 1), minY, maxY - 1), z);
                BlockState deeper = oldTerrain.getBlockState(samplePos);
                if (!isAirLike(deeper)) {
                    fromOld = deeper;
                }
            }
            if (isAirLike(fromNew) && newY < newH && depth > 0) {
                samplePos.set(x, clamp(newH - Math.max(depth, 1), minY, maxY - 1), z);
                BlockState deeper = newTerrain.getBlockState(samplePos);
                if (!isAirLike(deeper)) {
                    fromNew = deeper;
                }
            }
        }

        double pick = clamp(blend + (smoothNoise(x, z, seed ^ 0x9E3779B97F4A7C15L) - 0.5D) * 0.18D, 0.0D, 1.0D);
        if (pick >= 0.5D) {
            if (isAirLike(fromNew) && !isAirLike(fromOld) && depth > 0) {
                return fromOld;
            }
            return fromNew;
        }
        if (isAirLike(fromOld) && !isAirLike(fromNew) && depth > 0) {
            return fromNew;
        }
        return fromOld;
    }

    private static double blendFactor(int distance, int maxTransition, int blockX, int blockZ, long seed) {
        if (maxTransition <= 0 || distance <= 0) {
            return 1.0D;
        }
        if (distance >= maxTransition) {
            return clamp(smoothNoise(blockX, blockZ, seed) * 0.12D, 0.0D, 0.15D);
        }
        double linear = 1.0D - (double) distance / (double) maxTransition;
        double smooth = linear * linear * (3.0D - 2.0D * linear);
        double jitter = (smoothNoise(blockX, blockZ, seed) - 0.5D) * 0.22D;
        return clamp(smooth + jitter, 0.0D, 1.0D);
    }

    private static int groundSurfaceY(ChunkAccess chunk, int blockX, int blockZ, int minY, int maxY) {
        int localX = blockX & 15;
        int localZ = blockZ & 15;
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ) - 1;
        if (top >= minY && top < maxY) {
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(blockX, top, blockZ);
            BlockState state = chunk.getBlockState(probe);
            if (!isAirLike(state) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) {
                return top;
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, 0, blockZ);
        for (int y = maxY - 1; y >= minY; y--) {
            pos.setY(y);
            BlockState state = chunk.getBlockState(pos);
            if (isAirLike(state) || isFluid(state) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                continue;
            }
            return y;
        }
        return minY;
    }

    private static boolean isAirLike(BlockState state) {
        return state.isAir();
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
    private static void mergeBiomes(ChunkAccess oldTerrain, ChunkAccess newTerrain, ChunkPos chunkPos,
                                    int transitionDistance, int maxTransition, long seed) {
        if (transitionDistance == 0) {
            return;
        }
        LevelChunkSection[] oldSections = oldTerrain.getSections();
        LevelChunkSection[] newSections = newTerrain.getSections();
        for (int sectionIndex = 0; sectionIndex < Math.min(oldSections.length, newSections.length); sectionIndex++) {
            PalettedContainer<Holder<Biome>> target =
                    (PalettedContainer<Holder<Biome>>) newSections[sectionIndex].getBiomes();
            for (int quartX = 0; quartX < 4; quartX++) {
                for (int quartZ = 0; quartZ < 4; quartZ++) {
                    int blockX = chunkPos.getMinBlockX() + quartX * 4 + 2;
                    int blockZ = chunkPos.getMinBlockZ() + quartZ * 4 + 2;
                    if (blendFactor(transitionDistance, maxTransition, blockX, blockZ, seed) >= 0.5D) {
                        continue;
                    }
                    for (int quartY = 0; quartY < 4; quartY++) {
                        target.getAndSetUnchecked(quartX, quartY, quartZ,
                                oldSections[sectionIndex].getNoiseBiome(quartX, quartY, quartZ));
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
            return below.is(net.minecraft.world.level.block.Blocks.END_STONE)
                    || below.is(net.minecraft.world.level.block.Blocks.CHORUS_PLANT);
        }
        return true;
    }

    private static boolean canHostPlant(BlockState below) {
        return below.is(BlockTags.DIRT)
                || below.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                || below.is(BlockTags.NYLIUM)
                || below.is(net.minecraft.world.level.block.Blocks.SOUL_SAND)
                || below.is(net.minecraft.world.level.block.Blocks.SOUL_SOIL)
                || below.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK);
    }

    private static boolean isSolidGround(BlockState below, ChunkAccess terrain, BlockPos pos) {
        return below.isSolidRender(terrain, pos) || below.is(BlockTags.LEAVES);
    }

    private static void mergeBlockEntities(@Nullable CompoundTag currentTag, ChunkAccess oldTerrain,
                                           ChunkAccess newTerrain, int transitionDistance, int maxTransition,
                                           long seed) {
        Map<BlockPos, CompoundTag> current = currentTag == null
                ? saveBlockEntities(oldTerrain) : readBlockEntities(currentTag);
        Map<BlockPos, CompoundTag> old = saveBlockEntities(oldTerrain);
        Map<BlockPos, CompoundTag> generatedNew = saveBlockEntities(newTerrain);
        Set<BlockPos> positions = new HashSet<>(current.keySet());
        positions.addAll(old.keySet());
        positions.addAll(generatedNew.keySet());

        for (BlockPos pos : new HashSet<>(getBlockEntityMap(newTerrain).keySet())) {
            newTerrain.removeBlockEntity(pos);
        }

        for (BlockPos pos : positions) {
            CompoundTag currentNbt = current.get(pos);
            CompoundTag oldNbt = old.get(pos);
            boolean playerModified = !java.util.Objects.equals(currentNbt, oldNbt);
            CompoundTag desired;
            if (playerModified) {
                desired = currentNbt;
            } else {
                double blend = blendFactor(transitionDistance, maxTransition, pos.getX(), pos.getZ(), seed);
                desired = blend >= 0.5D ? generatedNew.get(pos) : oldNbt;
            }
            if (desired == null) {
                continue;
            }
            BlockState blockState = newTerrain.getBlockState(pos);
            if (!canLoadBlockEntity(desired, blockState)) {
                continue;
            }
            BlockEntity blockEntity = BlockEntity.loadStatic(pos, blockState, desired);
            if (blockEntity != null) {
                newTerrain.setBlockEntity(blockEntity);
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

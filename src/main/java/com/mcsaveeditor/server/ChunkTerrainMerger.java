package com.mcsaveeditor.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ChunkTerrainMerger {
    private ChunkTerrainMerger() {
    }

    static CompoundTag merge(ServerLevel sourceWorld, TerrainRegenerationSession session,
                             TerrainRegenerationSession.TerrainPair terrain, ChunkPos chunkPos,
                             @Nullable CompoundTag currentTag, int transitionDistance, long targetSeed) {
        ChunkAccess oldTerrain = terrain.oldTerrain();
        ChunkAccess newTerrain = terrain.newTerrain();
        ChunkAccess current = currentTag == null
                ? oldTerrain
                : ChunkSerializer.read(session.oldWorld(), session.oldWorld().getPoiManager(), chunkPos, currentTag);

        int minY = sourceWorld.getMinBuildHeight();
        int maxY = sourceWorld.getMaxBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int blockX = chunkPos.getMinBlockX() + localX;
                int blockZ = chunkPos.getMinBlockZ() + localZ;
                boolean useNewTerrain = transitionDistance == 0
                        || transitionColumn(blockX, blockZ, transitionDistance, targetSeed);
                for (int y = minY; y < maxY; y++) {
                    pos.set(blockX, y, blockZ);
                    BlockState currentState = current.getBlockState(pos);
                    BlockState oldState = oldTerrain.getBlockState(pos);
                    if (currentState != oldState) {
                        if (canPreserve(currentState, newTerrain, pos)) {
                            newTerrain.setBlockState(pos, currentState, false);
                        }
                        newTerrain.removeBlockEntity(pos);
                    } else if (!useNewTerrain) {
                        newTerrain.setBlockState(pos, oldState, false);
                        newTerrain.removeBlockEntity(pos);
                    }
                }
            }
        }

        mergeBiomes(oldTerrain, newTerrain, chunkPos, transitionDistance, targetSeed);
        mergeBlockEntities(currentTag, oldTerrain, newTerrain, transitionDistance, targetSeed);
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

    private static void copyIfPresent(CompoundTag source, CompoundTag target, String key) {
        if (source.contains(key)) {
            target.put(key, source.get(key).copy());
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeBiomes(ChunkAccess oldTerrain, ChunkAccess newTerrain, ChunkPos chunkPos,
                                    int transitionDistance, long seed) {
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
                    if (transitionColumn(blockX, blockZ, transitionDistance, seed)) {
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

    private static boolean transitionColumn(int blockX, int blockZ, int distance, long seed) {
        if (distance <= 1) {
            return true;
        }
        if (distance >= 8) {
            return false;
        }
        double threshold = (8.0D - distance) / 7.0D;
        return smoothNoise(blockX, blockZ, seed) < threshold;
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
                                           ChunkAccess newTerrain, int transitionDistance, long seed) {
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
                boolean useNew = transitionDistance == 0
                        || transitionColumn(pos.getX(), pos.getZ(), transitionDistance, seed);
                desired = useNew ? generatedNew.get(pos) : oldNbt;
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

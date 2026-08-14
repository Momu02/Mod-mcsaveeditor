package com.mcsaveeditor.server;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

final class TerrainGeneration {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TerrainGeneration() {
    }

    /**
     * Generates a fresh chunk (biomes + noise + surface + decorations) for the given seed.
     * When {@code biomeId} is a valid registry id, every block column of the
     * chunk is forced to that biome before the surface is built, so the
     * surface blocks (sand, snow, …) and decorations (trees, vegetation, ores)
     * match the chosen biome.
     */
    static ChunkAccess generate(ServerLevel level, ChunkPos pos, long seed, String biomeId) {
        var generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            throw new IllegalStateException("不支持的地形生成器: " + generator.getClass().getName());
        }
        Holder<NoiseGeneratorSettings> settings = noiseGenerator.generatorSettings();
        var noiseLookup = level.registryAccess().lookupOrThrow(Registries.NOISE);
        // 群系位置始终使用世界种子，保证与原世界一致；只有地形细节使用目标种子。
        RandomState biomeRandomState = RandomState.create(settings.value(), noiseLookup, level.getSeed());
        RandomState terrainRandomState = RandomState.create(settings.value(), noiseLookup, seed);
        StructureManager structures = level.structureManager();
        Executor executor = Util.backgroundExecutor();

        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        chunk.setLightEngine(level.getLightEngine());

        generator.createBiomes(executor, biomeRandomState, Blender.empty(), structures, chunk).join();
        chunk.setStatus(ChunkStatus.BIOMES);
        // 在噪声生成前覆盖群系：密度函数按目标群系的深度/尺度参数生成地形，
        // 避免强制樱花林/沼泽时仍按原群系（如海洋）生成水下地形。
        overrideBiomes(chunk, level, biomeId);
        generator.fillFromNoise(executor, Blender.empty(), terrainRandomState, structures, chunk).join();
        chunk.setStatus(ChunkStatus.NOISE);
        raiseIfBelowSea(chunk, level);

        List<ChunkAccess> chunks = new ArrayList<>(9);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    chunks.add(chunk);
                } else {
                    chunks.add(decorateNeighbor(level, new ChunkPos(pos.x + dx, pos.z + dz)));
                }
            }
        }
        WorldGenRegion region = new WorldGenRegion(level, chunks, ChunkStatus.SURFACE, 1);
        generator.buildSurface(region, structures, terrainRandomState, chunk);
        chunk.setStatus(ChunkStatus.SURFACE);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        chunk.setStatus(ChunkStatus.FULL);
        decorate(level, generator, structures, chunk);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        return chunk;
    }

    /**
     * 陆地群系的地形如果远低于海平面（例如随机种子在深海位置生成了
     * 樱花林/沼泽等陆地群系），把该列垫高到海平面，避免整个区域被淹。
     * 浅水（海平面下 4 格以内）保留原样，沼泽的浅水特性不受影响。
     */
    private static void raiseIfBelowSea(ChunkAccess chunk, ServerLevel level) {
        int seaLevel = level.getSeaLevel();
        int minY = level.getMinBuildHeight();
        int sectionIndex = chunk.getSectionIndex(seaLevel);
        LevelChunkSection seaSection = chunk.getSection(sectionIndex);
        if (seaSection == null) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                Holder<Biome> biome = seaSection.getNoiseBiome(localX >> 2, 1, localZ >> 2);
                if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) {
                    continue;
                }
                int top = minY - 1;
                for (int y = seaLevel; y >= minY; y--) {
                    pos.set(localX, y, localZ);
                    if (!chunk.getBlockState(pos).isAir()) {
                        top = y;
                        break;
                    }
                }
                if (top < seaLevel - 4) {
                    for (int y = top + 1; y <= seaLevel; y++) {
                        pos.setY(y);
                        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private static void decorate(ServerLevel level, ChunkGenerator generator,
                                 StructureManager structures, ChunkAccess center) {
        ChunkPos pos = center.getPos();
        List<ChunkAccess> chunks = new ArrayList<>(17 * 17);
        for (int dz = -8; dz <= 8; dz++) {
            for (int dx = -8; dx <= 8; dx++) {
                ChunkPos neighborPos = new ChunkPos(pos.x + dx, pos.z + dz);
                if (dx == 0 && dz == 0) {
                    chunks.add(center);
                } else if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    chunks.add(decorateNeighbor(level, neighborPos));
                } else {
                    chunks.add(decoratePlaceholder(level, neighborPos));
                }
            }
        }
        try {
            WorldGenRegion region = new WorldGenRegion(level, chunks, ChunkStatus.FULL, 8);
            generator.applyBiomeDecoration(region, center, structures);
        } catch (Exception exception) {
            LOGGER.error("MC 存档区块编辑器装饰区块 {} 失败（跳过装饰，仅保留地形）", pos, exception);
        }
    }

    private static ChunkAccess decorateNeighbor(ServerLevel level, ChunkPos neighborPos) {
        try {
            if (AnvilChunkIo.regionFileExists(WorldStoragePaths.chunkRegion(level), neighborPos)) {
                CompoundTag tag = level.getChunkSource().chunkMap.read(neighborPos).join().orElse(null);
                if (tag != null && !tag.isEmpty()) {
                    ChunkAccess neighbor = ChunkSerializer.read(level, level.getPoiManager(), neighborPos, tag);
                    if (neighbor instanceof ProtoChunk proto) {
                        proto.setLightEngine(level.getLightEngine());
                    }
                    return neighbor;
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("MC 存档区块编辑器读取装饰邻居区块 {} 失败，使用空区块", neighborPos, exception);
        }
        return decoratePlaceholder(level, neighborPos);
    }

    private static ChunkAccess decoratePlaceholder(ServerLevel level, ChunkPos neighborPos) {
        ProtoChunk placeholder = new ProtoChunk(neighborPos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        placeholder.setStatus(ChunkStatus.FULL);
        placeholder.setLightEngine(level.getLightEngine());
        fillDefaultBiomes(placeholder, level);
        return placeholder;
    }

    @SuppressWarnings("unchecked")
    private static void fillDefaultBiomes(ChunkAccess chunk, ServerLevel level) {
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> fallback = registry.getHolderOrThrow(Biomes.PLAINS);
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null) {
                continue;
            }
            PalettedContainer<Holder<Biome>> biomes =
                    (PalettedContainer<Holder<Biome>>) section.getBiomes();
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        Holder<Biome> holder = biomes.get(x, y, z);
                        if (holder == null) {
                            biomes.getAndSetUnchecked(x, y, z, fallback);
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void overrideBiomes(ChunkAccess chunk, ServerLevel level, String biomeId) {
        if (biomeId == null || biomeId.isEmpty()) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(biomeId);
        if (id == null) {
            return;
        }
        Optional<Holder.Reference<Biome>> optional = level.registryAccess().registryOrThrow(Registries.BIOME)
                .getHolder(ResourceKey.create(Registries.BIOME, id));
        if (optional.isEmpty()) {
            return;
        }
        Holder.Reference<Biome> biome = optional.get();
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null) {
                continue;
            }
            PalettedContainer<Holder<Biome>> biomes =
                    (PalettedContainer<Holder<Biome>>) section.getBiomes();
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        biomes.getAndSetUnchecked(x, y, z, biome);
                    }
                }
            }
        }
    }
}

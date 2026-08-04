package com.mcsaveeditor.server;

import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.WorldGenRegion;
import net.minecraft.world.level.chunk.storage.StructureManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executor;

final class TerrainGeneration {
    private TerrainGeneration() {
    }

    static ChunkAccess generate(ServerLevel level, ChunkPos pos, long seed) {
        var generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            throw new IllegalStateException("不支持的地形生成器: " + generator.getClass().getName());
        }
        Holder<NoiseGeneratorSettings> settings = noiseGenerator.generatorSettings();
        HolderGetter<NoiseGeneratorSettings> holderGetter =
                level.registryAccess().lookupOrThrow(Registries.NOISE_GENERATOR_SETTINGS);
        RandomState randomState = RandomState.create(settings.value(), holderGetter, seed);
        StructureManager structures = level.getStructureManager();
        Blender blender = Blender.empty();
        Executor executor = Util.backgroundExecutor();

        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.createBiomes(executor, randomState, blender, structures, chunk).join();
        chunk.setStatus(ChunkStatus.BIOMES);
        generator.fillFromNoise(executor, blender, randomState, structures, chunk).join();
        chunk.setStatus(ChunkStatus.NOISE);
        List<ChunkAccess> chunks = List.of(chunk);
        WorldGenRegion region = new WorldGenRegion(level, chunks, ChunkStatus.SURFACE, 1);
        generator.buildSurface(region, structures, randomState, chunk);
        chunk.setStatus(ChunkStatus.SURFACE);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        return chunk;
    }
}

package com.mcsaveeditor.server;

import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

final class TerrainGeneration {
    private TerrainGeneration() {
    }

    /**
     * Generates a fresh chunk (biomes + noise + surface) for the given seed.
     * When {@code biomeId} is a valid registry id, every block column of the
     * chunk is forced to that biome before the surface is built, so surface
     * rules (sand, snow, …) match the chosen biome.
     */
    static ChunkAccess generate(ServerLevel level, ChunkPos pos, long seed, String biomeId) {
        var generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            throw new IllegalStateException("不支持的地形生成器: " + generator.getClass().getName());
        }
        Holder<NoiseGeneratorSettings> settings = noiseGenerator.generatorSettings();
        RandomState randomState = RandomState.create(settings.value(), level.registryAccess().lookupOrThrow(Registries.NOISE), seed);
        StructureManager structures = level.structureManager();
        Blender blender = Blender.empty();
        Executor executor = Util.backgroundExecutor();

        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.createBiomes(executor, randomState, blender, structures, chunk).join();
        chunk.setStatus(ChunkStatus.BIOMES);
        generator.fillFromNoise(executor, blender, randomState, structures, chunk).join();
        chunk.setStatus(ChunkStatus.NOISE);
        overrideBiomes(chunk, level, biomeId);
        List<ChunkAccess> chunks = List.of(chunk);
        WorldGenRegion region = new WorldGenRegion(level, chunks, ChunkStatus.SURFACE, 1);
        generator.buildSurface(region, structures, randomState, chunk);
        chunk.setStatus(ChunkStatus.SURFACE);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        return chunk;
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
        Optional<Holder.Reference<Biome>> optional =
                level.registryAccess().lookupOrThrow(Registries.BIOME).get(id);
        if (optional.isEmpty()) {
            return;
        }
        Holder.Reference<Biome> biome = optional.get();
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null) {
                continue;
            }
            PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
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

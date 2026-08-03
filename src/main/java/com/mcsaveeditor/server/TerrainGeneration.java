package com.mcsaveeditor.server;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureManager;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class TerrainGeneration {
    private TerrainGeneration() {
    }

    static ChunkAccess generate(ServerLevel level, ChunkPos pos, long seed) {
        var generator = level.getChunkSource().getGenerator();
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        chunk.setStatus(ChunkStatus.EMPTY);
        StructureManager structures = level.getStructureManager();
        List<ChunkAccess> chunks = List.of(chunk);
        WorldGenRegion region = new WorldGenRegion(level, chunks, ChunkStatus.SURFACE, 1) {
            @Override
            public long getSeed() {
                return seed;
            }
        };
        generator.createBiomes(region, chunk);
        CompletableFuture<ChunkAccess> noise = generator.fillFromNoise(region, structures, chunk);
        chunk = (ProtoChunk) noise.join();
        RandomState randomState = generator.randomState(seed);
        generator.buildSurface(region, structures, randomState, chunk);
        chunk.setStatus(ChunkStatus.SURFACE);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        return chunk;
    }
}

package com.mcsaveeditor.server;

import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.EnumSet;
import java.util.List;

final class TerrainGeneration {
    private TerrainGeneration() {
    }

    static ChunkAccess generate(ServerLevel level, ChunkPos pos, long seed) {
        ServerChunkCache cache = (ServerChunkCache) level.getChunkSource();
        var generator = cache.getGenerator();
        RandomState randomState = cache.randomState();
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
        Blender blender = Blender.empty();
        chunk = (ProtoChunk) generator.createBiomes(
                Util.backgroundExecutor(), randomState, blender, structures, chunk).join();
        chunk = (ProtoChunk) generator.fillFromNoise(
                Util.backgroundExecutor(), blender, randomState, structures, chunk).join();
        generator.buildSurface(region, structures, randomState, chunk);
        chunk.setStatus(ChunkStatus.SURFACE);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        return chunk;
    }
}

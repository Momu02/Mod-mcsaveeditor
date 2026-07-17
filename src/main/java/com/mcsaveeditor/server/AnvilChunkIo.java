package com.mcsaveeditor.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AnvilChunkIo {
    private AnvilChunkIo() {
    }

    public static long readInhabitedTicks(Path chunkRegionPath, ChunkPos pos) throws IOException {
        if (!regionFileExists(chunkRegionPath, pos)) {
            return -1L;
        }

        try (RegionFileStorage storage = new RegionFileStorage(chunkRegionPath, true)) {
            return readInhabitedTicks(storage, pos);
        }
    }

    public static long readInhabitedTicks(RegionFileStorage storage, ChunkPos pos) throws IOException {
        CompoundTag tag = storage.read(pos);
        if (tag == null || tag.isEmpty()) {
            return -1L;
        }
        return readInhabitedTime(tag);
    }

    public static void eraseChunk(Path chunkRegionPath, Path poiRegionPath, Path entityRegionPath, ChunkPos pos) throws IOException {
        eraseFromStorage(chunkRegionPath, pos);
        eraseFromStorage(poiRegionPath, pos);
        eraseFromStorage(entityRegionPath, pos);
    }

    public static void eraseChunk(RegionFileStorage chunkStorage, RegionFileStorage poiStorage, RegionFileStorage entityStorage, ChunkPos pos) throws IOException {
        chunkStorage.write(pos, null);
        poiStorage.write(pos, null);
        entityStorage.write(pos, null);
    }

    private static void eraseFromStorage(Path regionPath, ChunkPos pos) throws IOException {
        if (!regionFileExists(regionPath, pos)) {
            return;
        }

        try (RegionFileStorage storage = new RegionFileStorage(regionPath, false)) {
            storage.write(pos, null);
            storage.flush();
        }
    }

    private static long readInhabitedTime(CompoundTag tag) {
        if (tag.contains("InhabitedTime")) {
            return tag.getLong("InhabitedTime");
        }
        if (tag.contains("Level", 10)) {
            CompoundTag level = tag.getCompound("Level");
            if (level.contains("InhabitedTime")) {
                return level.getLong("InhabitedTime");
            }
        }
        return 0L;
    }

    public static boolean regionFileExists(Path regionPath, ChunkPos pos) {
        return Files.isRegularFile(regionPath.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca"));
    }
}

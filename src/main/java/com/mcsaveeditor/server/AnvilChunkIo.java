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
        return readInhabitedTicks(tag);
    }

    public static long readInhabitedTicks(CompoundTag tag) {
        return readInhabitedTime(tag);
    }

    public static void eraseChunk(Path chunkRegionPath, Path poiRegionPath, Path entityRegionPath, ChunkPos pos) throws IOException {
        eraseFromStorage(chunkRegionPath, pos);
        eraseFromStorage(poiRegionPath, pos);
        eraseFromStorage(entityRegionPath, pos);
    }

    public static void eraseChunk(RegionFileStorage chunkStorage, RegionFileStorage poiStorage, RegionFileStorage entityStorage, ChunkPos pos) throws IOException {
        writable(chunkStorage).writeChunk(pos, null);
        writable(poiStorage).writeChunk(pos, null);
        writable(entityStorage).writeChunk(pos, null);
    }

    static void writeChunk(RegionFileStorage storage, ChunkPos pos, CompoundTag tag) throws IOException {
        writable(storage).writeChunk(pos, tag);
    }

    private static void eraseFromStorage(Path regionPath, ChunkPos pos) throws IOException {
        if (!regionFileExists(regionPath, pos)) {
            return;
        }

        try (WritableRegionFileStorage storage = new WritableRegionFileStorage(regionPath, false)) {
            storage.writeChunk(pos, null);
            storage.flush();
        }
    }

    private static WritableRegionFileStorage writable(RegionFileStorage storage) throws IOException {
        if (storage instanceof WritableRegionFileStorage writable) {
            return writable;
        }
        throw new IOException("Chunk storage is not writable");
    }

    public static final class WritableRegionFileStorage extends RegionFileStorage {
        public WritableRegionFileStorage(Path path, boolean sync) {
            super(path, sync);
        }

        public void writeChunk(ChunkPos pos, CompoundTag tag) throws IOException {
            write(pos, tag);
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

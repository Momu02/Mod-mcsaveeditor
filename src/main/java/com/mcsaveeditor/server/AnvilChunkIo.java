package com.mcsaveeditor.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AnvilChunkIo {
    private static final Method WRITE_METHOD;

    static {
        try {
            WRITE_METHOD = RegionFileStorage.class.getDeclaredMethod("write", ChunkPos.class, CompoundTag.class);
            WRITE_METHOD.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to find RegionFileStorage.write method", e);
        }
    }

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
        writeChunk(chunkStorage, pos, null);
        writeChunk(poiStorage, pos, null);
        writeChunk(entityStorage, pos, null);
    }

    static void writeChunk(RegionFileStorage storage, ChunkPos pos, CompoundTag tag) throws IOException {
        try {
            WRITE_METHOD.invoke(storage, pos, tag);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Failed to write chunk", cause);
        } catch (IllegalAccessException e) {
            throw new IOException("Reflection access error", e);
        }
    }

    private static void eraseFromStorage(Path regionPath, ChunkPos pos) throws IOException {
        if (!regionFileExists(regionPath, pos)) {
            return;
        }

        try (RegionFileStorage storage = new RegionFileStorage(regionPath, false)) {
            writeChunk(storage, pos, null);
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

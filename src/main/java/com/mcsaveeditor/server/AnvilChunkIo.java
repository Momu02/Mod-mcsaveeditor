package com.mcsaveeditor.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AnvilChunkIo {
    private static final Method WRITE_METHOD = findWriteMethod();

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

    public static boolean hasChunk(RegionFileStorage storage, ChunkPos pos) throws IOException {
        CompoundTag tag = storage.read(pos);
        return tag != null && !tag.isEmpty();
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
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("RegionFileStorage.write failed", cause);
        } catch (IllegalAccessException | IllegalArgumentException exception) {
            throw new IOException("Unable to invoke RegionFileStorage.write", exception);
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

    private static Method findWriteMethod() {
        for (Method method : RegionFileStorage.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0] == ChunkPos.class
                    && parameters[1] == CompoundTag.class) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("Unable to find RegionFileStorage chunk write method");
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

package com.mcsaveeditor.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    public static void compactRegion(Path regionPath, int regionX, int regionZ) throws IOException {
        Path file = regionPath.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) {
            return;
        }

        List<StoredChunk> chunks = new ArrayList<>();
        try (RegionFileStorage storage = new RegionFileStorage(regionPath, true)) {
            int minX = regionX << 5;
            int minZ = regionZ << 5;
            for (int localX = 0; localX < 32; localX++) {
                for (int localZ = 0; localZ < 32; localZ++) {
                    ChunkPos pos = new ChunkPos(minX + localX, minZ + localZ);
                    CompoundTag tag = storage.read(pos);
                    if (tag != null && !tag.isEmpty()) {
                        chunks.add(new StoredChunk(pos, tag));
                    }
                }
            }
        }

        Files.deleteIfExists(file);
        if (chunks.isEmpty()) {
            return;
        }

        try (RegionFileStorage storage = new RegionFileStorage(regionPath, false)) {
            for (StoredChunk chunk : chunks) {
                writeChunk(storage, chunk.pos(), chunk.tag());
            }
            storage.flush();
        }
    }

    private record StoredChunk(ChunkPos pos, CompoundTag tag) {
    }
}

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

    public static long readInhabitedTicks(CompoundTag tag) {
        return readInhabitedTime(tag);
    }

    public static boolean hasChunk(RegionFileStorage storage, ChunkPos pos) throws IOException {
        CompoundTag tag = storage.read(pos);
        return tag != null && !tag.isEmpty();
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

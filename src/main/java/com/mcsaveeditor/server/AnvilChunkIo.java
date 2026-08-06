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

    static void writeChunk(RegionFileStorage storage, ChunkPos pos, CompoundTag tag) throws IOException {
        storage.write(pos, tag);
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

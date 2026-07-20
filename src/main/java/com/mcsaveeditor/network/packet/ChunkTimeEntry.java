package com.mcsaveeditor.network.packet;

public record ChunkTimeEntry(int chunkX, int chunkZ, long inhabitedTicks, int[] terrainColors, boolean protectedChunk) {
    public static final int MAP_SIZE = 8;
    public static final int MAP_PIXELS = MAP_SIZE * MAP_SIZE;

    public long inhabitedMinutes() {
        return inhabitedTicks / 1200L;
    }
}

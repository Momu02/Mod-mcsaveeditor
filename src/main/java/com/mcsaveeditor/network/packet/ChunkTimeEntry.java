package com.mcsaveeditor.network.packet;

public record ChunkTimeEntry(int chunkX, int chunkZ, long inhabitedTicks) {
    public long inhabitedMinutes() {
        return inhabitedTicks / 1200L;
    }
}

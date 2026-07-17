package com.mcsaveeditor.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public final class WorldStoragePaths {
    private WorldStoragePaths() {
    }

    public static Path dimensionRoot(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), worldRoot);
    }

    public static Path chunkRegion(ServerLevel level) {
        return dimensionRoot(level).resolve("region");
    }

    public static Path poiRegion(ServerLevel level) {
        return dimensionRoot(level).resolve("poi");
    }

    public static Path entityRegion(ServerLevel level) {
        return dimensionRoot(level).resolve("entities");
    }
}

package com.mcsaveeditor.server;

import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TerrainRegenerationSession implements AutoCloseable {
    private static final ChunkProgressListener NO_PROGRESS = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos pos) {
        }

        @Override
        public void onStatusChange(ChunkPos pos, @Nullable ChunkStatus status) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };

    private final Path root;
    private final LevelStorageSource.LevelStorageAccess oldAccess;
    private final LevelStorageSource.LevelStorageAccess newAccess;
    private final ServerLevel oldWorld;
    private final ServerLevel newWorld;
    private final Map<Long, ChunkAccess> oldCache = new HashMap<>();
    private final Map<Long, ChunkAccess> newCache = new HashMap<>();

    TerrainRegenerationSession(ServerLevel source, long targetSeed) throws IOException {
        this.root = Files.createTempDirectory("mcsaveeditor-worldgen-");
        LevelStorageSource oldStorage = LevelStorageSource.createDefault(root.resolve("old"));
        LevelStorageSource newStorage = LevelStorageSource.createDefault(root.resolve("new"));
        this.oldAccess = oldStorage.createAccess("world");
        this.newAccess = newStorage.createAccess("world");
        this.oldWorld = createWorld(source, oldAccess, source.getSeed());
        this.newWorld = createWorld(source, newAccess, targetSeed);
    }

    TerrainPair pair(ChunkPos pos) {
        long key = pos.toLong();
        ChunkAccess oldTerrain = oldCache.computeIfAbsent(key, ignored -> generate(oldWorld, pos));
        ChunkAccess newTerrain = newCache.computeIfAbsent(key, ignored -> generate(newWorld, pos));
        return new TerrainPair(oldTerrain, newTerrain);
    }

    ServerLevel oldWorld() {
        return oldWorld;
    }

    ServerLevel newWorld() {
        return newWorld;
    }

    private static ServerLevel createWorld(ServerLevel source, LevelStorageSource.LevelStorageAccess access,
                                           long worldSeed) {
        ServerLevel world = new ServerLevel(
                source.getServer(), Util.backgroundExecutor(), access,
                (ServerLevelData) source.getLevelData(), source.dimension(),
                new LevelStem(source.dimensionTypeRegistration(), source.getChunkSource().getGenerator()),
                NO_PROGRESS, source.isDebug(), worldSeed, List.of(), false, source.getRandomSequences()) {
            @Override
            public long getSeed() {
                return worldSeed;
            }
        };
        world.noSave = true;
        return world;
    }

    private static ChunkAccess generate(ServerLevel world, ChunkPos pos) {
        var future = world.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.FEATURES, true);
        world.getServer().managedBlock(future::isDone);
        return future.join().left().orElseThrow(() -> new IllegalStateException("无法生成区块 " + pos));
    }

    @Override
    public void close() {
        oldCache.clear();
        newCache.clear();
        closeQuietly(oldWorld);
        closeQuietly(newWorld);
        closeQuietly(oldAccess);
        closeQuietly(newAccess);
        deleteTree(root);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    record TerrainPair(ChunkAccess oldTerrain, ChunkAccess newTerrain) {
    }
}

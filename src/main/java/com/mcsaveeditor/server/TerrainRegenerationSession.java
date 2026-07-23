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

    private final ServerLevel source;
    private final Path root;
    private final LevelStorageSource.LevelStorageAccess access;
    private final ServerLevel genWorld;
    private final Map<Long, ChunkAccess> cache = new HashMap<>();

    TerrainRegenerationSession(ServerLevel source, long targetSeed) throws IOException {
        this.source = source;
        this.root = Files.createTempDirectory("mcsaveeditor-worldgen-");
        LevelStorageSource storage = LevelStorageSource.createDefault(root.resolve("gen"));
        this.access = storage.createAccess("world");
        this.genWorld = createWorld(source, access, targetSeed);
    }

    ChunkAccess generate(ChunkPos pos) {
        long key = pos.toLong();
        ChunkAccess cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        var future = genWorld.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.FEATURES, true);
        source.getServer().managedBlock(future::isDone);
        ChunkAccess chunk = future.join().left()
                .orElseThrow(() -> new IllegalStateException("无法生成区块 " + pos));
        cache.put(key, chunk);
        return chunk;
    }

    ServerLevel genWorld() {
        return genWorld;
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

    @Override
    public void close() {
        cache.clear();
        closeQuietly(genWorld);
        closeQuietly(access);
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
}

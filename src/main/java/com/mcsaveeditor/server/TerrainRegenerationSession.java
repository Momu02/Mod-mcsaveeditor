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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class TerrainRegenerationSession implements AutoCloseable {
    private static final int MAX_PENDING_REQUESTS = 32;
    private static final int MAX_COMPLETED_RESULTS = 256;
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
    private final LevelStorageSource oldStorage;
    private final LevelStorageSource newStorage;
    private final LevelStorageSource.LevelStorageAccess oldAccess;
    private final LevelStorageSource.LevelStorageAccess newAccess;
    private final ServerLevel oldWorld;
    private final ServerLevel newWorld;
    private final Executor workerExecutor;
    private final Map<Long, PendingPair> pending = new LinkedHashMap<>();
    private final Map<Long, TerrainPair> completed = new LinkedHashMap<>();
    private boolean closed;

    TerrainRegenerationSession(ServerLevel source, long targetSeed) throws IOException {
        this.source = source;
        this.root = Files.createTempDirectory("mcsaveeditor-worldgen-");
        this.oldStorage = LevelStorageSource.createDefault(root.resolve("old"));
        this.newStorage = LevelStorageSource.createDefault(root.resolve("new"));
        this.oldAccess = oldStorage.createAccess("world");
        this.newAccess = newStorage.createAccess("world");
        this.oldWorld = createWorld(oldAccess, source.getSeed());
        this.newWorld = createWorld(newAccess, targetSeed);
        this.workerExecutor = runnable -> source.getServer().executeIfPossible(runnable);
    }

    boolean canAcceptMore() {
        collectCompleted();
        return pending.size() < MAX_PENDING_REQUESTS && completed.size() < MAX_COMPLETED_RESULTS;
    }

    void request(ChunkPos pos) {
        if (closed) {
            throw new IllegalStateException("地形重建会话已关闭");
        }
        long key = pos.toLong();
        if (completed.containsKey(key) || pending.containsKey(key)) {
            return;
        }
        PendingPair pair = new PendingPair(
                CompletableFuture.supplyAsync(() -> generate(oldWorld, pos), workerExecutor),
                CompletableFuture.supplyAsync(() -> generate(newWorld, pos), workerExecutor));
        pending.put(key, pair);
        pair.combined().whenComplete((result, error) -> source.getServer().executeIfPossible(() -> {
            PendingPair current = pending.remove(key);
            if (current != null && error == null && result != null) {
                completed.put(key, result);
            }
        }));
    }

    @Nullable
    TerrainPair completed(ChunkPos pos) {
        collectCompleted();
        return completed.get(pos.toLong());
    }

    void release(ChunkPos pos) {
        completed.remove(pos.toLong());
        pending.remove(pos.toLong());
    }

    boolean isIdle() {
        return pending.isEmpty() && completed.isEmpty();
    }

    ServerLevel oldWorld() {
        return oldWorld;
    }

    ServerLevel newWorld() {
        return newWorld;
    }

    private ServerLevel createWorld(LevelStorageSource.LevelStorageAccess access, long worldSeed) {
        return new ServerLevel(
                source.getServer(), Util.backgroundExecutor(), access,
                (ServerLevelData) source.getLevelData(), source.dimension(),
                new LevelStem(source.dimensionTypeRegistration(), source.getChunkSource().getGenerator()),
                NO_PROGRESS, source.isDebug(), worldSeed, List.of(), false, source.getRandomSequences()) {
            @Override
            public long getSeed() {
                return worldSeed;
            }
        };
    }

    private void collectCompleted() {
        Set<Long> finished = new HashSet<>();
        for (Map.Entry<Long, PendingPair> entry : pending.entrySet()) {
            CompletableFuture<TerrainPair> combined = entry.getValue().combined();
            TerrainPair pair = combined.getNow(null);
            if (pair != null) {
                completed.putIfAbsent(entry.getKey(), pair);
                finished.add(entry.getKey());
            }
        }
        finished.forEach(pending::remove);
    }

    private ChunkAccess generate(ServerLevel world, ChunkPos pos) {
        var future =
                world.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.FEATURES, true);
        return future.join().left().orElseThrow(() -> new IllegalStateException("无法生成区块 " + pos));
    }

    @Override
    public void close() {
        closed = true;
        pending.clear();
        completed.clear();
        try {
            oldWorld.close();
        } catch (IOException ignored) {
        }
        try {
            newWorld.close();
        } catch (IOException ignored) {
        }
        try {
            oldAccess.close();
        } catch (IOException ignored) {
        }
        try {
            newAccess.close();
        } catch (IOException ignored) {
        }
        deleteTree(root);
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

    private record PendingPair(CompletableFuture<ChunkAccess> oldTerrain,
                               CompletableFuture<ChunkAccess> newTerrain) {
        private CompletableFuture<TerrainPair> combined() {
            return oldTerrain.thenCombine(newTerrain, TerrainPair::new);
        }
    }
}

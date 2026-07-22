package com.mcsaveeditor.server;

import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    private final LevelStorageSource oldStorage;
    private final LevelStorageSource newStorage;
    private final LevelStorageSource.LevelStorageAccess oldAccess;
    private final LevelStorageSource.LevelStorageAccess newAccess;
    private final ServerLevel oldWorld;
    private final ServerLevel newWorld;

    TerrainRegenerationSession(ServerLevel source, long targetSeed) throws IOException {
        this.source = source;
        this.root = Files.createTempDirectory("mcsaveeditor-worldgen-");
        this.oldStorage = LevelStorageSource.createDefault(root.resolve("old"));
        this.newStorage = LevelStorageSource.createDefault(root.resolve("new"));
        this.oldAccess = oldStorage.createAccess("world");
        this.newAccess = newStorage.createAccess("world");
        this.oldWorld = createWorld(oldAccess, source.getSeed());
        this.newWorld = createWorld(newAccess, targetSeed);
    }

    ChunkAccess oldChunk(ChunkPos pos) {
        return generate(oldWorld, pos);
    }

    ChunkAccess newChunk(ChunkPos pos) {
        return generate(newWorld, pos);
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

    private ChunkAccess generate(ServerLevel world, ChunkPos pos) {
        var future =
                world.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.FEATURES, true);
        world.getServer().managedBlock(future::isDone);
        return future.join().left().orElseThrow(() -> new IllegalStateException("无法生成区块 " + pos));
    }

    @Override
    public void close() {
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
}

package com.mcsaveeditor.server;

import com.mcsaveeditor.network.packet.DeleteChunksPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraftforge.event.TickEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public final class ServerChunkEraseService {
    private static final Queue<EraseJob> JOBS = new ArrayDeque<>();
    private static final int CHUNKS_PER_TICK = 24;
    private static final int MAX_RETRIES = 200;

    private ServerChunkEraseService() {
    }

    public static void handleDeleteRequest(ServerPlayer player, DeleteChunksPacket packet) {
        if (!player.hasPermissions(3)) {
            player.sendSystemMessage(Component.translatable("mcsaveeditor.permission.denied").withStyle(ChatFormatting.RED));
            return;
        }
        if (packet.chunks().isEmpty()) {
            return;
        }

        ServerLevel level = player.serverLevel();
        List<ChunkPos> distinct = packet.chunks().stream().distinct().limit(DeleteChunksPacket.MAX_DELETE_COUNT).toList();
        JOBS.add(new EraseJob(level, distinct));
        player.sendSystemMessage(Component.literal("MC Save Editor: queued " + distinct.size() + " chunks for safe erase."));
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) {
            return;
        }

        EraseJob job = JOBS.peek();
        if (job == null) {
            return;
        }

        job.tick();
        if (job.isDone()) {
            JOBS.poll();
            job.level.getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("MC Save Editor: erase complete. Deleted " + job.deleted + ", skipped " + job.skipped + "."),
                    false);
        }
    }

    private static final class EraseJob {
        private final ServerLevel level;
        private final Queue<PendingChunk> queue = new ArrayDeque<>();
        private final Path chunkRegionPath;
        private final Path poiRegionPath;
        private final Path entityRegionPath;
        private final RegionFileStorage chunkStorage;
        private final RegionFileStorage poiStorage;
        private final RegionFileStorage entityStorage;
        private int deleted;
        private int skipped;

        private EraseJob(ServerLevel level, List<ChunkPos> chunks) {
            this.level = level;
            this.chunkRegionPath = WorldStoragePaths.chunkRegion(level);
            this.poiRegionPath = WorldStoragePaths.poiRegion(level);
            this.entityRegionPath = WorldStoragePaths.entityRegion(level);
            this.chunkStorage = openStorage(chunkRegionPath);
            this.poiStorage = openStorage(poiRegionPath);
            this.entityStorage = openStorage(entityRegionPath);

            Set<Long> seen = new HashSet<>();
            for (ChunkPos pos : chunks) {
                if (seen.add(pos.toLong())) {
                    queue.add(new PendingChunk(pos));
                }
            }
            level.getChunkSource().save(true);
        }

        private void tick() {
            int processed = 0;
            while (processed++ < CHUNKS_PER_TICK && !queue.isEmpty()) {
                PendingChunk pending = queue.poll();
                if (pending == null) {
                    continue;
                }
                process(pending);
            }
        }

        private void process(PendingChunk pending) {
            ChunkPos pos = pending.pos;
            ServerChunkCache chunkSource = level.getChunkSource();

            level.setChunkForced(pos.x, pos.z, false);

            if (isNearAnyPlayer(pos)) {
                skipped++;
                return;
            }

            if (chunkSource.hasChunk(pos.x, pos.z)) {
                if (pending.retries++ < MAX_RETRIES) {
                    queue.add(pending);
                } else {
                    skipped++;
                }
                return;
            }

            try {
                if (eraseIfPresent(pos)) {
                    forgetClientChunk(pos);
                    deleted++;
                } else {
                    skipped++;
                }
            } catch (IOException exception) {
                skipped++;
            }
        }

        private boolean eraseIfPresent(ChunkPos pos) throws IOException {
            boolean present = false;
            if (AnvilChunkIo.regionFileExists(chunkRegionPath, pos)) {
                present = AnvilChunkIo.hasChunk(chunkStorage, pos);
                AnvilChunkIo.writeChunk(chunkStorage, pos, null);
            }
            if (AnvilChunkIo.regionFileExists(poiRegionPath, pos)) {
                present |= AnvilChunkIo.hasChunk(poiStorage, pos);
                AnvilChunkIo.writeChunk(poiStorage, pos, null);
            }
            if (AnvilChunkIo.regionFileExists(entityRegionPath, pos)) {
                present |= AnvilChunkIo.hasChunk(entityStorage, pos);
                AnvilChunkIo.writeChunk(entityStorage, pos, null);
            }
            chunkStorage.flush();
            poiStorage.flush();
            entityStorage.flush();
            verifyErased(chunkStorage, pos, chunkRegionPath);
            verifyErased(poiStorage, pos, poiRegionPath);
            verifyErased(entityStorage, pos, entityRegionPath);
            return present;
        }

        private void verifyErased(RegionFileStorage storage, ChunkPos pos, Path regionPath) throws IOException {
            if (AnvilChunkIo.regionFileExists(regionPath, pos) && storage.read(pos) != null) {
                throw new IOException("Region file still contains chunk " + pos);
            }
        }

        private void forgetClientChunk(ChunkPos pos) {
            ClientboundForgetLevelChunkPacket packet = new ClientboundForgetLevelChunkPacket(pos);
            for (ServerPlayer player : level.players()) {
                player.connection.send(packet);
            }
        }

        private boolean isNearAnyPlayer(ChunkPos target) {
            int guardDistance = level.getServer().getPlayerList().getViewDistance() + 2;
            for (ServerPlayer player : level.players()) {
                ChunkPos playerPos = player.chunkPosition();
                int dx = Math.abs(playerPos.x - target.x);
                int dz = Math.abs(playerPos.z - target.z);
                if (Math.max(dx, dz) <= guardDistance) {
                    return true;
                }
            }
            return false;
        }

        private boolean isDone() {
            if (queue.isEmpty()) {
                flushAndClose(chunkStorage);
                flushAndClose(poiStorage);
                flushAndClose(entityStorage);
                return true;
            }
            return false;
        }

        private static RegionFileStorage openStorage(Path path) {
            return new RegionFileStorage(path, false);
        }

        private static void flushAndClose(RegionFileStorage storage) {
            try {
                storage.flush();
            } catch (IOException ignored) {
            }
            closeQuietly(storage);
        }

        private static void closeQuietly(RegionFileStorage closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class PendingChunk {
        private final ChunkPos pos;
        private int retries;

        private PendingChunk(ChunkPos pos) {
            this.pos = pos;
        }
    }
}

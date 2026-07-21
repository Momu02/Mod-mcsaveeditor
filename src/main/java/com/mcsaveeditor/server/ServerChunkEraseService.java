package com.mcsaveeditor.server;

import com.mcsaveeditor.network.ModNetworking;
import com.mcsaveeditor.network.packet.DeleteChunksPacket;
import com.mcsaveeditor.network.packet.DeletedChunksPacket;
import com.mcsaveeditor.network.packet.ChunkOperation;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
        ServerChunkScanService.cancelScan(player, false);
        JOBS.add(new EraseJob(level, packet.operation(), distinct));
        player.sendSystemMessage(Component.literal("MC Save Editor: queued " + distinct.size() + " chunks for "
                + operationName(packet.operation()) + "."));
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
                    Component.literal("MC Save Editor: " + operationName(job.operation) + " complete. Processed "
                            + job.processed + ", skipped " + job.skipped + "."),
                    false);
        }
    }

    private static String operationName(ChunkOperation operation) {
        return operation == ChunkOperation.UPDATE ? "chunk update" : "complete deletion";
    }

    private static final class EraseJob {
        private final ServerLevel level;
        private final ChunkOperation operation;
        private final Queue<PendingChunk> queue = new ArrayDeque<>();
        private final Path chunkRegionPath;
        private final Path poiRegionPath;
        private final Path entityRegionPath;
        private final RegionFileStorage chunkStorage;
        private final RegionFileStorage poiStorage;
        private final RegionFileStorage entityStorage;
        private final Set<Long> affectedRegions = new HashSet<>();
        private int processed;
        private int skipped;

        private EraseJob(ServerLevel level, ChunkOperation operation, List<ChunkPos> chunks) {
            this.level = level;
            this.operation = operation;
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
                    affectedRegions.add(ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ()));
                }
            }
            level.getChunkSource().save(true);
        }

        private void tick() {
            List<ChunkPos> completed = new ArrayList<>(CHUNKS_PER_TICK);
            int processed = 0;
            while (processed++ < CHUNKS_PER_TICK && !queue.isEmpty()) {
                PendingChunk pending = queue.poll();
                if (pending == null) {
                    continue;
                }
                if (process(pending)) {
                    completed.add(pending.pos);
                }
            }
            if (!completed.isEmpty()) {
                ModNetworking.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),
                        new DeletedChunksPacket(operation, List.copyOf(completed)));
            }
        }

        private boolean process(PendingChunk pending) {
            ChunkPos pos = pending.pos;
            ServerChunkCache chunkSource = level.getChunkSource();

            level.setChunkForced(pos.x, pos.z, false);

            if (isNearAnyPlayer(pos)) {
                deferOrSkip(pending);
                return false;
            }

            if (chunkSource.hasChunk(pos.x, pos.z)) {
                if (pending.retries++ < MAX_RETRIES) {
                    queue.add(pending);
                } else {
                    skipped++;
                }
                return false;
            }

            try {
                if (processStoredChunk(pos)) {
                    forgetClientChunk(pos);
                    processed++;
                    return true;
                } else {
                    skipped++;
                    return true;
                }
            } catch (IOException exception) {
                skipped++;
            }
            return false;
        }

        private void deferOrSkip(PendingChunk pending) {
            if (pending.retries++ < MAX_RETRIES) {
                queue.add(pending);
            } else {
                skipped++;
            }
        }

        private boolean processStoredChunk(ChunkPos pos) throws IOException {
            if (operation == ChunkOperation.UPDATE) {
                return updateIfPresent(pos);
            }
            return eraseIfPresent(pos);
        }

        private boolean updateIfPresent(ChunkPos pos) throws IOException {
            boolean present = updateStorageIfPresent(chunkStorage, chunkRegionPath, pos, DataFixTypes.CHUNK);
            present |= updateStorageIfPresent(poiStorage, poiRegionPath, pos, DataFixTypes.POI_CHUNK);
            present |= updateStorageIfPresent(entityStorage, entityRegionPath, pos, DataFixTypes.ENTITY_CHUNK);
            chunkStorage.flush();
            poiStorage.flush();
            entityStorage.flush();
            return present;
        }

        private boolean updateStorageIfPresent(RegionFileStorage storage, Path regionPath, ChunkPos pos,
                                               DataFixTypes dataFixType) throws IOException {
            if (!AnvilChunkIo.regionFileExists(regionPath, pos)) {
                return false;
            }
            CompoundTag tag = storage.read(pos);
            if (tag == null || tag.isEmpty()) {
                return false;
            }

            int oldVersion = NbtUtils.getDataVersion(tag, -1);
            int currentVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            CompoundTag updated = dataFixType.updateToCurrentVersion(level.getServer().getFixerUpper(), tag, oldVersion);
            updated.putInt("DataVersion", currentVersion);
            AnvilChunkIo.writeChunk(storage, pos, updated);
            return true;
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
            ClientboundForgetLevelChunkPacket packet = new ClientboundForgetLevelChunkPacket(pos.x, pos.z);
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
                if (operation == ChunkOperation.DELETE) {
                    compactAffectedRegions();
                }
                return true;
            }
            return false;
        }

        private void compactAffectedRegions() {
            for (long region : affectedRegions) {
                int regionX = (int) region;
                int regionZ = (int) (region >> 32);
                compactQuietly(chunkRegionPath, regionX, regionZ);
                compactQuietly(poiRegionPath, regionX, regionZ);
                compactQuietly(entityRegionPath, regionX, regionZ);
            }
        }

        private static void compactQuietly(Path regionPath, int regionX, int regionZ) {
            try {
                AnvilChunkIo.compactRegion(regionPath, regionX, regionZ);
            } catch (IOException ignored) {
                // The chunk slots were already cleared; a later save can compact the file again.
            }
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

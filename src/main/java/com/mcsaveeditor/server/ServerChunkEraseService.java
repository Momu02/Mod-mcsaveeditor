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
import net.minecraft.server.level.ChunkMap;
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
    private static final int MAX_RETRIES = 1200;

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
        player.sendSystemMessage(Component.literal("MC 存档区块编辑器：已将 " + distinct.size() + " 个区块加入"
                + operationName(packet.operation()) + "队列。请离开目标区块，等待其卸载。"));
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
                    Component.literal("MC 存档区块编辑器：" + operationName(job.operation) + "完成。已处理 "
                            + job.processed + " 个，跳过 " + job.skipped + " 个。"),
                    false);
        }
    }

    private static String operationName(ChunkOperation operation) {
        return operation == ChunkOperation.UPDATE ? "区块数据升级" : "彻底删除";
    }

    private static final class EraseJob {
        private final ServerLevel level;
        private final ChunkOperation operation;
        private final Queue<PendingChunk> queue = new ArrayDeque<>();
        private final Path chunkRegionPath;
        private final Path poiRegionPath;
        private final Path entityRegionPath;
        private final RegionFileStorage poiStorage;
        private final RegionFileStorage entityStorage;
        private int processed;
        private int skipped;

        private EraseJob(ServerLevel level, ChunkOperation operation, List<ChunkPos> chunks) {
            this.level = level;
            this.operation = operation;
            this.chunkRegionPath = WorldStoragePaths.chunkRegion(level);
            this.poiRegionPath = WorldStoragePaths.poiRegion(level);
            this.entityRegionPath = WorldStoragePaths.entityRegion(level);
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
            List<ChunkPos> completed = new ArrayList<>(CHUNKS_PER_TICK);
            int remaining = Math.min(CHUNKS_PER_TICK, queue.size());
            while (remaining-- > 0 && !queue.isEmpty()) {
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
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            boolean present = updateChunkIfPresent(chunkMap, pos);
            present |= updateStorageIfPresent(poiStorage, poiRegionPath, pos, DataFixTypes.POI_CHUNK);
            present |= updateStorageIfPresent(entityStorage, entityRegionPath, pos, DataFixTypes.ENTITY_CHUNK);
            chunkMap.flushWorker();
            poiStorage.flush();
            entityStorage.flush();
            return present;
        }

        private boolean updateChunkIfPresent(ChunkMap chunkMap, ChunkPos pos) throws IOException {
            if (!AnvilChunkIo.regionFileExists(chunkRegionPath, pos)) {
                return false;
            }
            CompoundTag tag = chunkMap.read(pos).join().orElse(null);
            if (tag == null || tag.isEmpty()) {
                return false;
            }
            writeUpdatedTag(chunkMap, pos, tag, DataFixTypes.CHUNK);
            return true;
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

        private void writeUpdatedTag(ChunkMap chunkMap, ChunkPos pos, CompoundTag tag,
                                     DataFixTypes dataFixType) {
            int oldVersion = NbtUtils.getDataVersion(tag, -1);
            int currentVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            CompoundTag updated = dataFixType.updateToCurrentVersion(level.getServer().getFixerUpper(), tag, oldVersion);
            updated.putInt("DataVersion", currentVersion);
            chunkMap.write(pos, updated);
        }

        private boolean eraseIfPresent(ChunkPos pos) throws IOException {
            boolean present = false;
            if (AnvilChunkIo.regionFileExists(chunkRegionPath, pos)) {
                ChunkMap chunkMap = level.getChunkSource().chunkMap;
                present = chunkMap.read(pos).join().isPresent();
                chunkMap.write(pos, null);
                chunkMap.flushWorker();
                if (chunkMap.read(pos).join().isPresent()) {
                    throw new IOException("区块存储仍包含 " + pos);
                }
            }
            if (AnvilChunkIo.regionFileExists(poiRegionPath, pos)) {
                present |= AnvilChunkIo.hasChunk(poiStorage, pos);
                AnvilChunkIo.writeChunk(poiStorage, pos, null);
            }
            if (AnvilChunkIo.regionFileExists(entityRegionPath, pos)) {
                present |= AnvilChunkIo.hasChunk(entityStorage, pos);
                AnvilChunkIo.writeChunk(entityStorage, pos, null);
            }
            poiStorage.flush();
            entityStorage.flush();
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

package com.mcsaveeditor.server;

import com.mcsaveeditor.network.ModNetworking;
import com.mcsaveeditor.network.packet.DeleteChunksPacket;
import com.mcsaveeditor.network.packet.DeletedChunksPacket;
import com.mcsaveeditor.network.packet.ChunkOperation;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

public final class ServerChunkEraseService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Queue<EraseJob> JOBS = new ArrayDeque<>();
    private static final int CHUNKS_PER_TICK = 24;
    private static final int REGENERATE_CHUNKS_PER_TICK = 4;
    private static final int MAX_REGENERATE_CHUNKS = 65536;
    private static final int MAX_RETRIES = 1200;
    private static final long REGENERATE_TIME_BUDGET_NS = 40_000_000L;

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
        if (packet.operation() == ChunkOperation.REGENERATE && distinct.size() > MAX_REGENERATE_CHUNKS) {
            player.sendSystemMessage(Component.literal("MC 存档区块编辑器：选区超过 "
                    + MAX_REGENERATE_CHUNKS + " 个区块。请分批处理。").withStyle(ChatFormatting.RED));
            return;
        }
        ServerChunkScanService.cancelScan(player, false);
        long seed = packet.randomSeed() ? new Random().nextLong() : packet.seed();
        EraseJob job = new EraseJob(level, packet.operation(), distinct, seed);
        JOBS.add(job);
        String extra = packet.operation() == ChunkOperation.REGENERATE
                ? " 处理 " + job.total + " 个选中区块（边缘在圈选内高度融合）。目标种子：" + seed
                : "";
        player.sendSystemMessage(Component.literal("MC 存档区块编辑器：已将 " + distinct.size() + " 个选中区块加入"
                + operationName(packet.operation()) + "队列。" + extra + " 请离开目标区块，等待其卸载。"));
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
        return switch (operation) {
            case UPDATE -> "区块数据升级";
            case REGENERATE -> "区块地形重建";
            case DELETE -> "彻底删除";
        };
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
        private final long targetSeed;
        private final Set<Long> selectedChunks;
        private TerrainRegenerationSession regenerationSession;
        private final int total;
        private int processed;
        private int skipped;
        private int progressMarker;

        private EraseJob(ServerLevel level, ChunkOperation operation, List<ChunkPos> chunks, long targetSeed) {
            this.level = level;
            this.operation = operation;
            this.chunkRegionPath = WorldStoragePaths.chunkRegion(level);
            this.poiRegionPath = WorldStoragePaths.poiRegion(level);
            this.entityRegionPath = WorldStoragePaths.entityRegion(level);
            this.poiStorage = openStorage(poiRegionPath);
            this.entityStorage = openStorage(entityRegionPath);
            this.targetSeed = targetSeed;
            this.selectedChunks = new HashSet<>();

            Set<Long> seen = new HashSet<>();
            for (ChunkPos pos : chunks) {
                if (seen.add(pos.toLong())) {
                    selectedChunks.add(pos.toLong());
                    queue.add(new PendingChunk(pos));
                }
            }
            this.total = queue.size();
            level.getChunkSource().save(true);
        }

        private void tick() {
            int limit = operation == ChunkOperation.REGENERATE ? REGENERATE_CHUNKS_PER_TICK : CHUNKS_PER_TICK;
            long deadline = operation == ChunkOperation.REGENERATE
                    ? System.nanoTime() + REGENERATE_TIME_BUDGET_NS : Long.MAX_VALUE;
            List<ChunkPos> completed = new ArrayList<>(limit);
            List<ChunkPos> skippedThisTick = new ArrayList<>(limit);
            int remainingLookups = queue.size();
            while (remainingLookups-- > 0 && !queue.isEmpty() && completed.size() < limit && System.nanoTime() < deadline) {
                PendingChunk pending = queue.poll();
                if (pending == null) {
                    continue;
                }
                ProcessResult result = process(pending);
                if (result == ProcessResult.SUCCESS) {
                    completed.add(pending.pos);
                } else if (result == ProcessResult.SKIPPED) {
                    skippedThisTick.add(pending.pos);
                } else if (result == ProcessResult.DEFERRED) {
                    continue;
                }
            }
            if (operation == ChunkOperation.REGENERATE && !completed.isEmpty()) {
                flushRegeneratedChunks();
                maybeReportProgress();
            }
            List<ChunkPos> allProcessed = new ArrayList<>(completed.size() + skippedThisTick.size());
            allProcessed.addAll(completed);
            allProcessed.addAll(skippedThisTick);
            if (!allProcessed.isEmpty()) {
                ModNetworking.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),
                        new DeletedChunksPacket(operation, List.copyOf(allProcessed)));
            }
        }

        private ProcessResult process(PendingChunk pending) {
            ChunkPos pos = pending.pos;
            ServerChunkCache chunkSource = level.getChunkSource();

            level.setChunkForced(pos.x, pos.z, false);

            if (isNearAnyPlayer(pos)) {
                return deferOrSkip(pending);
            }

            if (chunkSource.hasChunk(pos.x, pos.z)) {
                return deferOrSkip(pending);
            }

            try {
                if (processStoredChunk(pending)) {
                    if (operation != ChunkOperation.REGENERATE) {
                        forgetClientChunk(pos);
                    }
                    processed++;
                    return ProcessResult.SUCCESS;
                }
                skipped++;
                return ProcessResult.SKIPPED;
            } catch (Exception exception) {
                skipped++;
                LOGGER.error("MC 存档区块编辑器处理区块 {} 失败", pos, exception);
                return ProcessResult.SKIPPED;
            }
        }

        private void maybeReportProgress() {
            int done = processed + skipped;
            if (done < progressMarker + 8 && done < total) {
                return;
            }
            progressMarker = done;
            LOGGER.info("MC 存档区块编辑器：地形重建进度 {}/{}（完成 {}，跳过 {}，队列剩余 {}）",
                    done, total, processed, skipped, queue.size());
        }

        private ProcessResult deferOrSkip(PendingChunk pending) {
            if (pending.retries++ < MAX_RETRIES) {
                queue.add(pending);
                return ProcessResult.DEFERRED;
            }
            skipped++;
            return ProcessResult.SKIPPED;
        }

        private boolean processStoredChunk(PendingChunk pending) throws Exception {
            return switch (operation) {
                case UPDATE -> updateIfPresent(pending.pos);
                case REGENERATE -> regenerate(pending);
                case DELETE -> eraseIfPresent(pending.pos);
            };
        }

        private boolean regenerate(PendingChunk pending) throws Exception {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            if (regenerationSession == null) {
                regenerationSession = new TerrainRegenerationSession(level, targetSeed);
            }
            boolean regionExists = AnvilChunkIo.regionFileExists(chunkRegionPath, pending.pos);
            CompoundTag current = regionExists
                    ? chunkMap.read(pending.pos).join().orElse(null) : null;
            var generated = regenerationSession.generate(pending.pos);
            if (generated instanceof ProtoChunk proto) {
                proto.setStatus(ChunkStatus.SURFACE);
                Heightmap.primeHeightmaps(proto, EnumSet.allOf(Heightmap.Types.class));
            }
            CompoundTag merged;
            if (current == null) {
                merged = ChunkSerializer.write(regenerationSession.genWorld(), generated);
            } else {
                merged = ChunkTerrainMerger.merge(
                        level, regenerationSession, generated, pending.pos, current, selectedChunks, targetSeed);
            }
            chunkMap.write(pending.pos, merged);
            if (AnvilChunkIo.regionFileExists(poiRegionPath, pending.pos)) {
                AnvilChunkIo.writeChunk(poiStorage, pending.pos, null);
            }
            ChunkPos pos = pending.pos;
            var future = level.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
            level.getServer().managedBlock(future::isDone);
            return true;
        }

        private void flushRegeneratedChunks() {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            chunkMap.flushWorker();
            try {
                poiStorage.flush();
            } catch (IOException exception) {
                LOGGER.warn("MC 存档区块编辑器刷新 POI 存储失败", exception);
            }
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
            if (!queue.isEmpty()) {
                return false;
            }
            flushAndClose(poiStorage);
            flushAndClose(entityStorage);
            if (regenerationSession != null) {
                TerrainRegenerationSession session = regenerationSession;
                regenerationSession = null;
                Util.backgroundExecutor().execute(() -> {
                    try {
                        session.close();
                    } catch (Exception ignored) {
                    }
                });
            }
            return true;
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

    private enum ProcessResult {
        SUCCESS,
        SKIPPED,
        DEFERRED
    }
}

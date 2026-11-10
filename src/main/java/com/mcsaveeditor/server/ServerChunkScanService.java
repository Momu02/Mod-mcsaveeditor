package com.mcsaveeditor.server;

import com.mcsaveeditor.network.ModNetworking;
import com.mcsaveeditor.network.packet.ChunkTimeEntry;
import com.mcsaveeditor.network.packet.ScanRequestPacket;
import com.mcsaveeditor.network.packet.ScanResultPacket;
import com.mcsaveeditor.network.packet.ScanSpeedPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ServerChunkScanService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Queue<ScanJob> JOBS = new ArrayDeque<>();
    private static final int DEFAULT_CHUNKS_PER_TICK = 32;
    private static final int MIN_CHUNKS_PER_TICK = 1;
    private static final int MAX_CHUNKS_PER_TICK = 256;
    private static volatile int chunksPerTick = DEFAULT_CHUNKS_PER_TICK;
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private ServerChunkScanService() {
    }

    public static void handleScanSpeedRequest(ServerPlayer player, ScanSpeedPacket request) {
        if (!player.hasPermissions(3)) {
            player.sendSystemMessage(Component.translatable("mcsaveeditor.permission.denied").withStyle(ChatFormatting.RED));
            return;
        }
        int value = Math.max(MIN_CHUNKS_PER_TICK, Math.min(request.chunksPerTick(), MAX_CHUNKS_PER_TICK));
        chunksPerTick = value;
        player.sendSystemMessage(Component.literal("MC 存档区块编辑器：扫描速度已调整为 " + value + " 区块/游戏刻"));
    }

    public static void handleScanRequest(ServerPlayer player, ScanRequestPacket request) {
        if (!player.hasPermissions(3)) {
            player.sendSystemMessage(Component.translatable("mcsaveeditor.permission.denied").withStyle(ChatFormatting.RED));
            return;
        }

        ServerLevel level = player.serverLevel();
        int radius = Math.min(Math.max(request.radiusChunks(), 1), ScanRequestPacket.MAX_RADIUS);
        int maxInhabitedMinutes = Math.max(request.maxInhabitedMinutes(), 0);
        long maxInhabitedTicks = Math.min((long) maxInhabitedMinutes * 1200L, Long.MAX_VALUE);
        List<ProtectedArea> protectedAreas = new ArrayList<>();
        int guardDistance = level.getServer().getPlayerList().getViewDistance() + 1;
        for (ServerPlayer onlinePlayer : level.players()) {
            protectedAreas.add(new ProtectedArea(onlinePlayer.chunkPosition(), guardDistance));
        }

        ScanJob job;
        try {
            job = new ScanJob(player, WorldStoragePaths.chunkRegion(level), request.centerChunkX(),
                    request.centerChunkZ(), request.limitRadius(), radius, request.limitInhabitedTime(),
                    maxInhabitedTicks, protectedAreas);
        } catch (RuntimeException exception) {
            LOGGER.error("MC 存档区块编辑器：创建扫描任务失败", exception);
            player.sendSystemMessage(Component.literal("MC 存档区块编辑器：创建扫描任务失败，请检查存档目录权限。")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        JOBS.removeIf(existing -> {
            if (existing.player == player) {
                existing.close();
                return true;
            }
            return false;
        });
        JOBS.add(job);
        player.sendSystemMessage(Component.literal("MC 存档区块编辑器：正在扫描已有区块……"));
    }

    public static void cancelScan(ServerPlayer player, boolean notify) {
        boolean cancelled = JOBS.removeIf(job -> {
            if (job.player == player) {
                job.close();
                return true;
            }
            return false;
        });
        if (cancelled && notify) {
            player.sendSystemMessage(Component.literal("MC 存档区块编辑器：已取消扫描。"));
        }
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) {
            return;
        }
        ScanJob job = JOBS.peek();
        try {
            job.tick();
            if (job.done()) {
                JOBS.poll();
                job.close();
                job.player.sendSystemMessage(Component.literal("MC 存档区块编辑器：扫描完成，共找到 " + job.found + " 个区块。"));
            }
        } catch (IOException | RuntimeException exception) {
            JOBS.poll();
            job.close();
            job.player.sendSystemMessage(Component.literal("MC 存档区块编辑器：扫描失败："
                    + exception.getClass().getSimpleName()));
        }
    }

    private static void sendBatch(ServerPlayer player, List<ChunkTimeEntry> batch) {
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ScanResultPacket(List.copyOf(batch)));
    }

    private record ProtectedArea(ChunkPos center, int distance) {
        boolean contains(ChunkPos pos) {
            return Math.max(Math.abs(center.x - pos.x), Math.abs(center.z - pos.z)) <= distance;
        }
    }

    private static final class ScanJob {
        private final ServerPlayer player;
        private final Path regionPath;
        private final int centerX;
        private final int centerZ;
        private final boolean limitRadius;
        private final int radius;
        private final boolean limitInhabitedTime;
        private final long maxInhabitedTicks;
        private final List<ProtectedArea> protectedAreas;
        private final List<RegionPos> regions;
        private RegionFileStorage storage;
        private int regionIndex;
        private int localIndex;
        private int found;
        private boolean finished;
        private boolean closed;
        private boolean warnedCorruptChunk;

        private ScanJob(ServerPlayer player, Path regionPath, int centerX, int centerZ, boolean limitRadius,
                        int radius, boolean limitInhabitedTime, long maxInhabitedTicks,
                        List<ProtectedArea> protectedAreas) {
            this.player = player;
            this.regionPath = regionPath;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.limitRadius = limitRadius;
            this.radius = radius;
            this.limitInhabitedTime = limitInhabitedTime;
            this.maxInhabitedTicks = maxInhabitedTicks;
            this.protectedAreas = protectedAreas;
            this.regions = listRegions(regionPath, centerX, centerZ, limitRadius, radius);
            this.finished = regions.isEmpty();
        }

        private void tick() throws IOException {
            if (finished) {
                return;
            }
            if (storage == null) {
                storage = new RegionFileStorage(regionPath, true);
            }

            List<ChunkTimeEntry> batch = new ArrayList<>(chunksPerTick);
            int processed = 0;
            int limit = chunksPerTick;
            while (processed++ < limit && !finished) {
                RegionPos region = regions.get(regionIndex);
                int x = (region.x() << 5) + (localIndex & 31);
                int z = (region.z() << 5) + (localIndex >> 5);
                ChunkPos pos = new ChunkPos(x, z);
                if (!limitRadius || Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius) {
                    CompoundTag tag;
                    try {
                        tag = storage.read(pos);
                    } catch (IOException exception) {
                        if (!warnedCorruptChunk) {
                            warnedCorruptChunk = true;
                            LOGGER.warn("MC 存档区块编辑器：区块 {} 读取失败，扫描将跳过损坏区块", pos, exception);
                        }
                        advance();
                        continue;
                    }
                    if (tag != null && !tag.isEmpty()) {
                        long inhabitedTicks = AnvilChunkIo.readInhabitedTicks(tag);
                        if (limitInhabitedTime && inhabitedTicks > maxInhabitedTicks) {
                            advance();
                            continue;
                        }
                        boolean protectedChunk = protectedAreas.stream().anyMatch(area -> area.contains(pos));
                        batch.add(new ChunkTimeEntry(x, z, inhabitedTicks,
                                ChunkTerrainSampler.sample(tag), protectedChunk));
                        found++;
                    }
                }
                advance();
            }
            if (!batch.isEmpty()) {
                sendBatch(player, batch);
            }
        }

        private void advance() {
            if (++localIndex >= 32 * 32) {
                localIndex = 0;
                if (++regionIndex >= regions.size()) {
                    finished = true;
                }
            }
        }

        private boolean done() {
            return finished;
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (storage != null) {
                try {
                    storage.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static List<RegionPos> listRegions(Path regionPath, int centerX, int centerZ,
                                               boolean limitRadius, int radius) {
        if (!Files.isDirectory(regionPath)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(regionPath)) {
            return files.map(path -> parseRegion(path.getFileName().toString()))
                    .filter(java.util.Objects::nonNull)
                    .filter(region -> !limitRadius || region.intersects(centerX, centerZ, radius))
                    .sorted(Comparator.comparingInt(RegionPos::x).thenComparingInt(RegionPos::z))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static RegionPos parseRegion(String fileName) {
        Matcher matcher = REGION_FILE.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new RegionPos(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record RegionPos(int x, int z) {
        boolean intersects(int centerX, int centerZ, int radius) {
            int minX = x << 5;
            int minZ = z << 5;
            return minX <= centerX + radius && minX + 31 >= centerX - radius
                    && minZ <= centerZ + radius && minZ + 31 >= centerZ - radius;
        }
    }
}

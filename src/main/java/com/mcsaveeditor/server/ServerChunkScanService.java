package com.mcsaveeditor.server;

import com.mcsaveeditor.network.ModNetworking;
import com.mcsaveeditor.network.packet.ChunkTimeEntry;
import com.mcsaveeditor.network.packet.ScanRequestPacket;
import com.mcsaveeditor.network.packet.ScanResultPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class ServerChunkScanService {
    private static final ExecutorService SCAN_EXECUTOR = Executors.newSingleThreadExecutor(new ScanThreadFactory());
    private static final ConcurrentHashMap<UUID, AtomicLong> SCAN_GENERATIONS = new ConcurrentHashMap<>();

    private ServerChunkScanService() {
    }

    public static void handleScanRequest(ServerPlayer player, ScanRequestPacket request) {
        if (!player.hasPermissions(3)) {
            player.sendSystemMessage(Component.translatable("mcsaveeditor.permission.denied").withStyle(ChatFormatting.RED));
            return;
        }

        ServerLevel level = player.serverLevel();
        int radius = Math.min(Math.max(request.radiusChunks(), 1), ScanRequestPacket.MAX_RADIUS);
        Path chunkRegionPath = WorldStoragePaths.chunkRegion(level);
        AtomicLong playerGeneration = SCAN_GENERATIONS.computeIfAbsent(player.getUUID(), ignored -> new AtomicLong());
        long generation = playerGeneration.incrementAndGet();

        player.sendSystemMessage(Component.literal("MC Save Editor: scanning chunk NBT asynchronously..."));
        SCAN_EXECUTOR.execute(() -> scan(player, chunkRegionPath, request.centerChunkX(), request.centerChunkZ(), radius,
                playerGeneration, generation));
    }

    private static void scan(ServerPlayer player, Path chunkRegionPath, int centerX, int centerZ, int radius,
                             AtomicLong playerGeneration, long generation) {
        List<ChunkTimeEntry> batch = new ArrayList<>(ScanResultPacket.MAX_RESULTS);
        int sampledChunks = 0;

        try (RegionFileStorage storage = new RegionFileStorage(chunkRegionPath, true)) {
            for (int distance = 0; distance <= radius; distance++) {
                for (int x = centerX - distance; x <= centerX + distance; x++) {
                    for (int z = centerZ - distance; z <= centerZ + distance; z++) {
                        if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) != distance) {
                            continue;
                        }
                        if (generation != playerGeneration.get()) {
                            return;
                        }
                        ChunkPos pos = new ChunkPos(x, z);
                        if (!AnvilChunkIo.regionFileExists(chunkRegionPath, pos)) {
                            continue;
                        }
                        try {
                            net.minecraft.nbt.CompoundTag tag = storage.read(pos);
                            if (tag != null && !tag.isEmpty()) {
                                long ticks = AnvilChunkIo.readInhabitedTicks(tag);
                                batch.add(new ChunkTimeEntry(x, z, ticks, ChunkTerrainSampler.sample(tag)));
                                if (batch.size() >= ScanResultPacket.MAX_RESULTS) {
                                    sendBatch(player, batch);
                                    batch = new ArrayList<>(ScanResultPacket.MAX_RESULTS);
                                }
                                if (++sampledChunks % 64 == 0) {
                                    LockSupport.parkNanos(1_000_000L);
                                }
                            }
                        } catch (IOException | RuntimeException ignored) {
                            // Bad or locked chunks are skipped so one damaged entry does not abort the whole scan.
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            player.getServer().execute(() -> player.sendSystemMessage(Component.literal("MC Save Editor: scan failed to open region storage.")));
            return;
        }

        if (!batch.isEmpty()) {
            sendBatch(player, batch);
        }
        player.getServer().execute(() -> player.sendSystemMessage(Component.literal("MC Save Editor: scan complete.")));
    }

    private static void sendBatch(ServerPlayer player, List<ChunkTimeEntry> batch) {
        List<ChunkTimeEntry> immutableBatch = List.copyOf(batch);
        player.getServer().execute(() -> ModNetworking.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new ScanResultPacket(immutableBatch)));
    }

    private static final class ScanThreadFactory implements ThreadFactory {
        private int index;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MCSaveEditor-Scan-" + index++);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    }
}

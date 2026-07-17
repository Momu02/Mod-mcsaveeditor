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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class ServerChunkScanService {
    private static final ExecutorService SCAN_EXECUTOR = Executors.newFixedThreadPool(2, new ScanThreadFactory());

    private ServerChunkScanService() {
    }

    public static void handleScanRequest(ServerPlayer player, ScanRequestPacket request) {
        if (!player.hasPermissions(3)) {
            player.sendSystemMessage(Component.translatable("mcsaveeditor.permission.denied").withStyle(ChatFormatting.RED));
            return;
        }

        ServerLevel level = player.serverLevel();
        int radius = Math.min(Math.max(request.radiusChunks(), 1), ScanRequestPacket.MAX_RADIUS);
        int maxMinutes = Math.max(request.maxMinutes(), 0);
        Path chunkRegionPath = WorldStoragePaths.chunkRegion(level);

        player.sendSystemMessage(Component.literal("MC Save Editor: scanning chunk NBT asynchronously..."));
        SCAN_EXECUTOR.execute(() -> scan(player, chunkRegionPath, request.centerChunkX(), request.centerChunkZ(), radius, maxMinutes));
    }

    private static void scan(ServerPlayer player, Path chunkRegionPath, int centerX, int centerZ, int radius, int maxMinutes) {
        long thresholdTicks = maxMinutes * 1200L;
        List<ChunkTimeEntry> batch = new ArrayList<>(ScanResultPacket.MAX_RESULTS);

        try (RegionFileStorage storage = new RegionFileStorage(chunkRegionPath, true)) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    ChunkPos pos = new ChunkPos(x, z);
                    if (!AnvilChunkIo.regionFileExists(chunkRegionPath, pos)) {
                        continue;
                    }
                    try {
                        long ticks = AnvilChunkIo.readInhabitedTicks(storage, pos);
                        if (ticks >= 0L && ticks < thresholdTicks) {
                            batch.add(new ChunkTimeEntry(x, z, ticks));
                            if (batch.size() >= ScanResultPacket.MAX_RESULTS) {
                                sendBatch(player, batch);
                                batch = new ArrayList<>(ScanResultPacket.MAX_RESULTS);
                            }
                        }
                    } catch (IOException ignored) {
                        // Bad or locked chunks are skipped so one damaged entry does not abort the whole scan.
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
            return thread;
        }
    }
}

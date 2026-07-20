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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public final class ServerChunkScanService {
    private static final Queue<ScanJob> JOBS = new ArrayDeque<>();
    private static final int CHUNKS_PER_TICK = 32;

    private ServerChunkScanService() {
    }

    public static void handleScanRequest(ServerPlayer player, ScanRequestPacket request) {
        if (!player.hasPermissions(3)) {
            player.sendSystemMessage(Component.translatable("mcsaveeditor.permission.denied").withStyle(ChatFormatting.RED));
            return;
        }

        ServerLevel level = player.serverLevel();
        int radius = Math.min(Math.max(request.radiusChunks(), 1), ScanRequestPacket.MAX_RADIUS);
        List<ProtectedArea> protectedAreas = new ArrayList<>();
        int guardDistance = level.getServer().getPlayerList().getViewDistance() + 2;
        for (ServerPlayer onlinePlayer : level.players()) {
            protectedAreas.add(new ProtectedArea(onlinePlayer.chunkPosition(), guardDistance));
        }

        ScanJob job = new ScanJob(player, WorldStoragePaths.chunkRegion(level),
                request.centerChunkX(), request.centerChunkZ(), radius, protectedAreas);
        JOBS.removeIf(existing -> {
            if (existing.player == player) {
                existing.close();
                return true;
            }
            return false;
        });
        JOBS.add(job);
        player.sendSystemMessage(Component.literal("MC Save Editor: scanning saved chunks in the requested area..."));
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
                job.player.sendSystemMessage(Component.literal("MC Save Editor: scan complete. Found " + job.found + " saved chunks."));
            }
        } catch (IOException | RuntimeException exception) {
            JOBS.poll();
            job.close();
            job.player.sendSystemMessage(Component.literal("MC Save Editor: scan failed: " + exception.getClass().getSimpleName()));
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
        private final int radius;
        private final List<ProtectedArea> protectedAreas;
        private RegionFileStorage storage;
        private int x;
        private int z;
        private int found;
        private boolean finished;

        private ScanJob(ServerPlayer player, Path regionPath, int centerX, int centerZ, int radius,
                        List<ProtectedArea> protectedAreas) {
            this.player = player;
            this.regionPath = regionPath;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.protectedAreas = protectedAreas;
            this.x = centerX - radius;
            this.z = centerZ - radius;
        }

        private void tick() throws IOException {
            if (finished) {
                return;
            }
            if (storage == null) {
                storage = new RegionFileStorage(regionPath, true);
            }

            List<ChunkTimeEntry> batch = new ArrayList<>(CHUNKS_PER_TICK);
            int processed = 0;
            while (processed++ < CHUNKS_PER_TICK && !finished) {
                ChunkPos pos = new ChunkPos(x, z);
                if (AnvilChunkIo.regionFileExists(regionPath, pos)) {
                    net.minecraft.nbt.CompoundTag tag = storage.read(pos);
                    if (tag != null && !tag.isEmpty()) {
                        boolean protectedChunk = protectedAreas.stream().anyMatch(area -> area.contains(pos));
                        batch.add(new ChunkTimeEntry(x, z, AnvilChunkIo.readInhabitedTicks(tag),
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
            if (++z > centerZ + radius) {
                z = centerZ - radius;
                if (++x > centerX + radius) {
                    finished = true;
                }
            }
        }

        private boolean done() {
            return finished;
        }

        private void close() {
            if (storage != null) {
                try {
                    storage.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}

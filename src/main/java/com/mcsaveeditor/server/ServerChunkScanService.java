package com.mcsaveeditor.server;

import com.mcsaveeditor.network.ModNetworking;
import com.mcsaveeditor.network.packet.ChunkTimeEntry;
import com.mcsaveeditor.network.packet.ScanRequestPacket;
import com.mcsaveeditor.network.packet.ScanResultPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public final class ServerChunkScanService {
    private static final Queue<ScanJob> JOBS = new ArrayDeque<>();
    private static final int CHUNKS_PER_TICK = 8;

    private ServerChunkScanService() {
    }

    public static void handleScanRequest(ServerPlayer player, ScanRequestPacket request) {
        if (!player.hasPermissions(3)) {
            player.sendSystemMessage(Component.translatable("mcsaveeditor.permission.denied").withStyle(ChatFormatting.RED));
            return;
        }

        ServerLevel level = player.serverLevel();
        int radius = Math.min(Math.max(request.radiusChunks(), 1), ScanRequestPacket.MAX_RADIUS);
        List<LevelChunk> chunks = new ArrayList<>();
        for (int x = request.centerChunkX() - radius; x <= request.centerChunkX() + radius; x++) {
            for (int z = request.centerChunkZ() - radius; z <= request.centerChunkZ() + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
        }
        JOBS.removeIf(job -> job.player == player);
        JOBS.add(new ScanJob(player, chunks));
        player.sendSystemMessage(Component.literal("MC Save Editor: queued " + chunks.size() + " loaded chunks."));
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
                job.player.sendSystemMessage(Component.literal("MC Save Editor: scan complete."));
            }
        } catch (RuntimeException exception) {
            JOBS.poll();
            job.player.sendSystemMessage(Component.literal("MC Save Editor: scan failed: " + exception.getClass().getSimpleName()));
        }
    }

    private static void sendBatch(ServerPlayer player, List<ChunkTimeEntry> batch) {
        List<ChunkTimeEntry> immutableBatch = List.copyOf(batch);
        player.getServer().execute(() -> ModNetworking.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new ScanResultPacket(immutableBatch)));
    }

    private static final class ScanJob {
        private final ServerPlayer player;
        private final List<LevelChunk> chunks;
        private int index;

        private ScanJob(ServerPlayer player, List<LevelChunk> chunks) {
            this.player = player;
            this.chunks = chunks;
        }

        private void tick() {
            List<ChunkTimeEntry> batch = new ArrayList<>(CHUNKS_PER_TICK);
            for (int count = 0; count < CHUNKS_PER_TICK && index < chunks.size(); count++, index++) {
                LevelChunk chunk = chunks.get(index);
                batch.add(new ChunkTimeEntry(chunk.getPos().x, chunk.getPos().z, chunk.getInhabitedTime(),
                        ChunkTerrainSampler.sample(chunk)));
            }
            if (!batch.isEmpty()) {
                sendBatch(player, batch);
            }
        }

        private boolean done() {
            return index >= chunks.size();
        }
    }
}

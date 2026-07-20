package com.mcsaveeditor.server;

import com.mcsaveeditor.network.packet.ChunkTimeEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Locale;

final class ChunkTerrainSampler {
    private static final int AIR_COLOR = 0xFF17202A;

    private ChunkTerrainSampler() {
    }

    static int[] sample(CompoundTag chunk) {
        int[] colors = new int[ChunkTimeEntry.MAP_PIXELS];
        Section[] sections = readSections(chunk);

        for (int mapZ = 0; mapZ < ChunkTimeEntry.MAP_SIZE; mapZ++) {
            for (int mapX = 0; mapX < ChunkTimeEntry.MAP_SIZE; mapX++) {
                int blockX = mapX * 2 + 1;
                int blockZ = mapZ * 2 + 1;
                colors[mapZ * ChunkTimeEntry.MAP_SIZE + mapX] = sampleColumn(sections, blockX, blockZ);
            }
        }
        return colors;
    }

    static int[] sample(LevelChunk chunk) {
        int[] colors = new int[ChunkTimeEntry.MAP_PIXELS];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int mapZ = 0; mapZ < ChunkTimeEntry.MAP_SIZE; mapZ++) {
            for (int mapX = 0; mapX < ChunkTimeEntry.MAP_SIZE; mapX++) {
                int x = mapX * 2 + 1;
                int z = mapZ * 2 + 1;
                int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (topY < chunk.getMinBuildHeight()) {
                    colors[mapZ * ChunkTimeEntry.MAP_SIZE + mapX] = AIR_COLOR;
                } else {
                    String block = BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos.set(x, topY, z)).getBlock()).getPath();
                    colors[mapZ * ChunkTimeEntry.MAP_SIZE + mapX] = shade(baseColor(block), topY);
                }
            }
        }
        return colors;
    }

    private static Section[] readSections(CompoundTag chunk) {
        ListTag sectionTags = chunk.getList("sections", 10);
        if (sectionTags.isEmpty() && chunk.contains("Level", 10)) {
            sectionTags = chunk.getCompound("Level").getList("Sections", 10);
        }

        Section[] sections = new Section[sectionTags.size()];
        for (int index = 0; index < sectionTags.size(); index++) {
            CompoundTag sectionTag = sectionTags.getCompound(index);
            CompoundTag states = sectionTag.contains("block_states", 10)
                    ? sectionTag.getCompound("block_states")
                    : sectionTag.getCompound("BlockStates");
            ListTag paletteTag = states.getList("palette", 10);
            if (paletteTag.isEmpty()) {
                paletteTag = sectionTag.getList("Palette", 10);
            }
            if (paletteTag.isEmpty()) {
                continue;
            }

            String[] palette = new String[paletteTag.size()];
            for (int paletteIndex = 0; paletteIndex < palette.length; paletteIndex++) {
                palette[paletteIndex] = paletteTag.getCompound(paletteIndex).getString("Name");
            }
            long[] data = states.contains("data", 12) ? states.getLongArray("data") : sectionTag.getLongArray("BlockStates");
            sections[index] = new Section(sectionTag.getByte("Y"), palette, data);
        }
        return sections;
    }

    private static int sampleColumn(Section[] sections, int blockX, int blockZ) {
        int bestY = Integer.MIN_VALUE;
        String bestBlock = null;
        for (Section section : sections) {
            if (section == null || section.topY() <= bestY || section.isAirOnly()) {
                continue;
            }
            for (int localY = 15; localY >= 0; localY--) {
                String block = section.blockAt(blockX, localY, blockZ);
                if (!isAir(block)) {
                    bestY = section.sectionY * 16 + localY;
                    bestBlock = block;
                    break;
                }
            }
        }
        return bestBlock == null ? AIR_COLOR : shade(baseColor(bestBlock), bestY);
    }

    private static boolean isAir(String block) {
        return block.isEmpty() || block.endsWith(":air") || block.endsWith("_air");
    }

    private static int baseColor(String blockName) {
        String name = blockName.toLowerCase(Locale.ROOT);
        if (name.contains("water")) return 0xFF356CB5;
        if (name.contains("lava")) return 0xFFE66A20;
        if (name.equals("grass_block") || name.contains("grass") || name.contains("moss")) return 0xFF5D9348;
        if (name.contains("leaves") || name.contains("vine")) return 0xFF39763D;
        if (name.contains("sand") || name.contains("end_stone")) return 0xFFD8CA82;
        if (name.contains("snow") || name.contains("ice")) return 0xFFDDEAF0;
        if (name.contains("dirt") || name.contains("mud") || name.contains("podzol")) return 0xFF78553A;
        if (name.contains("log") || name.contains("wood") || name.contains("planks")) return 0xFFA67B4E;
        if (name.contains("brick") || name.contains("terracotta")) return 0xFFA4513E;
        if (name.contains("copper")) return 0xFFB66E4A;
        if (name.contains("glass")) return 0xFF9FC3CA;
        if (name.contains("concrete") || name.contains("wool")) return dyedColor(name);
        if (name.contains("stone") || name.contains("ore") || name.contains("deepslate")
                || name.contains("cobble") || name.contains("andesite") || name.contains("diorite")
                || name.contains("granite")) return 0xFF808080;
        if (name.contains("nether") || name.contains("nylium")) return 0xFF7F3434;
        return 0xFF70805F;
    }

    private static int dyedColor(String name) {
        if (name.contains("white")) return 0xFFE6E6E6;
        if (name.contains("light_gray")) return 0xFFAAAAAA;
        if (name.contains("gray")) return 0xFF555555;
        if (name.contains("black")) return 0xFF252525;
        if (name.contains("red")) return 0xFFB43A32;
        if (name.contains("orange")) return 0xFFD87F32;
        if (name.contains("yellow")) return 0xFFD9C43B;
        if (name.contains("lime")) return 0xFF78A933;
        if (name.contains("green")) return 0xFF3E7138;
        if (name.contains("cyan")) return 0xFF388A91;
        if (name.contains("light_blue")) return 0xFF69A9C8;
        if (name.contains("blue")) return 0xFF3D4F9B;
        if (name.contains("purple")) return 0xFF7D4596;
        if (name.contains("magenta")) return 0xFFA84F9C;
        if (name.contains("pink")) return 0xFFD5839B;
        if (name.contains("brown")) return 0xFF724C35;
        return 0xFF888888;
    }

    private static int shade(int color, int height) {
        int adjustment = Math.max(-22, Math.min(28, (height - 64) / 4));
        int red = Math.max(0, Math.min(255, ((color >> 16) & 0xFF) + adjustment));
        int green = Math.max(0, Math.min(255, ((color >> 8) & 0xFF) + adjustment));
        int blue = Math.max(0, Math.min(255, (color & 0xFF) + adjustment));
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private record Section(int sectionY, String[] palette, long[] data) {
        int topY() {
            return sectionY * 16 + 15;
        }

        boolean isAirOnly() {
            return palette.length == 1 && isAir(palette[0]);
        }

        String blockAt(int x, int y, int z) {
            if (palette.length == 1 || data.length == 0) {
                return palette[0];
            }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            int valuesPerLong = 64 / bits;
            int blockIndex = (y << 8) | (z << 4) | x;
            int longIndex = blockIndex / valuesPerLong;
            if (longIndex >= data.length) {
                return palette[0];
            }
            int offset = (blockIndex % valuesPerLong) * bits;
            int paletteIndex = (int) (data[longIndex] >>> offset & (1L << bits) - 1L);
            return paletteIndex < palette.length ? palette[paletteIndex] : palette[0];
        }
    }
}

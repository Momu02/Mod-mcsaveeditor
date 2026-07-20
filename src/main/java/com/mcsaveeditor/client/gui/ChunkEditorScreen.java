package com.mcsaveeditor.client.gui;

import com.mcsaveeditor.network.ModNetworking;
import com.mcsaveeditor.network.packet.CancelScanPacket;
import com.mcsaveeditor.network.packet.ChunkTimeEntry;
import com.mcsaveeditor.network.packet.DeleteChunksPacket;
import com.mcsaveeditor.network.packet.ScanRequestPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkEditorScreen extends Screen {
    private static final int SIDEBAR_WIDTH = 188;
    private static final int MIN_CELL = 4;
    private static final int MAX_CELL = 48;
    private static final int GRID_LINE_COLOR = 0x55303030;
    private static final int AXIS_COLOR = 0xAAE6E6E6;
    private static final int SELECTED_COLOR = 0x66E23535;
    private static final int PROTECTED_COLOR = 0x88F2C230;
    private static final int PREVIEW_COLOR = 0x55FF6060;

    private final int centerChunkX;
    private final int centerChunkZ;
    private final Map<Long, ChunkTimeEntry> scanned = new HashMap<>();
    private final Set<Long> selected = new HashSet<>();

    private EditBox radiusBox;
    private double panChunkX;
    private double panChunkZ;
    private double cellSize = 14.0D;
    private boolean panning;
    private boolean selecting;
    private boolean deselecting;
    private int dragStartChunkX;
    private int dragStartChunkZ;
    private int dragEndChunkX;
    private int dragEndChunkZ;

    public ChunkEditorScreen(int centerChunkX, int centerChunkZ) {
        super(Component.literal("MC Save Editor"));
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
    }

    @Override
    protected void init() {
        int x = 12;
        int y = 44;

        radiusBox = new EditBox(font, x, y, 72, 20, Component.literal("Radius"));
        radiusBox.setValue("128");
        addRenderableWidget(radiusBox);

        addRenderableWidget(Button.builder(Component.literal("Cancel Scan"), button -> sendCancelScan())
                .bounds(x + 84, y, 72, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Scan"), button -> sendScan())
                .bounds(x, y + 28, 72, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Clear"), button -> selected.clear())
                .bounds(x + 84, y + 28, 72, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Confirm Delete").withStyle(ChatFormatting.RED), button -> sendDelete())
                .bounds(x, y + 64, 156, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(x, y + 92, 156, 20)
                .build());
    }

    public void acceptScanResult(List<ChunkTimeEntry> entries) {
        for (ChunkTimeEntry entry : entries) {
            long key = ChunkPos.asLong(entry.chunkX(), entry.chunkZ());
            scanned.put(key, entry);
        }
    }

    public void acceptDeletedChunks(List<ChunkPos> chunks) {
        for (ChunkPos pos : chunks) {
            long key = pos.toLong();
            scanned.remove(key);
            selected.remove(key);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        renderMap(graphics, mouseX, mouseY);
        renderSidebar(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSidebar(GuiGraphics graphics) {
        graphics.fill(0, 0, SIDEBAR_WIDTH, height, 0xE81B1D22);
        graphics.fill(SIDEBAR_WIDTH - 1, 0, SIDEBAR_WIDTH, height, 0xFF4A4D55);
        graphics.drawString(font, "MC Save Editor", 12, 14, 0xFFFFFFFF);
        graphics.drawString(font, "Scan radius (saved chunks)", 12, 32, 0xFFBFC5D2);
        graphics.drawString(font, "Selected: " + selected.size(), 12, 168, 0xFFFFFFFF);
        graphics.drawString(font, "Scanned: " + scanned.size(), 12, 182, 0xFFBFC5D2);
        graphics.drawString(font, "Yellow: protected", 12, 196, 0xFFF2C230);
        graphics.drawString(font, "Center: " + centerChunkX + ", " + centerChunkZ, 12, 218, 0xFFBFC5D2);
        graphics.drawString(font, "Zoom: " + Math.round(cellSize) + " px", 12, 232, 0xFFBFC5D2);
    }

    private void renderMap(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = SIDEBAR_WIDTH;
        int right = width;
        int top = 0;
        int bottom = height;
        graphics.fill(left, top, right, bottom, 0xFF101216);

        int minChunkX = screenToChunkX(left);
        int maxChunkX = screenToChunkX(right) + 1;
        int minChunkZ = screenToChunkZ(top);
        int maxChunkZ = screenToChunkZ(bottom) + 1;

        drawTerrain(graphics, minChunkX, maxChunkX, minChunkZ, maxChunkZ);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int screenX = chunkToScreenX(chunkX);
            int color = chunkX == centerChunkX ? AXIS_COLOR : GRID_LINE_COLOR;
            graphics.vLine(screenX, top, bottom, color);
        }

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            int screenY = chunkToScreenY(chunkZ);
            int color = chunkZ == centerChunkZ ? AXIS_COLOR : GRID_LINE_COLOR;
            graphics.hLine(left, right, screenY, color);
        }

        drawVisibleOverlays(graphics, selected, SELECTED_COLOR, minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        drawProtectedOverlays(graphics, minChunkX, maxChunkX, minChunkZ, maxChunkZ);

        if ((selecting || deselecting) && mouseX >= SIDEBAR_WIDTH) {
            drawPreview(graphics);
        }

        if (mouseX >= SIDEBAR_WIDTH) {
            int chunkX = screenToChunkX(mouseX);
            int chunkZ = screenToChunkZ(mouseY);
            graphics.drawString(font, chunkX + ", " + chunkZ, mouseX + 8, mouseY + 8, 0xFFFFFFFF);
        }
    }

    private void drawTerrain(GuiGraphics graphics, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        int chunkStep = Math.max(1, (int) Math.ceil(10.0D / cellSize));
        int sampleStep = chunkStep > 1 ? ChunkTimeEntry.MAP_SIZE
                : Math.max(1, Math.min(ChunkTimeEntry.MAP_SIZE, (int) Math.ceil(48.0D / cellSize)));
        minChunkX = Math.floorDiv(minChunkX, chunkStep) * chunkStep;
        minChunkZ = Math.floorDiv(minChunkZ, chunkStep) * chunkStep;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            if ((chunkX - minChunkX) % chunkStep != 0) {
                continue;
            }
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if ((chunkZ - minChunkZ) % chunkStep != 0) {
                    continue;
                }
                ChunkTimeEntry entry = findTerrainEntry(chunkX, chunkZ, chunkStep);
                if (entry == null || entry.terrainColors().length != ChunkTimeEntry.MAP_PIXELS) {
                    continue;
                }
                if (chunkStep > 1) {
                    graphics.fill(Math.max(chunkToScreenX(chunkX), SIDEBAR_WIDTH), Math.max(chunkToScreenY(chunkZ), 0),
                            Math.min(chunkToScreenX(chunkX + chunkStep), width), Math.min(chunkToScreenY(chunkZ + chunkStep), height),
                            entry.terrainColors()[ChunkTimeEntry.MAP_PIXELS / 2]);
                    continue;
                }
                for (int sampleZ = 0; sampleZ < ChunkTimeEntry.MAP_SIZE; sampleZ += sampleStep) {
                    for (int sampleX = 0; sampleX < ChunkTimeEntry.MAP_SIZE; sampleX += sampleStep) {
                        int x0 = sampleToScreenX(chunkX, sampleX);
                        int y0 = sampleToScreenY(chunkZ, sampleZ);
                        int x1 = sampleToScreenX(chunkX, Math.min(sampleX + sampleStep, ChunkTimeEntry.MAP_SIZE));
                        int y1 = sampleToScreenY(chunkZ, Math.min(sampleZ + sampleStep, ChunkTimeEntry.MAP_SIZE));
                        graphics.fill(Math.max(x0, SIDEBAR_WIDTH), Math.max(y0, 0), Math.min(x1, width),
                                Math.min(y1, height), entry.terrainColors()[sampleZ * ChunkTimeEntry.MAP_SIZE + sampleX]);
                    }
                }
            }
        }
    }

    private ChunkTimeEntry findTerrainEntry(int chunkX, int chunkZ, int chunkStep) {
        for (int offsetX = 0; offsetX < chunkStep; offsetX++) {
            for (int offsetZ = 0; offsetZ < chunkStep; offsetZ++) {
                ChunkTimeEntry entry = scanned.get(ChunkPos.asLong(chunkX + offsetX, chunkZ + offsetZ));
                if (entry != null) {
                    return entry;
                }
            }
        }
        return null;
    }

    private void drawVisibleOverlays(GuiGraphics graphics, Set<Long> chunks, int color,
                                     int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        int chunkStep = Math.max(1, (int) Math.ceil(6.0D / cellSize));
        minChunkX = Math.floorDiv(minChunkX, chunkStep) * chunkStep;
        minChunkZ = Math.floorDiv(minChunkZ, chunkStep) * chunkStep;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            if ((chunkX - minChunkX) % chunkStep != 0) {
                continue;
            }
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if ((chunkZ - minChunkZ) % chunkStep != 0
                        || !containsChunk(chunks, chunkX, chunkZ, chunkStep)) {
                    continue;
                }
                int x0 = chunkToScreenX(chunkX);
                int y0 = chunkToScreenY(chunkZ);
                int x1 = chunkToScreenX(chunkX + chunkStep);
                int y1 = chunkToScreenY(chunkZ + chunkStep);
                graphics.fill(Math.max(x0, SIDEBAR_WIDTH), Math.max(y0, 0), Math.min(x1, width), Math.min(y1, height), color);
            }
        }
    }

    private void drawProtectedOverlays(GuiGraphics graphics, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        for (ChunkTimeEntry entry : scanned.values()) {
            if (!entry.protectedChunk()) {
                continue;
            }
            int x0 = chunkToScreenX(entry.chunkX());
            int y0 = chunkToScreenY(entry.chunkZ());
            int x1 = chunkToScreenX(entry.chunkX() + 1);
            int y1 = chunkToScreenY(entry.chunkZ() + 1);
            if (x1 >= SIDEBAR_WIDTH && x0 <= width && y1 >= 0 && y0 <= height) {
                graphics.fill(Math.max(x0, SIDEBAR_WIDTH), Math.max(y0, 0), Math.min(x1, width), Math.min(y1, height), PROTECTED_COLOR);
            }
        }
    }

    private boolean containsChunk(Set<Long> chunks, int chunkX, int chunkZ, int chunkStep) {
        for (int offsetX = 0; offsetX < chunkStep; offsetX++) {
            for (int offsetZ = 0; offsetZ < chunkStep; offsetZ++) {
                if (chunks.contains(ChunkPos.asLong(chunkX + offsetX, chunkZ + offsetZ))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int sampleToScreenX(int chunkX, int sampleX) {
        return (int) Math.floor(gridCenterX()
                + (chunkX + sampleX / (double) ChunkTimeEntry.MAP_SIZE - centerChunkX - panChunkX) * cellSize);
    }

    private int sampleToScreenY(int chunkZ, int sampleZ) {
        return (int) Math.floor(gridCenterY()
                + (chunkZ + sampleZ / (double) ChunkTimeEntry.MAP_SIZE - centerChunkZ - panChunkZ) * cellSize);
    }

    private void drawPreview(GuiGraphics graphics) {
        int minX = Math.min(dragStartChunkX, dragEndChunkX);
        int maxX = Math.max(dragStartChunkX, dragEndChunkX);
        int minZ = Math.min(dragStartChunkZ, dragEndChunkZ);
        int maxZ = Math.max(dragStartChunkZ, dragEndChunkZ);
        int x0 = Math.max(chunkToScreenX(minX), SIDEBAR_WIDTH);
        int y0 = Math.max(chunkToScreenY(minZ), 0);
        int x1 = Math.min(chunkToScreenX(maxX + 1), width);
        int y1 = Math.min(chunkToScreenY(maxZ + 1), height);
        graphics.fill(x0, y0, x1, y1, PREVIEW_COLOR);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX < SIDEBAR_WIDTH) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        dragStartChunkX = screenToChunkX(mouseX);
        dragStartChunkZ = screenToChunkZ(mouseY);
        dragEndChunkX = dragStartChunkX;
        dragEndChunkZ = dragStartChunkZ;

        if (button == 0) {
            selecting = true;
            return true;
        }
        if (button == 1) {
            deselecting = true;
            return true;
        }
        if (button == 2) {
            panning = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && button == 2) {
            panChunkX -= dragX / cellSize;
            panChunkZ -= dragY / cellSize;
            return true;
        }
        if ((selecting && button == 0) || (deselecting && button == 1)) {
            dragEndChunkX = screenToChunkX(mouseX);
            dragEndChunkZ = screenToChunkZ(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (selecting && button == 0) {
            applySelection(true);
            selecting = false;
            return true;
        }
        if (deselecting && button == 1) {
            applySelection(false);
            deselecting = false;
            return true;
        }
        if (panning && button == 2) {
            panning = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < SIDEBAR_WIDTH) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        double beforeX = screenToWorldChunkX(mouseX);
        double beforeZ = screenToWorldChunkZ(mouseY);
        cellSize = Math.max(MIN_CELL, Math.min(MAX_CELL, cellSize + delta * 2.0D));
        double afterX = screenToWorldChunkX(mouseX);
        double afterZ = screenToWorldChunkZ(mouseY);
        panChunkX += beforeX - afterX;
        panChunkZ += beforeZ - afterZ;
        return true;
    }

    private void applySelection(boolean add) {
        int minX = Math.min(dragStartChunkX, dragEndChunkX);
        int maxX = Math.max(dragStartChunkX, dragEndChunkX);
        int minZ = Math.min(dragStartChunkZ, dragEndChunkZ);
        int maxZ = Math.max(dragStartChunkZ, dragEndChunkZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long key = ChunkPos.asLong(x, z);
                ChunkTimeEntry entry = scanned.get(key);
                if (entry != null && entry.protectedChunk()) {
                    continue;
                }
                if (add) {
                    selected.add(key);
                } else {
                    selected.remove(key);
                }
            }
        }
    }

    private void sendScan() {
        int radius = parsePositive(radiusBox.getValue(), 128);
        scanned.clear();
        selected.clear();
        ModNetworking.CHANNEL.sendToServer(new ScanRequestPacket(centerChunkX, centerChunkZ, radius));
    }

    private void sendCancelScan() {
        ModNetworking.CHANNEL.sendToServer(new CancelScanPacket());
    }

    @Override
    public void onClose() {
        sendCancelScan();
        super.onClose();
    }

    private void sendDelete() {
        List<ChunkPos> chunks = new ArrayList<>(selected.size());
        for (long key : selected) {
            chunks.add(new ChunkPos((int) key, (int) (key >> 32)));
        }
        ModNetworking.CHANNEL.sendToServer(new DeleteChunksPacket(chunks));
    }

    private int parsePositive(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int chunkToScreenX(int chunkX) {
        return (int) Math.floor(gridCenterX() + (chunkX - centerChunkX - panChunkX) * cellSize);
    }

    private int chunkToScreenY(int chunkZ) {
        return (int) Math.floor(gridCenterY() + (chunkZ - centerChunkZ - panChunkZ) * cellSize);
    }

    private int screenToChunkX(double screenX) {
        return (int) Math.floor(screenToWorldChunkX(screenX));
    }

    private int screenToChunkZ(double screenY) {
        return (int) Math.floor(screenToWorldChunkZ(screenY));
    }

    private double screenToWorldChunkX(double screenX) {
        return (screenX - gridCenterX()) / cellSize + centerChunkX + panChunkX;
    }

    private double screenToWorldChunkZ(double screenY) {
        return (screenY - gridCenterY()) / cellSize + centerChunkZ + panChunkZ;
    }

    private double gridCenterX() {
        return SIDEBAR_WIDTH + (width - SIDEBAR_WIDTH) / 2.0D;
    }

    private double gridCenterY() {
        return height / 2.0D;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

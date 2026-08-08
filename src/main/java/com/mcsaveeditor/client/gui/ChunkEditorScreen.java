package com.mcsaveeditor.client.gui;

import com.mcsaveeditor.client.EditorPrefs;
import com.mcsaveeditor.promo.PromoInfo;
import com.mcsaveeditor.network.ModNetworking;
import com.mcsaveeditor.network.packet.CancelScanPacket;
import com.mcsaveeditor.network.packet.ChunkTimeEntry;
import com.mcsaveeditor.network.packet.ChunkOperation;
import com.mcsaveeditor.network.packet.DeleteChunksPacket;
import com.mcsaveeditor.network.packet.ScanRequestPacket;
import com.mcsaveeditor.network.packet.ScanSpeedPacket;
import com.mcsaveeditor.server.ProtectionCategories;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkEditorScreen extends Screen {
    private static final int SIDEBAR_WIDTH = 224;
    private static final int MAX_CELL = 48;
    private static final int MAX_SCANNED_ENTRIES = 200_000;
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
    private EditBox minutesBox;
    private EditBox seedBox;
    private Checkbox radiusCheckbox;
    private Checkbox minutesCheckbox;
    private Checkbox randomSeedCheckbox;
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
    private int biomeIndex;
    private Button biomeButton;
    private boolean fusionExpanded;
    private Button fusionButton;
    private final List<Checkbox> fusionChecks = new ArrayList<>();

    /** Biome options for terrain regeneration: id, display name. */
    private static final String[][] BIOMES = {
            {"", "原版（世界随机）"},
            {"minecraft:plains", "平原"},
            {"minecraft:desert", "沙漠"},
            {"minecraft:forest", "森林"},
            {"minecraft:taiga", "针叶林"},
            {"minecraft:swamp", "沼泽"},
            {"minecraft:snowy_plains", "雪原"},
            {"minecraft:savanna", "草原"},
            {"minecraft:ocean", "海洋"},
            {"minecraft:mushroom_fields", "蘑菇岛"},
            {"minecraft:badlands", "恶地"},
            {"minecraft:jungle", "丛林"},
            {"minecraft:cherry_grove", "樱花林"}
    };

    public ChunkEditorScreen(int centerChunkX, int centerChunkZ) {
        super(Component.literal("MC 存档区块编辑器"));
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
    }

    @Override
    protected void init() {
        int x = 12;
        int y = 42;

        // ── Restore remembered parameters ───────────────────────────────
        radiusCheckbox = new Checkbox(x, y, 92, 20, Component.literal("限制范围"), EditorPrefs.limitRadius());
        addRenderableWidget(radiusCheckbox);

        radiusBox = new EditBox(font, x + 100, y, 88, 20, Component.literal("区块半径"));
        radiusBox.setValue(String.valueOf(EditorPrefs.radius()));
        addRenderableWidget(radiusBox);

        minutesCheckbox = new Checkbox(x, y + 26, 92, 20, Component.literal("限制时间"), EditorPrefs.limitInhabitedTime());
        addRenderableWidget(minutesCheckbox);

        minutesBox = new EditBox(font, x + 100, y + 26, 88, 20, Component.literal("最长停留分钟"));
        minutesBox.setValue(String.valueOf(EditorPrefs.maxMinutes()));
        addRenderableWidget(minutesBox);

        addRenderableWidget(Button.builder(Component.literal("扫描区块"), button -> sendScan())
                .bounds(x, y + 56, 92, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("取消扫描"), button -> sendCancelScan())
                .bounds(x + 100, y + 56, 88, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("清除选择"), button -> selected.clear())
                .bounds(x, y + 84, 92, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("重建地形（保留建筑）"),
                        button -> sendOperation(ChunkOperation.REGENERATE))
                .bounds(x + 100, y + 84, 88, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("升级区块数据"),
                        button -> sendOperation(ChunkOperation.UPDATE))
                .bounds(x, y + 112, 92, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("彻底删除").withStyle(ChatFormatting.RED),
                        button -> sendOperation(ChunkOperation.DELETE))
                .bounds(x + 100, y + 112, 88, 20)
                .build());
        randomSeedCheckbox = new Checkbox(x, y + 140, 92, 20, Component.literal("随机种子"), EditorPrefs.randomSeed());
        addRenderableWidget(randomSeedCheckbox);
        seedBox = new EditBox(font, x + 100, y + 140, 88, 20, Component.literal("目标种子"));
        seedBox.setValue(String.valueOf(EditorPrefs.targetSeed()));
        addRenderableWidget(seedBox);
        addRenderableWidget(new ScanSpeedSlider(x, y + 168, 188, 20, EditorPrefs.scanSpeed()));

        // ── Biome selector for terrain regeneration ─────────────────────
        biomeIndex = 0;
        String rememberedBiome = EditorPrefs.biome();
        for (int i = 0; i < BIOMES.length; i++) {
            if (BIOMES[i][0].equals(rememberedBiome)) {
                biomeIndex = i;
                break;
            }
        }
        biomeButton = Button.builder(Component.literal(biomeLabel()), button -> {
            biomeIndex = (biomeIndex + 1) % BIOMES.length;
            button.setMessage(Component.literal(biomeLabel()));
        }).bounds(x, y + 196, 188, 20).build();
        addRenderableWidget(biomeButton);
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .bounds(x, y + 224, 188, 20)
                .build());

        // ── 融合策略：可折叠保护分类面板 ───────────────────────────────
        fusionChecks.clear();
        String savedCategories = EditorPrefs.protectedCategories();
        Set<String> remembered = new HashSet<>();
        if (savedCategories.isEmpty()) {
            remembered.addAll(ProtectionCategories.defaultEnabledIds());
        } else {
            for (String id : savedCategories.split(",")) {
                if (!id.isBlank()) {
                    remembered.add(id.trim());
                }
            }
        }
        int fusionY = 380;
        fusionButton = Button.builder(Component.literal(fusionLabel()), button -> {
            fusionExpanded = !fusionExpanded;
            button.setMessage(Component.literal(fusionLabel()));
            applyFusionVisibility();
        }).bounds(x, fusionY, 188, 20).build();
        addRenderableWidget(fusionButton);
        for (int i = 0; i < ProtectionCategories.CATEGORIES.size(); i++) {
            ProtectionCategories.Category category = ProtectionCategories.CATEGORIES.get(i);
            int column = i % 2;
            int row = i / 2;
            Checkbox box = new Checkbox(x + column * 98, fusionY + 28 + row * 20, 92, 18,
                    Component.literal(category.label()), remembered.contains(category.id()));
            fusionChecks.add(box);
            addRenderableWidget(box);
        }
        applyFusionVisibility();

        // ── Restore map view (zoom / pan) ───────────────────────────────
        cellSize = Math.max(minCellSize(), Math.min(MAX_CELL, EditorPrefs.cellSize()));
        panChunkX = EditorPrefs.panX();
        panChunkZ = EditorPrefs.panZ();
    }

    private String biomeLabel() {
        return "群系：" + BIOMES[biomeIndex][1];
    }

    private String fusionLabel() {
        return "融合策略（" + (fusionExpanded ? "收起" : "展开") + "）";
    }

    private void applyFusionVisibility() {
        for (Checkbox box : fusionChecks) {
            box.visible = fusionExpanded;
            box.active = fusionExpanded;
        }
    }

    private static final class ScanSpeedSlider extends AbstractSliderButton {
        private static final int MIN_SPEED = 1;
        private static final int MAX_SPEED = 256;
        private int lastSent = -1;

        private ScanSpeedSlider(int x, int y, int width, int height, int initialSpeed) {
            super(x, y, width, height, Component.empty(),
                    (double) (clampSpeed(initialSpeed) - MIN_SPEED) / (MAX_SPEED - MIN_SPEED));
        }

        private static int clampSpeed(int value) {
            return Math.max(MIN_SPEED, Math.min(MAX_SPEED, value));
        }

        private int speed() {
            return MIN_SPEED + (int) Math.round(this.value * (MAX_SPEED - MIN_SPEED));
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("扫描速度：" + speed() + " 区块/刻"));
        }

        @Override
        protected void applyValue() {
            int current = speed();
            if (current != lastSent) {
                lastSent = current;
                EditorPrefs.setScanSpeed(current);
                ModNetworking.CHANNEL.sendToServer(new ScanSpeedPacket(current));
            }
        }
    }

    public void acceptScanResult(List<ChunkTimeEntry> entries) {
        boolean warned = false;
        for (ChunkTimeEntry entry : entries) {
            if (scanned.size() >= MAX_SCANNED_ENTRIES) {
                if (!warned) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("MC 存档区块编辑器：扫描结果过多，已停止继续接收（上限 "
                                    + MAX_SCANNED_ENTRIES + "）。请缩小扫描范围。"), false);
                    warned = true;
                }
                return;
            }
            long key = ChunkPos.asLong(entry.chunkX(), entry.chunkZ());
            scanned.put(key, entry);
        }
    }

    public void acceptProcessedChunks(ChunkOperation operation, List<ChunkPos> chunks) {
        for (ChunkPos pos : chunks) {
            long key = pos.toLong();
            if (operation == ChunkOperation.DELETE) {
                scanned.remove(key);
            }
            selected.remove(key);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        renderMap(graphics, mouseX, mouseY);
        renderSidebar(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderPromoButton(graphics, mouseX, mouseY);
    }

    private static final int PROMO_BTN_X_OFFSET = 128;
    private static final int PROMO_BTN_WIDTH = 120;
    private static final int PROMO_BTN_HEIGHT = 20;

    private void renderPromoButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int bx = width - PROMO_BTN_X_OFFSET;
        int by = height - PROMO_BTN_HEIGHT - 4;
        boolean hovered = mouseX >= bx && mouseX < bx + PROMO_BTN_WIDTH
                && mouseY >= by && mouseY < by + PROMO_BTN_HEIGHT;
        int bg = hovered ? 0xFF5A5A5A : 0xFF3A3A3A;
        graphics.fill(bx, by, bx + PROMO_BTN_WIDTH, by + PROMO_BTN_HEIGHT, bg);
        graphics.fill(bx, by, bx + PROMO_BTN_WIDTH, by + 1, 0xFF888888);
        String text = "加入 AmityMC";
        int tw = font.width(text);
        graphics.drawString(font, text, bx + (PROMO_BTN_WIDTH - tw) / 2, by + 6, 0xFFFFD700, true);
    }

    private void renderSidebar(GuiGraphics graphics) {
        graphics.fill(0, 0, SIDEBAR_WIDTH, height, 0xE81B1D22);
        graphics.fill(SIDEBAR_WIDTH - 1, 0, SIDEBAR_WIDTH, height, 0xFF4A4D55);
        graphics.drawString(font, "MC 存档区块编辑器", 12, 14, 0xFFFFFFFF);
        graphics.drawString(font, "筛选条件（勾选后生效）", 12, 28, 0xFFBFC5D2);
        graphics.drawString(font, "已选择：" + selected.size() + "  扫描：" + scanned.size(), 12, 310, 0xFFFFFFFF);
        graphics.drawString(font, "黄色：玩家附近受保护区块", 12, 324, 0xFFF2C230);
        graphics.drawString(font, "中心：" + centerChunkX + ", " + centerChunkZ
                + "  缩放：" + Math.round(cellSize), 12, 338, 0xFFBFC5D2);
        long viewBlocks = Math.round((width - SIDEBAR_WIDTH) / cellSize * 16.0D);
        graphics.drawString(font, "视野约 " + viewBlocks + " 格", 12, 352, 0xFFBFC5D2);
    }

    private void joinAmity() {
        Minecraft mc = Minecraft.getInstance();
        ServerAddress address = ServerAddress.parseString(PromoInfo.SERVER_IP);
        ServerData data = new ServerData(PromoInfo.SERVER_NAME, PromoInfo.SERVER_IP, false);
        ConnectScreen.startConnecting(mc.screen, mc, address, data, false);
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

        int bx = width - PROMO_BTN_X_OFFSET;
        int by = height - PROMO_BTN_HEIGHT - 4;
        if (button == 0 && mouseX >= bx && mouseX < bx + PROMO_BTN_WIDTH
                && mouseY >= by && mouseY < by + PROMO_BTN_HEIGHT) {
            joinAmity();
            return true;
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
        cellSize = Math.max(minCellSize(), Math.min(MAX_CELL, cellSize + delta * 2.0D));
        double afterX = screenToWorldChunkX(mouseX);
        double afterZ = screenToWorldChunkZ(mouseY);
        panChunkX += beforeX - afterX;
        panChunkZ += beforeZ - afterZ;
        return true;
    }

    /** Smallest zoom-out: the viewport spans ~10,000 blocks (625 chunks) across. */
    private int minCellSize() {
        int mapWidthPx = Math.max(1, width - SIDEBAR_WIDTH);
        return Math.max(1, (int) Math.floor(mapWidthPx / 625.0D));
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
                if (entry == null || entry.protectedChunk()) {
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
        int minutes = parseNonNegative(minutesBox.getValue(), 5);
        scanned.clear();
        selected.clear();
        rememberParameters(radius, minutes);
        ModNetworking.CHANNEL.sendToServer(new ScanRequestPacket(centerChunkX, centerChunkZ,
                radiusCheckbox.selected(), radius, minutesCheckbox.selected(), minutes));
    }

    private void sendCancelScan() {
        ModNetworking.CHANNEL.sendToServer(new CancelScanPacket());
    }

    @Override
    public void onClose() {
        sendCancelScan();
        rememberParameters(parsePositive(radiusBox.getValue(), 128), parseNonNegative(minutesBox.getValue(), 5));
        super.onClose();
    }

    /** Persists every editable parameter so it is restored next time. */
    private void rememberParameters(int radius, int minutes) {
        EditorPrefs.setLimitRadius(radiusCheckbox.selected());
        EditorPrefs.setRadius(radius);
        EditorPrefs.setLimitInhabitedTime(minutesCheckbox.selected());
        EditorPrefs.setMaxMinutes(minutes);
        EditorPrefs.setRandomSeed(randomSeedCheckbox.selected());
        try {
            EditorPrefs.setTargetSeed(Long.parseLong(seedBox.getValue().trim()));
        } catch (NumberFormatException ignored) {
        }
        EditorPrefs.setBiome(biomeButton != null ? BIOMES[biomeIndex][0] : "");
        EditorPrefs.setProtectedCategories(String.join(",", fusionCategories()));
        EditorPrefs.setCellSize(cellSize);
        EditorPrefs.setPanX(panChunkX);
        EditorPrefs.setPanZ(panChunkZ);
        EditorPrefs.save();
    }

    private List<String> fusionCategories() {
        List<String> enabled = new ArrayList<>();
        for (int i = 0; i < Math.min(fusionChecks.size(), ProtectionCategories.CATEGORIES.size()); i++) {
            if (fusionChecks.get(i).selected()) {
                enabled.add(ProtectionCategories.CATEGORIES.get(i).id());
            }
        }
        return enabled;
    }

    private void sendOperation(ChunkOperation operation) {
        if (selected.isEmpty()) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("MC 存档区块编辑器：请先选择区块。"), false);
            return;
        }
        if (selected.size() > DeleteChunksPacket.MAX_DELETE_COUNT) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("MC 存档区块编辑器：选择过多（" + selected.size() + "），单次最多 "
                            + DeleteChunksPacket.MAX_DELETE_COUNT + " 个，请分批处理。"), false);
            return;
        }
        List<ChunkPos> chunks = new ArrayList<>(selected.size());
        for (long key : selected) {
            chunks.add(new ChunkPos((int) key, (int) (key >> 32)));
        }
        long seed = 0L;
        if (operation == ChunkOperation.REGENERATE && !randomSeedCheckbox.selected()) {
            try {
                seed = Long.parseLong(seedBox.getValue().trim());
            } catch (NumberFormatException exception) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("MC 存档区块编辑器：目标种子格式不正确，请输入整数。"), false);
                return;
            }
        }
        boolean randomSeed = operation == ChunkOperation.REGENERATE && randomSeedCheckbox.selected();
        ModNetworking.CHANNEL.sendToServer(new DeleteChunksPacket(operation, chunks, randomSeed, seed,
                BIOMES[biomeIndex][0],
                operation == ChunkOperation.REGENERATE ? fusionCategories() : List.of()));
    }

    private int parsePositive(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int parseNonNegative(String value, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
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

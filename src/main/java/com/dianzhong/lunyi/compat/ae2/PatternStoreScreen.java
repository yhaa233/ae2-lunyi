package com.dianzhong.lunyi.compat.ae2;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 样板仓界面。
 * <p>
 * 画法照 AE2 的平板风格：浅灰面板 + 略深的凹槽（槽位左上一条亮边、右下一条暗边），
 * 而不是原版那种凸起按钮式的槽。
 */
public class PatternStoreScreen extends AbstractContainerScreen<PatternStoreMenu> {
    private static final int WIDTH = 9 * 18 + 16;
    private static final int HEIGHT = 18 + PatternStoreMenu.ROWS * 18 + 20 + 4 * 18 + 30;

    // AE2 平板配色的近似值
    private static final int PANEL = 0xFFC2C2CE;
    private static final int PANEL_EDGE_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_EDGE_DARK = 0xFF8A8A9A;
    private static final int SLOT = 0xFFADADC0;
    private static final int SLOT_EDGE_LIGHT = 0xFFD8D8E4;
    private static final int SLOT_EDGE_DARK = 0xFF8E8EA0;
    private static final int LABEL = 0xFF404040;

    public PatternStoreScreen(PatternStoreMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelY = 18 + PatternStoreMenu.ROWS * 18 + 8;
    }

    @Override
    protected void init() {
        super.init();
        int y = this.topPos + this.imageHeight - 24;
        // 回到合成终端
        this.addRenderableWidget(Button.builder(Component.literal("终端"), b ->
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.dianzhong.lunyi.network.OpenTerminalPayload()))
                .bounds(this.leftPos + this.imageWidth - 54, this.topPos + 4, 46, 14).build());

        this.addRenderableWidget(Button.builder(Component.literal("<"), b ->
                        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0))
                .bounds(this.leftPos + 8, y, 24, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b ->
                        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 1))
                .bounds(this.leftPos + 36, y, 24, 18).build());

        // 从背包导入全部样板（避开右键被其它模组拦截）
        this.addRenderableWidget(Button.builder(Component.literal("导入"), b ->
                        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 2))
                .bounds(this.leftPos + 70, this.topPos + 4, 42, 14).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = this.leftPos;
        int y = this.topPos;
        graphics.drawString(this.font, Component.literal("第 " + (this.menu.getPage() + 1) + " 页 · 共 "
                + this.menu.getStoredCount() + " 个样板"), x + 68, y + this.imageHeight - 19, LABEL, false);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // 面板
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL);
        // 面板的亮/暗边
        graphics.fill(x, y, x + this.imageWidth, y + 1, PANEL_EDGE_LIGHT);
        graphics.fill(x, y, x + 1, y + this.imageHeight, PANEL_EDGE_LIGHT);
        graphics.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, PANEL_EDGE_DARK);
        graphics.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, PANEL_EDGE_DARK);

        // 所有槽位按 AE2 风格画（样板仓 + 玩家背包）
        for (Slot slot : this.menu.slots) {
            drawAe2Slot(graphics, x + slot.x - 1, y + slot.y - 1);
        }
    }

    /** 一个 18×18 的凹槽：左上亮边、右下暗边。 */
    private static void drawAe2Slot(GuiGraphics graphics, int x, int y) {
        int size = 18;
        graphics.fill(x, y, x + size, y + size, SLOT);
        graphics.fill(x, y, x + size, y + 1, SLOT_EDGE_LIGHT);
        graphics.fill(x, y, x + 1, y + size, SLOT_EDGE_LIGHT);
        graphics.fill(x + size - 1, y, x + size, y + size, SLOT_EDGE_DARK);
        graphics.fill(x, y + size - 1, x + size, y + size, SLOT_EDGE_DARK);
    }
}

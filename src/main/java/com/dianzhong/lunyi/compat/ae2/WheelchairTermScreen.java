package com.dianzhong.lunyi.compat.ae2;

import appeng.client.gui.me.items.CraftingTermScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;

import com.dianzhong.lunyi.entity.WheelchairEntity;
import com.dianzhong.lunyi.network.OpenPatternStorePayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 合成终端界面。
 * <p>
 * "样板仓"按钮走 AE2 的 {@code addToLeftToolbar}：
 * <ul>
 *   <li>必须在<b>构造函数</b>里加——AE2 的工具栏是持久列表，放 {@code init()} 会重复添加；</li>
 *   <li>控件必须是 <b>16×16</b>——AE2 按固定步长排版，{@code TabButton} 宽 28 会把左侧栏撑长。</li>
 * </ul>
 * 排版本身由 AE2 在 {@code init()} 里算，所以我们不指定坐标。
 */
public class WheelchairTermScreen extends CraftingTermScreen<WheelchairTermMenu> {
    private static final String STYLE = "/screens/terminals/wheelchair_terminal.json";

    public WheelchairTermScreen(WheelchairTermMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        this.addToLeftToolbar(new PatternStoreButton(button -> {
            var player = Minecraft.getInstance().player;
            if (player != null && player.getVehicle() instanceof WheelchairEntity wheelchair) {
                PacketDistributor.sendToServer(new OpenPatternStorePayload(wheelchair.getId()));
            }
        }));
    }

    /** 注册界面：用我们自己的样式表（它 include 了 AE2 的 terminal.json）。 */
    public static void register(RegisterMenuScreensEvent event) {
        MenuScreens.ScreenConstructor<WheelchairTermMenu, WheelchairTermScreen> factory =
                (menu, playerInventory, title) -> new WheelchairTermScreen(menu, playerInventory, title,
                        StyleManager.loadStyleDoc(STYLE));
        event.register(WheelchairTermMenu.TYPE, factory);
    }

    /** 16×16 的工具栏按钮，图标直接用 AE2 的编码样板物品。 */
    private static final class PatternStoreButton extends Button {
        private PatternStoreButton(OnPress onPress) {
            super(0, 0, 16, 16, Component.translatable("gui.ae2_lunyi.pattern_store"), onPress,
                    Button.DEFAULT_NARRATION);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int y = this.getY() + (this.isHovered() ? 1 : 0);
            graphics.renderItem(new net.minecraft.world.item.ItemStack(
                    appeng.core.definitions.AEItems.CRAFTING_PATTERN.get()), this.getX(), y);
        }
    }
}

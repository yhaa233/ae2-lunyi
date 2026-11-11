package com.dianzhong.lunyi.client;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.entity.WheelchairEntity;
import com.dianzhong.lunyi.network.DismountPayload;
import com.dianzhong.lunyi.network.OpenTerminalPayload;
import com.dianzhong.lunyi.registry.ModEntities;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端初始化：模型层、渲染器、按键注册，以及每 tick 检查脱离键。
 */
public final class ClientSetup {
    private ClientSetup() {}

    /** 模型层、渲染器、按键，都是 mod 总线事件。 */
    @EventBusSubscriber(modid = ae2lunyi.MODID, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
            event.registerLayerDefinition(WheelchairModel.LAYER_LOCATION, WheelchairModel::createBodyLayer);
        }

        @SubscribeEvent
        static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ModEntities.WHEELCHAIR.get(), WheelchairRenderer::new);
        }

        @SubscribeEvent
        static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(ModKeyMappings.DISMOUNT);
        }

        /** 注册轮椅自己的 AE2 终端界面（装了 AE2 才有意义）。 */
        @SubscribeEvent
        static void onRegisterMenuScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
            // 样板仓：普通容器界面
            event.register(com.dianzhong.lunyi.registry.ModMenus.PATTERN_STORE.get(),
                    com.dianzhong.lunyi.compat.ae2.PatternStoreScreen::new);

            if (!com.dianzhong.lunyi.compat.ae2.Ae2Compat.isAvailable()) {
                return;
            }
            try {
                com.dianzhong.lunyi.compat.ae2.WheelchairTermScreen.register(event);
            } catch (Throwable t) {
                ae2lunyi.LOGGER.error("Failed to register the wheelchair terminal screen", t);
            }
        }
    }

    /** 游戏总线事件：脱离键、E 键。 */
    @EventBusSubscriber(modid = ae2lunyi.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        private GameBus() {}

        @SubscribeEvent
        static void onClientTick(ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            while (ModKeyMappings.DISMOUNT.consumeClick()) {
                if (minecraft.player.getVehicle() instanceof WheelchairEntity) {
                    PacketDistributor.sendToServer(DismountPayload.INSTANCE);
                }
            }
        }

        /**
         * Alt + 右键轮椅：打开样板仓界面。
         */
        @SubscribeEvent
        static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
            if (event.isAttack()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.screen != null) {
                return;
            }
            long window = minecraft.getWindow().getWindow();
            boolean alt = net.minecraft.client.gui.screens.Screen.hasAltDown()
                    || org.lwjgl.glfw.GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                    || org.lwjgl.glfw.GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
            if (!alt) {
                return;
            }
            if (minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit
                    && hit.getEntity() instanceof WheelchairEntity wheelchair) {
                PacketDistributor.sendToServer(
                        new com.dianzhong.lunyi.network.OpenPatternStorePayload(wheelchair.getId()));
                event.setCanceled(true);
            }
        }

        /**
         * 骑在轮椅上按 E：直接打开 AE2 终端。
         * <p>
         * 不能在 {@link ClientTickEvent} 里用 {@code consumeClick()} —— 那时
         * {@code Minecraft#handleKeybinds()} 早就把这次点击消耗掉了（背包都已经弹出来）。
         * 所以在这一层拦：按键按下、人在轮椅上、按的是"打开背包"那个键，就发包并取消事件，
         * 这样背包不会跟着弹出来。用 keyInventory 比较而不是写死 E，玩家改键也能用。
         */
        @SubscribeEvent
        static void onKeyInput(InputEvent.Key event) {
            if (event.getAction() != GLFW.GLFW_PRESS) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.screen != null) {
                return;
            }
            if (!(minecraft.player.getVehicle() instanceof WheelchairEntity)) {
                return;
            }
            if (minecraft.options.keyInventory.matches(event.getKey(), event.getScanCode())) {
                // InputEvent.Key 在 1.21 里不能取消，所以背包可能先弹一下；
                // 但服务端立刻回一个打开终端的包，客户端会把屏幕换成终端，观感上就是闪一下。
                PacketDistributor.sendToServer(OpenTerminalPayload.INSTANCE);
            }
        }
    }

    /** Alt + 右键轮椅合成核心：把背包里的样板全部上传（Alt 只能在客户端读到）。 */
    @SubscribeEvent
    static void onRightClickCore(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (!net.minecraft.client.gui.screens.Screen.hasAltDown()) {
            return;
        }
        var state = event.getLevel().getBlockState(event.getPos());
        if (!(state.getBlock() instanceof com.dianzhong.lunyi.block.WheelchairCpuBlock)) {
            return;
        }
        PacketDistributor.sendToServer(
                new com.dianzhong.lunyi.network.UploadPatternsPayload(event.getPos()));
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
    }
}

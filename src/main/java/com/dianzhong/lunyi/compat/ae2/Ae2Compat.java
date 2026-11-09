package com.dianzhong.lunyi.compat.ae2;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.entity.WheelchairEntity;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

/**
 * AE2 兼容层的入口。这个类里<b>没有任何 AE2 类型</b>，
 * 所以即使玩家没装 AE2，加载它也不会炸；只有在确认 AE2 存在时才会去碰
 * {@link Ae2Terminal}（那个类才有 AE2 类型）。
 * <p>
 * 外面还包了一层 {@code catch (Throwable)}：JVM 校验/解析 {@link Ae2Terminal} 是懒的，
 * 但万一在某个环境下提前触发了链接，也只会静默降级而不是崩掉整个游戏。
 */
public final class Ae2Compat {
    public static final String AE2_MODID = "ae2";

    private Ae2Compat() {}

    public static boolean isAvailable() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(AE2_MODID);
    }

    /** 这个物品是不是量子缠绕奇点（用来绑定网络）。 */
    public static boolean isEntangledSingularity(ItemStack stack) {
        if (!isAvailable() || stack.isEmpty()) {
            return false;
        }
        try {
            return Ae2Terminal.isEntangledSingularity(stack);
        } catch (Throwable t) {
            logOnce("check singularity", t);
            return false;
        }
    }

    /** 读奇点里存的网络频率，0 表示无效。 */
    public static long getSingularityFrequency(ItemStack stack) {
        if (!isAvailable() || stack.isEmpty()) {
            return 0L;
        }
        try {
            return Ae2Terminal.getFrequency(stack);
        } catch (Throwable t) {
            logOnce("read singularity frequency", t);
            return 0L;
        }
    }

    /** 是否是合成样板（而非处理样板）。 */
    public static boolean isCraftingPattern(ItemStack stack) {
        if (!isAvailable() || stack.isEmpty()) {
            return false;
        }
        try {
            return stack.is(appeng.core.definitions.AEItems.CRAFTING_PATTERN.get());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isEncodedPattern(ItemStack stack) {
        if (!isAvailable() || stack.isEmpty()) {
            return false;
        }
        try {
            return appeng.api.crafting.PatternDetailsHelper.isEncodedPattern(stack);
        } catch (Throwable t) {
            logOnce("check encoded pattern", t);
            return false;
        }
    }

    /** 打开轮椅的合成终端。 */
    public static boolean tryOpenTerminal(Player player, WheelchairEntity wheelchair) {
        if (!isAvailable()) {
            return false;
        }
        try {
            return Ae2Terminal.open(player, wheelchair);
        } catch (Throwable t) {
            logOnce("open terminal", t);
            return false;
        }
    }

    /**
     * 轮椅的样板仓变化后，通知附近的"轮椅合成核心"重新扫描。
     * 不调这个，AE2 的合成服务会一直用旧缓存，表现为"只改轮椅上的样板不生效"。
     */
    public static void refreshNearbyCores(net.minecraft.world.entity.Entity entity) {
        if (!isAvailable()) {
            return;
        }
        try {
            Ae2Terminal.refreshNearbyCores(entity);
        } catch (Throwable t) {
            logOnce("refresh nearby cores", t);
        }
    }
    /** 每 tick 维护轮椅自己的网格节点（服务端）。 */
    public static void tickGridNode(WheelchairEntity wheelchair) {
        if (!isAvailable()) {
            return;
        }
        try {
            Ae2Terminal.tickGridNode(wheelchair);
        } catch (Throwable t) {
            logOnce("tick grid node", t);
        }
    }

    /** 实体移除时销毁网格节点，别在网络里留幽灵。 */
    public static void removeGridNode(WheelchairEntity wheelchair) {
        if (!isAvailable()) {
            return;
        }
        try {
            Ae2Terminal.removeGridNode(wheelchair);
        } catch (Throwable t) {
            logOnce("remove grid node", t);
        }
    }
    /** 注册菜单类型，必须在 mod 构造阶段调用（要赶在注册事件之前）。 */
    public static void registerMenuType(IEventBus modEventBus) {
        if (!isAvailable()) {
            return;
        }
        try {
            Ae2Terminal.registerMenuType(modEventBus);
        } catch (Throwable t) {
            logOnce("register menu type", t);
        }
    }

    /** 注册菜单定位器，必须在 mod 初始化时调用一次（两端都要）。 */
    public static void registerLocators() {
        if (!isAvailable()) {
            return;
        }
        try {
            Ae2Terminal.registerLocator();
        } catch (Throwable t) {
            logOnce("register menu locator", t);
        }
    }

    private static boolean logged;

    private static void logOnce(String action, Throwable t) {
        if (!logged) {
            logged = true;
            ae2lunyi.LOGGER.error("AE2 integration failed to {}", action, t);
        }
    }
}

package com.dianzhong.lunyi.compat;

import com.dianzhong.lunyi.ae2lunyi;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * 对 creat-id（配方探测器）的可选依赖。
 * <p>
 * 装了它就直接查询它启动时建立的配方索引，判断"某个物品/流体/化学品是否有真实配方产出"，
 * 比我们自己反射扫描更快也更准（尤其是 Mekanism 的化学品与闪电科技的机器配方）。
 * 没装时全部返回"未知"，调用方退回原有逻辑。
 */
public final class CreatIdCompat {
    private static boolean checked;
    private static boolean available;
    private static Method producesMethod;
    private static Method producersMethod;
    private static Method isReadyMethod;
    private static Method inputsMethod;
    private static Method slotsMethod;
    private static Method outputsMethod;
    private static Method outputAmountsMethod;

    private CreatIdCompat() {
    }

    private static void init() {
        if (checked) {
            return;
        }
        checked = true;
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("creat_id")) {
                return;
            }
            Class<?> index = Class.forName("com.dianzhong.creatid.RecipeIndex");
            isReadyMethod = index.getMethod("isReady");
            producesMethod = index.getMethod("produces", String.class);
            producersMethod = index.getMethod("producersOf", String.class);
            inputsMethod = index.getMethod("inputsOf", String.class);
            slotsMethod = index.getMethod("slotsOf", String.class);
            outputsMethod = index.getMethod("outputsOf", String.class);
            outputAmountsMethod = index.getMethod("outputAmountsOf", String.class);
            available = true;
            ae2lunyi.LOGGER.info("creat-id detected: recipe index will be used for pattern validation");
        } catch (Throwable t) {
            ae2lunyi.LOGGER.warn("creat-id present but unusable, falling back", t);
        }
    }

    /** 索引是否可用且已经建立完成。 */
    public static boolean usable() {
        init();
        if (!available) {
            return false;
        }
        try {
            Object ready = isReadyMethod.invoke(null);
            return ready instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Output entries WITH counts; empty when unavailable. */
    @SuppressWarnings("unchecked")
    public static java.util.List<String> outputAmountsOf(String recipeId) {
        if (!usable() || outputAmountsMethod == null) {
            return Collections.emptyList();
        }
        try {
            Object result = outputAmountsMethod.invoke(null, recipeId);
            return result instanceof java.util.List<?> list
                    ? (java.util.List<String>) list
                    : Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
    /** Output ids of one recipe; empty when unavailable. */
    @SuppressWarnings("unchecked")
    public static java.util.List<String> outputsOf(String recipeId) {
        if (!usable() || outputsMethod == null) {
            return Collections.emptyList();
        }
        try {
            Object result = outputsMethod.invoke(null, recipeId);
            return result instanceof java.util.List<?> list
                    ? (java.util.List<String>) list
                    : Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
    /** 某条配方的输入槽；索引不可用或没有该配方时返回空表。 */
    @SuppressWarnings("unchecked")
    public static java.util.List<String> slotsOf(String recipeId) {
        if (!usable() || slotsMethod == null) {
            return Collections.emptyList();
        }
        try {
            Object result = slotsMethod.invoke(null, recipeId);
            return result instanceof java.util.List<?> list
                    ? (java.util.List<String>) list
                    : Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
    /** 某条配方在索引里声明的输入 id；索引不可用时返回空表。 */
    @SuppressWarnings("unchecked")
    public static java.util.List<String> inputsOf(String recipeId) {
        if (!usable() || inputsMethod == null) {
            return Collections.emptyList();
        }
        try {
            Object result = inputsMethod.invoke(null, recipeId);
            return result instanceof java.util.List<?> list
                    ? (java.util.List<String>) list
                    : Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** null 表示无法判断（索引不可用），true/false 表示有/没有真实配方。 */
    public static Boolean produces(String id) {
        if (!usable()) {
            return null;
        }
        try {
            Object result = producesMethod.invoke(null, id);
            return result instanceof Boolean b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> producersOf(String id) {
        if (!usable()) {
            return Collections.emptyList();
        }
        try {
            Object result = producersMethod.invoke(null, id);
            return result instanceof List<?> list ? (List<String>) list : Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
}

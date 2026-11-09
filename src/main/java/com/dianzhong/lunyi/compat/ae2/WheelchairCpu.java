package com.dianzhong.lunyi.compat.ae2;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.block.WheelchairCpuBlockEntity;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轮椅合成核心的 CPU 门面：出现在 AE2 的 CPU 列表里，容量/并行显示成无穷大。
 * <p>
 * 真正的执行在 {@link #perform(IGrid, GenericStack)}：按轮椅/核心/网络里的样板递归展开，
 * 从网络扣基础材料，再把产物写回网络（写入时绕开 AE2 的 AutoCrafting 存储，见 {@link #insertIntoNetwork}）。
 */
public final class WheelchairCpu implements ICraftingCPU {
    public static final WheelchairCpu INSTANCE = new WheelchairCpu();

    /** 列表诊断日志只打一次。 */
    public static boolean LIST_LOGGED;

    /** 递归展开的最大深度。 */
    private static final int MAX_DEPTH = 16;

    private WheelchairCpu() {}

    @Override
    public boolean isBusy() {
        return false;
    }

    @Nullable
    @Override
    public CraftingJobStatus getJobStatus() {
        return null;
    }

    @Override
    public void cancelJob() {
        // 没有排队任务
    }

    @Override
    public long getAvailableStorage() {
        return Long.MAX_VALUE;
    }

    @Override
    public int getCoProcessors() {
        return Integer.MAX_VALUE;
    }

    @Nullable
    @Override
    public Component getName() {
        return Component.translatable("block.ae2_lunyi.wheelchair_cpu");
    }

    @Override
    public CpuSelectionMode getSelectionMode() {
        return CpuSelectionMode.ANY;
    }

    // ------------------------------------------------------------------
    // 合成
    // ------------------------------------------------------------------

    /**
     * 按网络里的样板把 {@code output} 一次性做出来。
     *
     * @return true 表示已经完成（产物已经交付）
     */
    public static boolean perform(IGrid grid, @Nullable GenericStack output) {
        if (output == null) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        IActionSource source = IActionSource.empty();
        var craftingService = grid.getCraftingService();

        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        int coreCount = 0;
        int patternCount = 0;
        WheelchairCpuBlockEntity firstCore = null;
        net.minecraft.world.level.Level firstLevel = null;

        java.util.List<ItemStack> allPatterns = new java.util.ArrayList<>();
        for (WheelchairCpuBlockEntity core : grid.getMachines(WheelchairCpuBlockEntity.class)) {
            coreCount++;
            if (firstCore == null) {
                firstCore = core;
                firstLevel = core.getLevel();
            }
            allPatterns.addAll(core.getPatterns());
        }
        for (com.dianzhong.lunyi.entity.WheelchairEntity wheelchair
                : com.dianzhong.lunyi.entity.WheelchairEntity.activeWheelchairs()) {
            if (wheelchair.level() == null) {
                continue;
            }
            allPatterns.addAll(wheelchair.getPatterns());
            if (firstLevel == null) {
                firstLevel = wheelchair.level();
            }
        }

        for (ItemStack stack : allPatterns) {
            if (stack.isEmpty() || firstLevel == null) {
                continue;
            }
            patternCount++;
            IPatternDetails details = PatternDetailsHelper.decodePattern(stack, firstLevel);
            if (details == null) {
                continue;
            }
            for (GenericStack out : details.getOutputs()) {
                byOutput.putIfAbsent(out.what(), details);
            }
        }

        ae2lunyi.LOGGER.info("Wheelchair CPU: cores={}, patterns={}, decoded={}, target={}",
                coreCount, patternCount, byOutput.size(), output.what());

        if (!byOutput.containsKey(output.what())) {
            IPatternDetails external = resolvePattern(grid, craftingService, output.what());
            if (external == null) {
                return false;
            }
            byOutput.put(output.what(), external);
        }

        IPatternDetails targetPattern = byOutput.get(output.what());
        long perCraft = 1;
        for (GenericStack out : targetPattern.getOutputs()) {
            if (out.what().equals(output.what())) {
                perCraft = Math.max(1, out.amount());
                break;
            }
        }
        long crafts = Math.max(1, (output.amount() + perCraft - 1) / perCraft);

        Map<AEKey, Long> baseNeeds = new HashMap<>();
        if (!expand(grid, firstLevel, output.what(), crafts, byOutput, craftingService, storage, source,
                baseNeeds, new HashSet<>(), 0)) {
            ae2lunyi.LOGGER.info("Wheelchair CPU: expand failed (cycle or too deep) for {}", output.what());
            return false;
        }

        for (Map.Entry<AEKey, Long> need : baseNeeds.entrySet()) {
            long have = storage.extract(need.getKey(), need.getValue(), Actionable.SIMULATE, source);
            if (have < need.getValue()) {
                ae2lunyi.LOGGER.info("Wheelchair CPU: not enough {} (need {}, have {})",
                        need.getKey(), need.getValue(), have);
                return false;
            }
        }
        for (Map.Entry<AEKey, Long> need : baseNeeds.entrySet()) {
            storage.extract(need.getKey(), need.getValue(), Actionable.MODULATE, source);
        }

        // 诊断：写出展开后的基础材料清单，便于核对扣料是否正确
        ae2lunyi.LOGGER.info("Wheelchair CPU: base needs = {}", baseNeeds);

        for (GenericStack out : targetPattern.getOutputs()) {
            long want = out.amount() * crafts;
            ae2lunyi.LOGGER.info("Wheelchair CPU: delivering key={} amount={}", out.what(), want);
            long inserted = insertIntoNetwork(grid, storage, out.what(), want, source);
            if (inserted < want) {
                ae2lunyi.LOGGER.warn("Wheelchair CPU: could only insert {} of {}", inserted, want);
                return false;
            }
        }
        ae2lunyi.LOGGER.info("Wheelchair CPU crafted {} x{} ({} crafts) from {} base ingredients",
                output.what(), output.amount(), crafts, baseNeeds.size());
        return true;
    }

    /**
     * 把产物写进普通网络存储。
     * <p>
     * 直接 {@code storage.insert} 会被 AE2 的 {@code CraftingServiceStorage}（描述为 AutoCrafting）
     * 先吃掉，塞进 CPU 集群的内部缓冲 —— 能从存储视图里取到，但终端物品列表不显示。
     * 所以这里临时把合成服务里的 CPU 集合清空，让那次 insert 落不到任何 CPU 上，再恢复。
     */
    private static long insertIntoNetwork(IGrid grid, appeng.api.storage.MEStorage storage,
                                          AEKey what, long amount, IActionSource source) {
        Object savedSet = null;
        java.util.Set<Object> cpuSet = null;
        try {
            var service = grid.getCraftingService();
            var field = service.getClass().getDeclaredField("craftingCPUClusters");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var set = (java.util.Set<Object>) field.get(service);
            cpuSet = set;
            savedSet = List.copyOf(set);
            set.clear();
        } catch (Throwable t) {
            ae2lunyi.LOGGER.warn("Wheelchair CPU: cannot bypass AutoCrafting storage", t);
        }

        try {
            return storage.insert(what, amount, Actionable.MODULATE, source);
        } finally {
            if (cpuSet != null && savedSet != null) {
                @SuppressWarnings("unchecked")
                var saved = (List<Object>) savedSet;
                cpuSet.addAll(saved);
            }
        }
    }

    /**
     * 严格模式下校验样板：服务端配方表里必须存在产出同样物品的合成配方，
     * 用来挡掉"虚空请求"（样板是伪造的、实际没有配方）。
     */
    private static boolean isRealRecipe(net.minecraft.world.level.Level level, IPatternDetails details) {
        if (com.dianzhong.lunyi.Config.STRICT_PATTERN_VALIDATION.get() || level == null) {
            return true;
        }
        var outputs = details.getOutputs();
        if (outputs.isEmpty()) {
            return false;
        }
        AEKey wanted = outputs.get(0).what();

        // 样板声明的输入（把 tag/多选都按第一个可能项展开成物品列表）
        java.util.List<ItemStack> patternInputs = new java.util.ArrayList<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible.length == 0) {
                continue;
            }
            if (!(possible[0].what() instanceof appeng.api.stacks.AEItemKey itemKey)) {
                return false;
            }
            long amount = possible[0].amount() * input.getMultiplier();
            for (long i = 0; i < amount; i++) {
                patternInputs.add(itemKey.toStack(1));
            }
        }

        for (var holder : level.getRecipeManager().getAllRecipesFor(
                net.minecraft.world.item.crafting.RecipeType.CRAFTING)) {
            var recipe = holder.value();
            var result = recipe.getResultItem(level.registryAccess());
            var resultKey = appeng.api.stacks.AEItemKey.of(result);
            if (resultKey == null || !resultKey.equals(wanted)) {
                continue;
            }
            // 产物对上了，还要核对输入：配方的每个原料都必须能在样板输入里找到对应项
            var ingredients = recipe.getIngredients().stream().filter(i -> !i.isEmpty()).toList();
            if (ingredients.size() != patternInputs.size()) {
                continue;
            }
            java.util.List<ItemStack> pool = new java.util.ArrayList<>(patternInputs);
            boolean ok = true;
            for (var ingredient : ingredients) {
                int found = -1;
                for (int i = 0; i < pool.size(); i++) {
                    if (ingredient.test(pool.get(i))) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    ok = false;
                    break;
                }
                pool.remove(found);
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }
    /** 向网络要一个能产出该物品的样板（含外部样板供应器）。 */
    @Nullable
    private static IPatternDetails resolvePattern(IGrid grid,
                                                 appeng.api.networking.crafting.ICraftingService service,
                                                 AEKey what) {
        if (service != null) {
            var found = service.getCraftingFor(what);
            if (found != null && !found.isEmpty()) {
                return found.iterator().next();
            }
        }
        if (grid != null) {
            for (ICraftingProvider provider : grid.getMachines(ICraftingProvider.class)) {
                for (IPatternDetails details : provider.getAvailablePatterns()) {
                    for (GenericStack out : details.getOutputs()) {
                        if (out.what().equals(what)) {
                            return details;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 递归展开：网络里已有的直接当材料，缺的继续往下拆。 */
    private static boolean expand(IGrid grid, net.minecraft.world.level.Level level, AEKey what, long amount,
                                  Map<AEKey, IPatternDetails> byOutput,
                                  appeng.api.networking.crafting.ICraftingService craftingService,
                                  appeng.api.storage.MEStorage storage, IActionSource source,
                                  Map<AEKey, Long> baseNeeds, Set<AEKey> path, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        // 中间的产物：网络里已有就直接当材料用。
        // 但目标物品本身（depth == 0）必须按样板真正展开，否则会变成"扣掉再放回"。
        if (depth > 0 && storage != null && source != null
                && storage.extract(what, amount, Actionable.SIMULATE, source) >= amount) {
            baseNeeds.merge(what, amount, Long::sum);
            return true;
        }

        IPatternDetails pattern = byOutput.get(what);
        if (pattern == null) {
            pattern = resolvePattern(grid, craftingService, what);
            if (pattern != null) {
                byOutput.put(what, pattern);
            }
        }
        if (pattern == null) {
            baseNeeds.merge(what, amount, Long::sum);
            return true;
        }
        if (!isRealRecipe(level, pattern)) {
            ae2lunyi.LOGGER.info("Wheelchair CPU: pattern for {} has no matching recipe, refusing", what);
            return false;
        }
        if (!path.add(what)) {
            baseNeeds.merge(what, amount, Long::sum);
            return true;
        }
        try {
            for (IPatternDetails.IInput input : pattern.getInputs()) {
                GenericStack[] possible = input.getPossibleInputs();
                if (possible.length == 0) {
                    return false;
                }
                // 自引用样板（输入就是自己的产物）没法瞬时展开：扣一个再还一个，等于没做。
                // 交回 AE2 自己调度。
                if (possible[0].what().equals(what)) {
                    ae2lunyi.LOGGER.info("Wheelchair CPU: self-referential pattern for {}, handing back to AE2",
                            what);
                    return false;
                }
                long need = possible[0].amount() * input.getMultiplier() * amount;
                if (!expand(grid, level, possible[0].what(), need, byOutput, craftingService, storage, source,
                        baseNeeds, path, depth + 1)) {
                    return false;
                }
            }
        } finally {
            path.remove(what);
        }
        return true;
    }
}

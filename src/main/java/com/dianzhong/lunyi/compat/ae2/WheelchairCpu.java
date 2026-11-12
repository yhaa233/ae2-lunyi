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
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CPU facade for the wheelchair crafting core.
 * Pipeline: map primary outputs to patterns -> expand recursively (stock counts as material) ->
 * validate every pattern against real recipes -> consume base materials -> write outputs back.
 */
public final class WheelchairCpu implements ICraftingCPU {
    public static final WheelchairCpu INSTANCE = new WheelchairCpu();

    public static boolean LIST_LOGGED;

    /**
     * 这张样板是否在我们的样板仓里（轮椅合成核心或轮椅本体）。
     * <p>
     * 用于识别"来自其它 CPU（例如多方块假配方）的请求"：只有样板确实由我们保管时，
     * 才按玩家下单同样的流程校验并制作。
     */
    public static boolean hasStoredPattern(IGrid grid, @Nullable GenericStack output) {
        if (output == null) {
            return false;
        }
        Level level = null;
        List<ItemStack> stacks = new ArrayList<>();
        for (WheelchairCpuBlockEntity core : grid.getMachines(WheelchairCpuBlockEntity.class)) {
            if (level == null) {
                level = core.getLevel();
            }
            stacks.addAll(core.getPatterns());
        }
        for (com.dianzhong.lunyi.entity.WheelchairEntity wheelchair
                : com.dianzhong.lunyi.entity.WheelchairEntity.activeWheelchairs()) {
            if (wheelchair.level() == null) {
                continue;
            }
            if (level == null) {
                level = wheelchair.level();
            }
            stacks.addAll(wheelchair.getPatterns());
        }
        if (level == null) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
            if (details == null) {
                continue;
            }
            var outs = details.getOutputs();
            if (!outs.isEmpty() && outs.get(0).what().equals(output.what())) {
                return true;
            }
        }
        return false;
    }
    /**
     * 供其它 CPU 推送样板时使用的校验入口：
     * 通过返回 true，伪造/过期返回 false（此时我们什么都不产出，但不会取消整单）。
     */
    public static boolean validateForProvider(IGrid grid, IPatternDetails details) {
        if (details == null || grid == null) {
            return false;
        }
        Level level = null;
        for (WheelchairCpuBlockEntity core : grid.getMachines(WheelchairCpuBlockEntity.class)) {
            level = core.getLevel();
            break;
        }
        if (level == null) {
            for (com.dianzhong.lunyi.entity.WheelchairEntity wheelchair
                    : com.dianzhong.lunyi.entity.WheelchairEntity.activeWheelchairs()) {
                if (wheelchair.level() != null) {
                    level = wheelchair.level();
                    break;
                }
            }
        }
        if (level == null) {
            return false;
        }
        // 天枢这类 CPU 会批量推送同一个样板：5 秒内复用结果，避免每次都全量扫配方
        AEKey key = details.getDefinition();
        long now = currentTick();
        Long at = PROVIDER_CACHE_TICK.get(key);
        if (at != null && now - at < 100) {
            return Boolean.TRUE.equals(PROVIDER_CACHE_OK.get(key));
        }
        boolean ok = isRealRecipe(level, details) == Check.OK;
        PROVIDER_CACHE_TICK.put(key, now);
        PROVIDER_CACHE_OK.put(key, ok);
        return ok;
    }

    private static final Map<AEKey, Long> PROVIDER_CACHE_TICK = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<AEKey, Boolean> PROVIDER_CACHE_OK = new java.util.concurrent.ConcurrentHashMap<>();
    /** Result of validating one pattern. */
    public enum Check {
        /** Pattern matches a real recipe. */
        OK,
        /** The item has a real recipe, but this pattern no longer matches it (recipe changed). */
        STALE,
        /** No recipe anywhere produces this item: must be blocked. */
        FORGED,
        /** No recipe information available: let other CPUs handle it. */
        UNKNOWN
    }

    /** True when the current perform() found an outdated pattern and wants the player to re-encode it. */
    public static final ThreadLocal<Boolean> STALE = ThreadLocal.withInitial(() -> false);

    /** True when the current perform() could not verify a recipe and wants to hand over. */
    public static final ThreadLocal<Boolean> UNVERIFIED = ThreadLocal.withInitial(() -> false);

    /** True when the current perform() refused a pattern because it is not a real recipe. */
    public static final ThreadLocal<Boolean> REJECTED = ThreadLocal.withInitial(() -> false);

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
    // crafting
    // ------------------------------------------------------------------

    /** 其它 CPU 最近提交的任务最终产物 -> tick；用于区分"最终产物"和"中间材料"。 */
    private static final Map<AEKey, Long> JOB_FINALS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void rememberJobFinal(AEKey key) {
        if (key != null) {
            JOB_FINALS.put(key, currentTick());
        }
    }

    /** 这个产物是不是某个外部任务正在等的最终结果（而不是中间材料）。 */
    public static boolean isJobFinal(AEKey key) {
        Long at = key == null ? null : JOB_FINALS.get(key);
        if (at == null) {
            return false;
        }
        if (currentTick() - at > 1200) {
            JOB_FINALS.remove(key);
            return false;
        }
        return true;
    }
    /** 校验结果缓存：样板 -> (tick, 是否合法)。避免每次规划都重新扫配方。 */
    private static final Map<AEKey, Long> CACHE_TICK = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<AEKey, Boolean> CACHE_OK = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int CACHE_TICKS = 200;

    /**
     * 这张样板是否可以对外公开（可被其它 CPU / 终端规划）。
     * <p>
     * 校验不通过的样板直接不公开，这样伪造样板的请求根本不会生成，也就不会出现
     * "请求永远无法完成 -> 无限重试" 的卡死。
     */
    public static boolean isAdvertisable(net.minecraft.world.level.Level level, IPatternDetails details) {
        if (level == null || details == null) {
            return false;
        }
        AEKey key = details.getDefinition();
        long now = currentTick();
        if (key != null) {
            Long at = CACHE_TICK.get(key);
            if (at != null && now - at < CACHE_TICKS) {
                return Boolean.TRUE.equals(CACHE_OK.get(key));
            }
        }
        boolean ok;
        try {
            ok = isRealRecipe(level, details) == Check.OK;
        } catch (Throwable t) {
            ok = false;
        }
        if (key != null) {
            CACHE_TICK.put(key, now);
            CACHE_OK.put(key, ok);
        }
        return ok;
    }
    /** 同一物品在短时间内被反复请求的次数（用于打断"任务永远完不成 -> 无限重试"）。 */
    private static final Map<AEKey, long[]> REQUEST_BURST = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int BURST_WINDOW_TICKS = 100;
    private static final int BURST_LIMIT = 4;

    /** @return true 表示这次请求属于失控重试，应该放弃接管 */
    private static boolean isRunawayRequest(AEKey key) {
        long now = currentTick();
        long[] state = REQUEST_BURST.computeIfAbsent(key, k -> new long[]{now, 0L});
        if (now - state[0] > BURST_WINDOW_TICKS) {
            state[0] = now;
            state[1] = 0L;
        }
        state[1]++;
        if (state[1] > BURST_LIMIT) {
            return true;
        }
        return false;
    }
    /** 最近被拒绝的物品 -> 记录时的 tick；用于防止请求方反复重试把服务器拖死。 */
    private static final Map<AEKey, Long> RECENTLY_BLOCKED = new HashMap<>();

    private static final int BLOCK_MEMORY_TICKS = 40;

    private static long currentTick() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0L : server.getTickCount();
    }

    private static boolean recentlyBlocked(AEKey key) {
        Long at = RECENTLY_BLOCKED.get(key);
        if (at == null) {
            return false;
        }
        if (currentTick() - at > BLOCK_MEMORY_TICKS) {
            RECENTLY_BLOCKED.remove(key);
            return false;
        }
        return true;
    }

    private static void markBlocked(AEKey key) {
        if (key != null) {
            RECENTLY_BLOCKED.put(key, currentTick());
        }
    }
    public static boolean perform(IGrid grid, @Nullable GenericStack output) {
        if (output == null) {
            return false;
        }

        // 刚被拒绝过的物品：请求方往往会立刻重试，这里直接短路，避免服务器空转
        if (recentlyBlocked(output.what())) {
            REJECTED.set(true);
            return false;
        }
        if (isRunawayRequest(output.what())) {
            ae2lunyi.LOGGER.warn(
                    "Wheelchair CPU: {} is being requested in a tight loop, backing off", output.what());
            UNVERIFIED.set(true);
            return false;
        }
        REJECTED.set(false);
        UNVERIFIED.set(false);
        STALE.set(false);

        MEStorage storage = grid.getStorageService().getInventory();
        IActionSource source = IActionSource.empty();
        var craftingService = grid.getCraftingService();

        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        Level level = null;
        int coreCount = 0;
        int patternCount = 0;

        List<ItemStack> allPatterns = new ArrayList<>();
        for (WheelchairCpuBlockEntity core : grid.getMachines(WheelchairCpuBlockEntity.class)) {
            coreCount++;
            if (level == null) {
                level = core.getLevel();
            }
            allPatterns.addAll(core.getPatterns());
        }
        for (com.dianzhong.lunyi.entity.WheelchairEntity wheelchair
                : com.dianzhong.lunyi.entity.WheelchairEntity.activeWheelchairs()) {
            if (wheelchair.level() == null) {
                continue;
            }
            allPatterns.addAll(wheelchair.getPatterns());
            if (level == null) {
                level = wheelchair.level();
            }
        }

        for (ItemStack stack : allPatterns) {
            if (stack.isEmpty() || level == null) {
                continue;
            }
            patternCount++;
            IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
            if (details == null) {
                continue;
            }
            var outs = details.getOutputs();
            if (!outs.isEmpty()) {
                byOutput.putIfAbsent(outs.get(0).what(), details);
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
        if (!expand(grid, level, output.what(), crafts, byOutput, craftingService, storage, source,
                baseNeeds, new HashSet<>(), 0)) {
            ae2lunyi.LOGGER.info("Wheelchair CPU: expand failed for {}", output.what());
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

        long finalCrafts = crafts;
        Runnable writer = () -> {
            for (GenericStack out : targetPattern.getOutputs()) {
                long want = out.amount() * finalCrafts;
                long inserted = insertIntoNetwork(grid, storage, out.what(), want, source);
                if (inserted < want) {
                    ae2lunyi.LOGGER.warn("Wheelchair CPU: could only insert {} of {}", inserted, want);
                }
            }
        };
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(writer);
        } else {
            writer.run();
        }

        ae2lunyi.LOGGER.info("Wheelchair CPU crafted {} x{} ({} crafts) from {} base ingredients",
                output.what(), output.amount(), crafts, baseNeeds.size());
        return true;
    }

    private static long insertIntoNetwork(IGrid grid, MEStorage storage,
                                          AEKey what, long amount, IActionSource source) {
        Set<Object> cpuSet = null;
        List<Object> saved = null;
        try {
            var service = grid.getCraftingService();
            var field = service.getClass().getDeclaredField("craftingCPUClusters");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var set = (Set<Object>) field.get(service);
            cpuSet = set;
            saved = List.copyOf(set);
            set.clear();
        } catch (Throwable t) {
            ae2lunyi.LOGGER.warn("Wheelchair CPU: cannot bypass AutoCrafting storage", t);
        }
        try {
            return storage.insert(what, amount, Actionable.MODULATE, source);
        } finally {
            if (cpuSet != null && saved != null) {
                cpuSet.addAll(saved);
            }
        }
    }

    private static boolean expand(IGrid grid, Level level, AEKey what, long amount,
                                  Map<AEKey, IPatternDetails> byOutput,
                                  appeng.api.networking.crafting.ICraftingService craftingService,
                                  MEStorage storage, IActionSource source,
                                  Map<AEKey, Long> baseNeeds, Set<AEKey> path, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        // intermediate products: use stock when available. The target itself (depth 0) is always expanded.
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
        Check check = isRealRecipe(level, pattern);
        if (check == Check.STALE) {
            ae2lunyi.LOGGER.info(
                    "Wheelchair CPU: pattern for {} no longer matches its recipe, asking for a re-encode", what);
            STALE.set(true);
            return false;
        }
        if (check == Check.FORGED) {
            ae2lunyi.LOGGER.info("Wheelchair CPU: pattern for {} is not a real recipe, blocking", what);
            REJECTED.set(true);
            return false;
        }
        if (check == Check.UNKNOWN) {
            ae2lunyi.LOGGER.info(
                    "Wheelchair CPU: recipe for {} cannot be verified, handing over to other CPUs", what);
            UNVERIFIED.set(true);
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
                long need = possible[0].amount() * input.getMultiplier() * amount;
                if (possible[0].what().equals(what)) {
                    // 自引用配方（锻造模板复制这类）：产物同时是材料，
                    // 这份材料直接用网络存量，不再递归展开，避免无限套娃
                    baseNeeds.merge(what, need, Long::sum);
                    continue;
                }
                if (!expand(grid, level, possible[0].what(), need, byOutput, craftingService, storage,
                        source, baseNeeds, path, depth + 1)) {
                    return false;
                }
            }
        } finally {
            path.remove(what);
        }
        return true;
    }

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
                    var outs = details.getOutputs();
                    if (!outs.isEmpty() && outs.get(0).what().equals(what)) {
                        return details;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    private static Check isRealRecipe(Level level, IPatternDetails details) {
        if (!com.dianzhong.lunyi.Config.STRICT_PATTERN_VALIDATION.get() || level == null) {
            return Check.OK;
        }
        var outputs = details.getOutputs();
        if (outputs.isEmpty()) {
            return Check.FORGED;
        }
        // useless_mod omniversal patterns come from the mold system and cannot be verified here;
        // accept them as-is and craft them ourselves instead of handing them to other CPUs
        String detailsClass = details.getClass().getName();
        if (detailsClass.contains("OmniversalPattern") || detailsClass.contains("sorrowmist.useless")) {
            ae2lunyi.LOGGER.info("Wheelchair CPU: {} is an omniversal pattern, skipping validation",
                    outputs.get(0).what());
            return Check.OK;
        }

        AEKey wanted = outputs.get(0).what();
        boolean itemOutput = wanted instanceof AEItemKey;
        boolean verbose = com.dianzhong.lunyi.Config.LOG_VALIDATION.get();

        // byproducts must be produced by the very recipe that produces the primary output,
        // otherwise a pattern could smuggle an unrelated item in as a secondary output
        List<String> patternOutputIds = new ArrayList<>();
        for (GenericStack out : outputs) {
            patternOutputIds.add(String.valueOf(out.what().getId()));
        }

        // index is positive evidence only; a missing entry does not prove the pattern is forged
        Boolean indexed = com.dianzhong.lunyi.compat.CreatIdCompat.produces(String.valueOf(wanted.getId()));
        if (verbose) {
            ae2lunyi.LOGGER.info("validate {} indexed={} patternIn={}",
                    wanted, indexed, patternInputIds(details));
        }

        List<ItemStack> patternInputs = itemOutput ? patternInputStacks(details) : null;

        // crafting pattern: it stores the recipe id it was encoded from
        if (details instanceof appeng.crafting.pattern.AECraftingPattern craftingPattern) {
            var encoded = craftingPattern.getDefinition()
                    .get(appeng.api.ids.AEComponents.ENCODED_CRAFTING_PATTERN);
            if (encoded == null) {
                return Check.FORGED;
            }
            var holder = level.getRecipeManager().byKey(encoded.recipeId()).orElse(null);
            if (!(holder != null && holder.value() instanceof CraftingRecipe sourceRecipe)) {
                return Check.STALE;
            }
            if (patternInputs == null) {
                return Check.OK;
            }
            var ingredients = sourceRecipe.getIngredients().stream().filter(x -> !x.isEmpty()).toList();
            if (ingredients.size() != patternInputs.size()) {
                return Check.STALE;
            }
            List<ItemStack> pool = new ArrayList<>(patternInputs);
            for (Ingredient ingredient : ingredients) {
                int found = -1;
                for (int i = 0; i < pool.size(); i++) {
                    if (ingredient.test(pool.get(i))) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    return Check.STALE;
                }
                pool.remove(found);
            }
            return Check.OK;
        }

        List<String> patternIds = patternInputIds(details);
        boolean sawCandidate = false;

        for (var holder : level.getRecipeManager().getRecipes()) {
            Object recipe = holder.value();
            if (!recipeProduces(level, recipe, wanted)) {
                continue;
            }
            sawCandidate = true;

            if (!itemOutput) {
                var recipeIds = recipeInputIds(recipe);
                // the recipe must be fully covered by the pattern; tags are resolved by index slots
                var producerSlots = com.dianzhong.lunyi.compat.CreatIdCompat
                        .slotsOf(String.valueOf(holder.id()));
                boolean ok;
                if (!producerSlots.isEmpty()) {
                    ok = slotMatch(producerSlots, patternInputSlots(details));
                } else {
                    ok = !recipeIds.isEmpty() && subsetMatch(recipeIds, patternIds);
                }
                if (verbose) {
                    ae2lunyi.LOGGER.info("non-item check {} recipe={} patternIn={} recipeIn={} -> {}",
                            wanted, holder.id(), patternIds, recipeIds, ok ? "PASS" : "no-match");
                }
                if (ok && outputsCovered(recipeOutputIds(level, recipe), patternOutputIds)
                        && !overclaims(recipeOutputAmount(level, recipe, wanted), outputs)) {
                    return Check.OK;
                }
                continue;
            }

            if (patternInputs == null) {
                var recipeIds = recipeInputIds(recipe);
                // the recipe must be fully covered by the pattern; tags are resolved by index slots
                var producerSlots = com.dianzhong.lunyi.compat.CreatIdCompat
                        .slotsOf(String.valueOf(holder.id()));
                boolean ok;
                if (!producerSlots.isEmpty()) {
                    ok = slotMatch(producerSlots, patternInputSlots(details));
                } else {
                    ok = !recipeIds.isEmpty() && subsetMatch(recipeIds, patternIds);
                }
                if (verbose) {
                    ae2lunyi.LOGGER.info("mixed check {} recipe={} patternIn={} recipeIn={} -> {}",
                            wanted, holder.id(), patternIds, recipeIds, ok ? "PASS" : "no-match");
                }
                if (ok && outputsCovered(recipeOutputIds(level, recipe), patternOutputIds)
                        && !overclaims(recipeOutputAmount(level, recipe, wanted), outputs)) {
                    return Check.OK;
                }
                continue;
            }

            if (recipe instanceof CraftingRecipe craftingRecipe) {
                var ingredients = craftingRecipe.getIngredients().stream()
                        .filter(ing -> !ing.isEmpty())
                        .toList();
                if (ingredients.size() != patternInputs.size()) {
                    continue;
                }
                List<ItemStack> pool = new ArrayList<>(patternInputs);
                boolean ok = true;
                for (Ingredient ingredient : ingredients) {
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
                if (ok && outputsCovered(recipeOutputIds(level, recipe), patternOutputIds)
                        && !overclaims(recipeOutputAmount(level, recipe, wanted), outputs)) {
                    return Check.OK;
                }
                continue;
            }

            // EnderIO soul binding: getIngredients() reports [input, barrier, input] because the
            // soul slot is dynamic, so match against input()/output() instead of the generic path
            if (recipe.getClass().getName().contains("SoulBindingRecipe")) {
                Object rawInput = null;
                try {
                    var method = recipe.getClass().getMethod("input");
                    if (method.getParameterCount() == 0) {
                        rawInput = method.invoke(recipe);
                    }
                } catch (Throwable ignored) {
                    // fall through to the generic path
                }
                if (rawInput instanceof Ingredient ingredient && patternInputs != null
                        && patternInputs.stream().anyMatch(ingredient::test)
                        && soulMatches(recipe, patternInputs, ingredient)
                        && outputsCovered(recipeOutputIds(level, recipe), patternOutputIds)
                        && !overclaims(recipeOutputAmount(level, recipe, wanted), outputs)) {
                    if (verbose) {
                        ae2lunyi.LOGGER.info("soul-binding check {} recipe={} -> PASS", wanted, holder.id());
                    }
                    return Check.OK;
                }
                if (verbose) {
                    ae2lunyi.LOGGER.info("soul-binding check {} recipe={} -> no-match", wanted, holder.id());
                }
                continue;
            }

            List<ItemStack> machineInputs = recipeInputs(recipe);
            // unreadable inputs must NOT count as a match: fall through to the index, which has slots
            if (!machineInputs.isEmpty()
                    && sameMultiset(patternInputs, machineInputs)
                    && outputsCovered(recipeOutputIds(level, recipe), patternOutputIds)) {
                return Check.OK;
            }
        }

        // fall back to the recipe index: it can read recipes our local scan cannot (e.g. Draconic fusion)
        if (wanted != null) {
            List<String> patternSlots = patternInputSlots(details);
            for (String producerId : com.dianzhong.lunyi.compat.CreatIdCompat
                    .producersOf(String.valueOf(wanted.getId()))) {
                List<String> recipeSlots = com.dianzhong.lunyi.compat.CreatIdCompat.slotsOf(producerId);
                if (!recipeSlots.isEmpty()) {
                    boolean matched = slotMatch(recipeSlots, patternSlots)
                            && indexOutputsCovered(producerId, patternOutputIds)
                            && !overclaims(indexOutputAmount(producerId, wanted), outputs);
                    if (matched) {
                        if (verbose) {
                            ae2lunyi.LOGGER.info("index slot check {} matched recipe {} recipeSlots={} patternSlots={}",
                                    wanted, producerId, recipeSlots, patternSlots);
                        }
                        return Check.OK;
                    }
                    if (verbose) {
                        ae2lunyi.LOGGER.info("index slot MISS {} recipe={} recipeSlots={} patternSlots={}",
                                wanted, producerId, recipeSlots, patternSlots);
                    }
                    continue;
                }
                // old index format without slot info
                List<String> indexedInputs = com.dianzhong.lunyi.compat.CreatIdCompat.inputsOf(producerId);
                if (verbose) {
                    ae2lunyi.LOGGER.info("index diag {} producer={} slots={} inputs={}",
                            wanted, producerId, recipeSlots, indexedInputs);
                }
                // 配方必须被样板完整覆盖（单向），否则少写材料也能通过
                if (!indexedInputs.isEmpty() && subsetMatch(indexedInputs, patternIds)) {
                    if (verbose) {
                        ae2lunyi.LOGGER.info("index check {} matched recipe {}", wanted, producerId);
                    }
                    return Check.OK;
                }
            }
        }

        if (sawCandidate) {
            if (verbose) {
                ae2lunyi.LOGGER.info("validate {} is stale: a recipe exists but this pattern does not match it",
                        wanted);
            }
            return Check.STALE;
        }
        if (indexed != null) {
            if (!indexed) {
                // 服务端索引完全查不到：先问一次客户端 JEI（补空，不推翻任何已有判定）
                // 组件变体（药水、附魔物等）：JEI 只能证明“有配方”，无法校验材料，
                // 为了不让“少材料也能做”成为漏洞，这里直接拒绝
                if (String.valueOf(wanted).contains("component")) {
                    if (verbose) {
                        ae2lunyi.LOGGER.info("validate {} rejected: component variant, materials cannot be verified", wanted);
                    }
                    return Check.FORGED;
                }
                String itemId = String.valueOf(wanted.getId());
                if (com.dianzhong.lunyi.compat.jei.JeiFallback.isCached(itemId)) {
                    if (com.dianzhong.lunyi.compat.jei.JeiFallback.cachedFound(itemId)) {
                        if (verbose) {
                            ae2lunyi.LOGGER.info("validate {} accepted: JEI knows a recipe", wanted);
                        }
                        return Check.OK;
                    }
                } else {
                    String kind = "item";
                    String id = itemId;
                    String nbt = "";
                    if (wanted instanceof appeng.api.stacks.AEItemKey itemKey) {
                        var stack = itemKey.getReadOnlyStack();
                        var tag = stack.save(net.minecraft.core.RegistryAccess.EMPTY);
                        nbt = tag.toString();
                    } else if (wanted instanceof appeng.api.stacks.AEFluidKey fluidKey) {
                        kind = "fluid";
                    } else {
                        kind = "chemical";
                    }
                    com.dianzhong.lunyi.compat.jei.JeiFallback.request(itemId, kind, id, nbt);
                }
                // no recipe anywhere produces this item: forged
                if (verbose) {
                    ae2lunyi.LOGGER.info("validate {} rejected: no recipe anywhere", wanted);
                }
                return Check.FORGED;
            }
            // a recipe exists, but its inputs could not be matched above: do not trust it blindly
            if (verbose) {
                ae2lunyi.LOGGER.info("validate {} unknown: index has a recipe but inputs did not match", wanted);
            }
            return Check.UNKNOWN;
        }
        if (verbose) {
            ae2lunyi.LOGGER.info("validate {} accepted: index unavailable", wanted);
        }
        return Check.UNKNOWN;
    }

    private static boolean recipeProduces(Level level, Object recipe, AEKey wanted) {
        for (Method method : recipe.getClass().getMethods()) {
            String name = method.getName();
            // 覆盖 getOutput / getMainOutputDefinition / getSecondaryOutputDefinition 等常见命名
            boolean looksLikeOutput = name.equals("output") || name.equals("outputs")
                    || name.equals("result") || name.equals("results")
                    || name.equals("getResultItem") || name.equals("getResultStacks")
                    || (name.startsWith("get") && name.contains("Output"))
                    || (name.startsWith("get") && name.contains("Result"));
            if (!looksLikeOutput) {
                continue;
            }
            if (method.getParameterCount() > 1) {
                continue;
            }
            try {
                Object result = method.getParameterCount() == 0
                        ? method.invoke(recipe)
                        : method.invoke(recipe, level.registryAccess());
                if (stackMatches(result, wanted)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // overload not usable
            }
        }
        return false;
    }

    private static boolean stackMatches(Object result, AEKey wanted) {
        if (result == null) {
            return false;
        }
        if (result instanceof ItemStack stack) {
            if (stack.isEmpty() || !(wanted instanceof AEItemKey)) {
                return false;
            }
            // 按物品 id 比对：样板产物常带 NBT/组件，直接比 AEKey 会永远不相等
            var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return key != null && key.toString().equals(String.valueOf(wanted.getId()));
        }
        if (result instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (stackMatches(item, wanted)) {
                    return true;
                }
            }
            return false;
        }
        if (!(wanted instanceof AEItemKey)) {
            String id = nonItemId(result);
            return id != null && id.equals(String.valueOf(wanted.getId()));
        }
        return false;
    }

    @Nullable
    private static String nonItemId(Object value) {
        String mekId = com.dianzhong.lunyi.compat.MekanismCompat.chemicalId(value);
        if (mekId != null) {
            return mekId;
        }
        String cls = value.getClass().getSimpleName();
        try {
            if (cls.contains("FluidStack")) {
                Object fluid = value.getClass().getMethod("getFluid").invoke(value);
                if (fluid instanceof Fluid f) {
                    var key = BuiltInRegistries.FLUID.getKey(f);
                    return key == null ? null : key.toString();
                }
            }
            if (cls.contains("ChemicalStack")) {
                try {
                    Object holder = value.getClass().getMethod("getChemicalHolder").invoke(value);
                    Object optKey = holder.getClass().getMethod("unwrapKey").invoke(holder);
                    if (optKey instanceof java.util.Optional<?> opt && opt.isPresent()) {
                        Object key = opt.get();
                        Object loc = key.getClass().getMethod("location").invoke(key);
                        if (loc != null) {
                            return String.valueOf(loc);
                        }
                    }
                } catch (Throwable ignoredHolder) {
                    // fall through to the legacy path
                }
                Object chemical = value.getClass().getMethod("getChemical").invoke(value);
                if (chemical != null) {
                    for (String name : new String[]{"getRegistryName", "getId", "getName"}) {
                        try {
                            Object id = chemical.getClass().getMethod(name).invoke(chemical);
                            if (id != null) {
                                return extractId(id);
                            }
                        } catch (Throwable ignored) {
                            // try next accessor
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // unknown structure
        }
        // last resort: scan the object description for a namespaced id
        var matcher = java.util.regex.Pattern
                .compile("([a-z0-9_.-]+:[a-z0-9_/.-]+)")
                .matcher(String.valueOf(value));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractId(Object id) {
        String text = String.valueOf(id);
        var matcher = java.util.regex.Pattern
                .compile("([a-z0-9_.-]+:[a-z0-9_/.-]+)")
                .matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? text.substring(slash + 1) : text;
    }

    private static long nonItemAmount(Object value) {
        long mekAmount = com.dianzhong.lunyi.compat.MekanismCompat.chemicalAmount(value);
        if (mekAmount > 0) {
            return mekAmount;
        }
        for (String name : new String[]{"getAmount", "amount"}) {
            try {
                Object amount = value.getClass().getMethod(name).invoke(value);
                if (amount instanceof Number number) {
                    return number.longValue();
                }
            } catch (Throwable ignored) {
                // try next accessor
            }
        }
        return 1L;
    }

    private static List<ItemStack> recipeInputs(Object recipe) {
        List<ItemStack> out = new ArrayList<>();
        for (Method method : recipe.getClass().getMethods()) {
            String name = method.getName();
            boolean looksLikeInput = name.equals("getIngredients") || name.equals("getCatalyst")
                    || name.equals("catalyst")
                    || name.toLowerCase(java.util.Locale.ROOT).contains("input");
            if (!looksLikeInput || method.getParameterCount() != 0) {
                continue;
            }
            try {
                collectStacks(method.invoke(recipe), out);
            } catch (Throwable ignored) {
                // overload not usable
            }
        }
        return out;
    }

    private static void collectStacks(Object value, List<ItemStack> out) {
        if (value == null) {
            return;
        }
        if (value instanceof ItemStack stack) {
            if (!stack.isEmpty()) {
                out.add(stack);
            }
            return;
        }
        if (value instanceof Ingredient ingredient) {
            var items = ingredient.getItems();
            if (items.length > 0) {
                out.add(items[0]);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectStacks(item, out);
            }
            return;
        }
        // NeoForge SizedIngredient: 里面包着一个 Ingredient
        for (String accessor : new String[]{"ingredient", "getIngredient"}) {
            try {
                Object inner = value.getClass().getMethod(accessor).invoke(value);
                if (inner != null && inner != value) {
                    collectStacks(inner, out);
                    return;
                }
            } catch (Throwable ignored) {
                // 换下一个方法名
            }
        }
        try {
            Object reps = value.getClass().getMethod("getRepresentations").invoke(value);
            if (reps instanceof Iterable<?> iterableReps) {
                for (Object rep : iterableReps) {
                    collectStacks(rep, out);
                }
            }
        } catch (Throwable ignored) {
            // not an ingredient wrapper
        }
    }

    private static List<String> patternInputIds(IPatternDetails details) {
        List<String> out = new ArrayList<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible.length == 0) {
                continue;
            }
            String id = String.valueOf(possible[0].what().getId());
            long amount = possible[0].amount() * input.getMultiplier();
            boolean nonItem = !(possible[0].what() instanceof AEItemKey);
            if (nonItem) {
                out.add(id + "#" + amount);
            } else {
                for (int i = 0; i < input.getMultiplier(); i++) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static List<String> recipeInputIds(Object recipe) {
        List<String> out = new ArrayList<>();
        for (Method method : recipe.getClass().getMethods()) {
            String name = method.getName();
            boolean looksLikeInput = name.equals("getIngredients") || name.equals("getCatalyst")
                    || name.equals("catalyst")
                    || name.toLowerCase(java.util.Locale.ROOT).contains("input");
            if (!looksLikeInput || method.getParameterCount() != 0) {
                continue;
            }
            try {
                collectIds(method.invoke(recipe), out);
            } catch (Throwable ignored) {
                // overload not usable
            }
        }
        return out;
    }

    private static void collectIds(Object value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof ItemStack stack) {
            if (!stack.isEmpty()) {
                var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null) {
                    out.add(key.toString());
                }
            }
            return;
        }
        if (value instanceof Ingredient ingredient) {
            var items = ingredient.getItems();
            if (items.length > 0) {
                var key = BuiltInRegistries.ITEM.getKey(items[0].getItem());
                if (key != null) {
                    out.add(key.toString());
                }
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectIds(item, out);
            }
            return;
        }
        // NeoForge SizedIngredient
        for (String accessor : new String[]{"ingredient", "getIngredient"}) {
            try {
                Object inner = value.getClass().getMethod(accessor).invoke(value);
                if (inner != null && inner != value) {
                    collectIds(inner, out);
                    return;
                }
            } catch (Throwable ignored) {
                // 换下一个方法名
            }
        }
        try {
            Object reps = value.getClass().getMethod("getRepresentations").invoke(value);
            if (reps instanceof Iterable<?> iterableReps) {
                for (Object rep : iterableReps) {
                    collectIds(rep, out);
                }
                return;
            }
        } catch (Throwable ignored) {
            // not an ingredient wrapper
        }
        String id = nonItemId(value);
        if (id != null) {
            out.add(id + "#" + nonItemAmount(value));
        }
    }

    @Nullable
    private static List<ItemStack> patternInputStacks(IPatternDetails details) {
        List<ItemStack> out = new ArrayList<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible.length == 0) {
                continue;
            }
            if (!(possible[0].what() instanceof AEItemKey itemKey)) {
                return null;
            }
            long amount = possible[0].amount() * input.getMultiplier();
            for (long i = 0; i < amount; i++) {
                out.add(itemKey.toStack(1));
            }
        }
        return out;
    }

    /** Pattern inputs grouped per slot: "idA|idB#amount". */
    private static List<String> patternInputSlots(IPatternDetails details) {
        List<String> out = new ArrayList<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible.length == 0) {
                continue;
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            long amount = Long.MAX_VALUE;
            for (GenericStack option : possible) {
                ids.add(String.valueOf(option.what().getId()));
                amount = Math.min(amount, option.amount());
            }
            long total = (amount == Long.MAX_VALUE ? 1 : amount) * input.getMultiplier();
            out.add(String.join("|", ids) + "#" + total);
        }
        return out;
    }

    /** Every recipe slot must be covered by a pattern slot with an intersecting id and enough amount. */
    /** 只参与、不会被消耗的催化剂：校验时不算必需材料。 */
    private static final java.util.Set<String> CATALYSTS = java.util.Set.of(
            "mysticalagriculture:infusion_crystal",
            "mysticalagriculture:master_infusion_crystal",
            "mysticalagriculture:awakened_supremium_essence_crystal");

    private static boolean allCatalyst(String ids) {
        for (String id : ids.split("\\|")) {
            if (!CATALYSTS.contains(id.trim())) {
                return false;
            }
        }
        return true;
    }
    private static boolean slotMatch(List<String> recipeSlots, List<String> patternSlots) {
        // patterns often split N copies into N single-item inputs, while the index keeps one slot of N,
        // so aggregate the pattern amounts per id before comparing
        java.util.Map<String, Long> patternAmounts = new java.util.HashMap<>();
        for (String patternSlot : patternSlots) {
            String[] parts = splitSlot(patternSlot);
            if (parts == null) {
                continue;
            }
            long amount = parseAmount(parts[1]);
            for (String id : parts[0].split("\\|")) {
                patternAmounts.merge(id, amount, Long::sum);
            }
        }
        for (String recipeSlot : recipeSlots) {
            String[] recipeParts = splitSlot(recipeSlot);
            if (recipeParts == null) {
                continue;
            }
            if (allCatalyst(recipeParts[0])) {
                // 只参与、不消耗的催化剂（注魔水晶等）：不算必需材料
                continue;
            }
            long need = parseAmount(recipeParts[1]);
            boolean satisfied = false;
            for (String id : recipeParts[0].split("\\|")) {
                if (patternAmounts.getOrDefault(id, 0L) >= need) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static String[] splitSlot(String slot) {
        int at = slot.lastIndexOf('#');
        if (at <= 0) {
            return new String[]{slot, "1"};
        }
        return new String[]{slot.substring(0, at), slot.substring(at + 1)};
    }

    private static boolean intersects(String idsA, String idsB) {
        for (String a : idsA.split("\\|")) {
            for (String b : idsB.split("\\|")) {
                if (a.equals(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long parseAmount(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 1L;
        }
    }
    /** Accessors that commonly wrap an ItemStack inside a mod-specific output record. */
    private static final String[] OUTPUT_ACCESSORS =
            {"getStack", "stack", "getItem", "item", "getChancedStack", "getResult", "result", "getValue",
             "value", "getOutput", "output", "left", "right", "getFirst", "getSecond"};

    /** Recursively dig out item stacks from a mod-specific output wrapper (depth-limited). */
    private static void collectOutputIdsDeep(Object value, Set<String> out, int depth) {
        if (value == null || depth > 3) {
            return;
        }
        if (value instanceof ItemStack stack) {
            if (!stack.isEmpty()) {
                var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null) {
                    out.add(key.toString());
                }
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectOutputIdsDeep(item, out, depth + 1);
            }
            return;
        }
        String id = nonItemId(value);
        if (id != null) {
            out.add(id);
            return;
        }
        for (String accessor : OUTPUT_ACCESSORS) {
            try {
                Object inner = value.getClass().getMethod(accessor).invoke(value);
                if (inner != null && inner != value) {
                    collectOutputIdsDeep(inner, out, depth + 1);
                    return;
                }
            } catch (Throwable ignored) {
                // try next accessor
            }
        }
    }
    /** All output ids a recipe object declares (by id, NBT-insensitive). */
    private static Set<String> recipeOutputIds(Level level, Object recipe) {
        Set<String> out = new HashSet<>();
        for (Method method : recipe.getClass().getMethods()) {
            String name = method.getName();
            // 覆盖 getOutput / getMainOutputDefinition / getSecondaryOutputDefinition 等常见命名
            boolean looksLikeOutput = name.equals("output") || name.equals("outputs")
                    || name.equals("result") || name.equals("results")
                    || name.equals("getResultItem") || name.equals("getResultStacks")
                    || (name.startsWith("get") && name.contains("Output"))
                    || (name.startsWith("get") && name.contains("Result"));
            if (!looksLikeOutput) {
                continue;
            }
            if (method.getParameterCount() > 1) {
                continue;
            }
            try {
                Object result = method.getParameterCount() == 0
                        ? method.invoke(recipe)
                        : method.invoke(recipe, level.registryAccess());
                collectOutputIdsDeep(result, out, 0);
            } catch (Throwable ignored) {
                // overload not usable
            }
        }
        return out;
    }

    private static void collectOutputIds(Object value, Set<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof ItemStack stack) {
            if (!stack.isEmpty()) {
                var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null) {
                    out.add(key.toString());
                }
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectOutputIds(item, out);
            }
            return;
        }
        String id = nonItemId(value);
        if (id != null) {
            out.add(id);
            return;
        }
        for (String accessor : new String[]{"getStack", "stack", "getItem", "item"}) {
            try {
                Object inner = value.getClass().getMethod(accessor).invoke(value);
                if (inner instanceof ItemStack innerStack && !innerStack.isEmpty()) {
                    var key = BuiltInRegistries.ITEM.getKey(innerStack.getItem());
                    if (key != null) {
                        out.add(key.toString());
                    }
                    return;
                }
            } catch (Throwable ignored) {
                // try next accessor
            }
        }
    }

    /** Every pattern output must be among the matched recipe outputs. */
    private static boolean outputsCovered(Set<String> recipeOutputs, List<String> patternOutputIds) {
        if (recipeOutputs.isEmpty()) {
            return true;   // recipe outputs unreadable, cannot verify
        }
        for (String id : patternOutputIds) {
            if (!recipeOutputs.contains(id)) {
                return false;
            }
        }
        return true;
    }

    private static boolean indexOutputsCovered(String recipeId, List<String> patternOutputIds) {
        List<String> recipeOutputs = com.dianzhong.lunyi.compat.CreatIdCompat.outputsOf(recipeId);
        if (recipeOutputs.isEmpty()) {
            return true;
        }
        for (String id : patternOutputIds) {
            if (!recipeOutputs.contains(id)) {
                return false;
            }
        }
        return true;
    }
    /** Max declared output amount of {@code wanted} in this recipe (0 when unreadable). */
    private static long recipeOutputAmount(Level level, Object recipe, AEKey wanted) {
        String id = String.valueOf(wanted.getId());
        long best = 0;
        for (Method method : recipe.getClass().getMethods()) {
            String name = method.getName();
            // 覆盖 getOutput / getMainOutputDefinition / getSecondaryOutputDefinition 等常见命名
            boolean looksLikeOutput = name.equals("output") || name.equals("outputs")
                    || name.equals("result") || name.equals("results")
                    || name.equals("getResultItem") || name.equals("getResultStacks")
                    || (name.startsWith("get") && name.contains("Output"))
                    || (name.startsWith("get") && name.contains("Result"));
            if (!looksLikeOutput) {
                continue;
            }
            if (method.getParameterCount() > 1) {
                continue;
            }
            try {
                Object result;
                if (method.getParameterCount() == 0) {
                    result = method.invoke(recipe);
                } else {
                    try {
                        result = method.invoke(recipe, level.registryAccess());
                    } catch (Throwable inner) {
                        result = method.invoke(recipe, new Object[]{null});
                    }
                }
                best = Math.max(best, amountOf(result, id));
            } catch (Throwable ignored) {
                // overload not usable
            }
        }
        return best;
    }

    private static long amountOf(Object value, String id) {
        if (value == null) {
            return 0;
        }
        if (value instanceof ItemStack stack) {
            if (stack.isEmpty()) {
                return 0;
            }
            var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return key != null && key.toString().equals(id) ? stack.getCount() : 0;
        }
        if (value instanceof Iterable<?> iterable) {
            long best = 0;
            for (Object item : iterable) {
                best = Math.max(best, amountOf(item, id));
            }
            return best;
        }
        String other = nonItemId(value);
        if (id.equals(other)) {
            return nonItemAmount(value);
        }
        for (String accessor : new String[]{"getStack", "stack", "getItem", "item"}) {
            try {
                Object inner = value.getClass().getMethod(accessor).invoke(value);
                if (inner instanceof ItemStack innerStack && !innerStack.isEmpty()) {
                    var key = BuiltInRegistries.ITEM.getKey(innerStack.getItem());
                    if (key != null && key.toString().equals(id)) {
                        return innerStack.getCount();
                    }
                }
            } catch (Throwable ignored) {
                // try next accessor
            }
        }
        return 0;
    }

    private static long indexOutputAmount(String recipeId, AEKey wanted) {
        String id = String.valueOf(wanted.getId());
        long best = 0;
        var entries = com.dianzhong.lunyi.compat.CreatIdCompat.outputAmountsOf(recipeId);
        if (entries.isEmpty()) {
            entries = com.dianzhong.lunyi.compat.CreatIdCompat.outputsOf(recipeId);
        }
        for (String entry : entries) {
            int at = entry.lastIndexOf('x');
            String entryId = at > 0 ? entry.substring(0, at) : entry;
            if (!entryId.equals(id)) {
                continue;
            }
            try {
                best = Math.max(best, Long.parseLong(entry.substring(at + 1)));
            } catch (Throwable ignored) {
                best = Math.max(best, 1);
            }
        }
        return best;
    }
    /** Pattern must not claim more per craft than the real recipe produces. */
    private static boolean overclaims(long recipeAmount, List<GenericStack> outputs) {
        boolean tooMuch = recipeAmount > 0 && !outputs.isEmpty()
                && outputs.get(0).amount() > recipeAmount;
        if (com.dianzhong.lunyi.Config.LOG_VALIDATION.get()) {
            ae2lunyi.LOGGER.info("overclaim check recipeAmount={} patternAmount={} -> {}",
                    recipeAmount, outputs.isEmpty() ? 0 : outputs.get(0).amount(),
                    tooMuch ? "REJECT" : "ok");
        }
        return tooMuch;
    }
    /** Every required id must be present in actual; extra ids in actual are allowed (tags). */
    private static boolean subsetMatch(List<String> required, List<String> actual) {
        Set<String> actualSet = new HashSet<>();
        for (String entry : actual) {
            int at = entry.indexOf('#');
            actualSet.add(at > 0 ? entry.substring(0, at) : entry);
        }
        if (actualSet.isEmpty()) {
            return false;
        }
        for (String entry : required) {
            int at = entry.indexOf('#');
            String id = at > 0 ? entry.substring(0, at) : entry;
            if (!actualSet.contains(id)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameMultiset(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<ItemStack> pool = new ArrayList<>(b);
        for (ItemStack want : a) {
            int found = -1;
            for (int i = 0; i < pool.size(); i++) {
                if (ItemStack.isSameItem(pool.get(i), want)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            pool.remove(found);
        }
        return true;
    }

    /**
     * 灵魂绑定：配方通过 entityType() 声明需要哪种生物的灵魂，而灵魂瓶的实体会存进物品组件里。
     * 这里要求样板里除了「被绑定物品」之外，至少还有一个输入的道具数据里带着该实体 id。
     */
    private static boolean soulMatches(Object recipe, List<ItemStack> patternInputs, Ingredient boundItem) {
        Object entityType = null;
        try {
            var method = recipe.getClass().getMethod("entityType");
            if (method.getParameterCount() == 0) {
                entityType = method.invoke(recipe);
            }
        } catch (Throwable ignored) {
            return true;   // 读不到实体要求就不额外限制
        }
        if (!(entityType instanceof java.util.Optional<?> optional) || optional.isEmpty()) {
            return true;
        }
        String wanted = String.valueOf(optional.get());
        // ResourceKey 的 toString 是 "ResourceKey[minecraft:entity_type / minecraft:zombie]"，
        // 只保留最后那段 id
        int slash = wanted.lastIndexOf('/');
        if (slash >= 0) {
            wanted = wanted.substring(slash + 1).replace("]", "").trim();
        }
        for (ItemStack stack : patternInputs) {
            if (stack.isEmpty() || boundItem.test(stack)) {
                continue;   // 跳过被绑定物品本身
            }
            if (String.valueOf(stack.getComponents()).contains(wanted)) {
                return true;
            }
        }
        return false;
    }
}

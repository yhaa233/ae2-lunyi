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

    /** Result of validating one pattern. */
    public enum Check {
        /** Pattern matches a real recipe. */
        OK,
        /** Pattern contradicts a real recipe: must be blocked. */
        FORGED,
        /** No recipe information available: let other CPUs handle it. */
        UNKNOWN
    }

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

    public static boolean perform(IGrid grid, @Nullable GenericStack output) {
        if (output == null) {
            return false;
        }
        REJECTED.set(false);
        UNVERIFIED.set(false);

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
                if (possible[0].what().equals(what)) {
                    ae2lunyi.LOGGER.info(
                            "Wheelchair CPU: self-referential pattern for {}, handing back to AE2", what);
                    return false;
                }
                long need = possible[0].amount() * input.getMultiplier() * amount;
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
        // useless_mod omniversal patterns come from the mold system: we cannot verify them, so outsource
        String detailsClass = details.getClass().getName();
        if (detailsClass.contains("OmniversalPattern") || detailsClass.contains("sorrowmist.useless")) {
            ae2lunyi.LOGGER.info("Wheelchair CPU: {} is an omniversal pattern, outsourcing", outputs.get(0).what());
            return Check.UNKNOWN;
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
                return Check.FORGED;
            }
            if (patternInputs == null) {
                return Check.OK;
            }
            var ingredients = sourceRecipe.getIngredients().stream().filter(x -> !x.isEmpty()).toList();
            if (ingredients.size() != patternInputs.size()) {
                return Check.FORGED;
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
                    return Check.FORGED;
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
                boolean ok = !recipeIds.isEmpty()
                        && (subsetMatch(patternIds, recipeIds)
                        || subsetMatch(recipeIds, patternIds));
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
                boolean ok = !recipeIds.isEmpty()
                        && (subsetMatch(patternIds, recipeIds)
                        || subsetMatch(recipeIds, patternIds));
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

            List<ItemStack> machineInputs = recipeInputs(recipe);
            if ((machineInputs.isEmpty() || sameMultiset(patternInputs, machineInputs))
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
                    if (slotMatch(recipeSlots, patternSlots)
                            && indexOutputsCovered(producerId, patternOutputIds)
                            && !overclaims(indexOutputAmount(producerId, wanted), outputs)) {
                        if (verbose) {
                            ae2lunyi.LOGGER.info("index slot check {} matched recipe {} recipeSlots={} patternSlots={}",
                                    wanted, producerId, recipeSlots, patternSlots);
                        }
                        return Check.OK;
                    }
                    continue;
                }
                // old index format without slot info
                List<String> indexedInputs = com.dianzhong.lunyi.compat.CreatIdCompat.inputsOf(producerId);
                if (!indexedInputs.isEmpty()
                        && (subsetMatch(patternIds, indexedInputs)
                        || subsetMatch(indexedInputs, patternIds))) {
                    if (verbose) {
                        ae2lunyi.LOGGER.info("index check {} matched recipe {}", wanted, producerId);
                    }
                    return Check.OK;
                }
            }
        }

        if (sawCandidate) {
            if (verbose) {
                ae2lunyi.LOGGER.info("validate {} rejected: candidate exists but inputs differ", wanted);
            }
            return Check.FORGED;
        }
        if (indexed != null) {
            // index is complete enough: no recipe anywhere means the item cannot be crafted at all
            if (verbose) {
                ae2lunyi.LOGGER.info("validate {} {} (indexed={})",
                        wanted, indexed ? "accepted: index has recipe" : "rejected: no recipe anywhere", indexed);
            }
            return indexed ? Check.OK : Check.UNKNOWN;
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
                    || name.matches("get[A-Za-z]*Output[A-Za-z]*")
                    || name.matches("get[A-Za-z]*Result[A-Za-z]*");
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
            boolean looksLikeInput = name.equals("getIngredients")
                    || name.matches("get[A-Za-z]*Input[A-Za-z]*");
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
            boolean looksLikeInput = name.equals("getIngredients")
                    || name.matches("get[A-Za-z]*Input[A-Za-z]*");
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
    private static boolean slotMatch(List<String> recipeSlots, List<String> patternSlots) {
        for (String recipeSlot : recipeSlots) {
            String[] recipeParts = splitSlot(recipeSlot);
            if (recipeParts == null) {
                continue;
            }
            boolean satisfied = false;
            for (String patternSlot : patternSlots) {
                String[] patternParts = splitSlot(patternSlot);
                if (patternParts == null) {
                    continue;
                }
                if (!intersects(recipeParts[0], patternParts[0])) {
                    continue;
                }
                long need = parseAmount(recipeParts[1]);
                long have = parseAmount(patternParts[1]);
                if (have >= need) {
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
                    || name.matches("get[A-Za-z]*Output[A-Za-z]*")
                    || name.matches("get[A-Za-z]*Result[A-Za-z]*");
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
                    || name.matches("get[A-Za-z]*Output[A-Za-z]*")
                    || name.matches("get[A-Za-z]*Result[A-Za-z]*");
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
}

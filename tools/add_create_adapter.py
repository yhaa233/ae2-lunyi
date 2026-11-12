import re

PATH = r"E:\creat-id\src\main\java\com\dianzhong\creatid\RecipeScanner.java"
with open(PATH, encoding="utf-8") as fh:
    text = fh.read()

adapter = '''
    /**
     * 机械动力专用适配。
     * <p>
     * Create 的配方（ProcessingRecipe 及其子类）把材料放在
     * {@code ingredients} / {@code fluidIngredients} 两个字段里，
     * 序列装配还有 {@code sequences} 与 {@code transitionalItem}。
     * 这里按确切字段名读取，避开通用扫描的各种猜测。
     *
     * @return true 表示这条配方已由本适配处理
     */
    private static boolean collectCreate(Object recipe, List<String> slots, List<String> inputs) {
        if (!recipe.getClass().getName().startsWith("com.simibubi.create")) {
            return false;
        }
        boolean handled = false;
        for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {
            String name = field.getName().toLowerCase(java.util.Locale.ROOT);
            boolean isItemInput = name.equals("ingredients") || name.equals("input")
                    || name.equals("inputs");
            boolean isFluidInput = name.equals("fluidingredients") || name.equals("inputfluid")
                    || name.equals("fluidinput");
            boolean isSequence = name.equals("sequences");
            if (!isItemInput && !isFluidInput && !isSequence) {
                continue;
            }
            try {
                Object value = field.get(recipe);
                if (value == null) {
                    continue;
                }
                handled = true;
                if (isFluidInput) {
                    List<String> fluidSlots = new ArrayList<>();
                    collectFluidValue(value, fluidSlots);
                    for (String slot : fluidSlots) {
                        if (!slots.contains(slot)) {
                            slots.add(slot);
                        }
                    }
                } else if (isSequence) {
                    if (value instanceof Iterable<?> steps) {
                        for (Object step : steps) {
                            Object inner = step;
                            for (String accessor : new String[]{"getRecipe", "recipe"}) {
                                try {
                                    Object candidate = step.getClass().getMethod(accessor).invoke(step);
                                    if (candidate != null) {
                                        inner = candidate;
                                        break;
                                    }
                                } catch (Throwable ignored) {
                                    // 换下一个名字
                                }
                            }
                            collectCreateInner(inner, slots, inputs);
                        }
                    }
                } else {
                    List<String> itemSlots = new ArrayList<>();
                    collectSlots(value, itemSlots);
                    List<String> flat = new ArrayList<>();
                    collectFlat(value, flat);
                    for (String slot : itemSlots) {
                        if (!slots.contains(slot)) {
                            slots.add(slot);
                        }
                    }
                    for (String option : flat) {
                        if (!inputs.contains(option)) {
                            inputs.add(option);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 读不到就跳过
            }
        }
        return handled;
    }

    /** 序列装配里每一道工序的内层配方。 */
    private static void collectCreateInner(Object inner, List<String> slots, List<String> inputs) {
        java.util.List<String> innerSlots = new ArrayList<>();
        for (String accessor : new String[]{"getIngredients", "getFluidIngredients"}) {
            try {
                Object value = inner.getClass().getMethod(accessor).invoke(inner);
                if (value == null) {
                    continue;
                }
                if (accessor.contains("Fluid")) {
                    collectFluidValue(value, innerSlots);
                } else {
                    collectSlots(value, innerSlots);
                }
            } catch (Throwable ignored) {
                // 该配方没有这个方法
            }
        }
        for (String slot : innerSlots) {
            if (!slots.contains(slot)) {
                slots.add(slot);
            }
        }
    }

    /** 流体列表 / 单个流体 / 流体标签。 */
    private static void collectFluidValue(Object value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof net.neoforged.neoforge.fluids.FluidStack stack) {
            if (!stack.isEmpty()) {
                out.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.FLUID
                        .getKey(stack.getFluid())) + "#" + stack.getAmount());
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collectFluidValue(element, out);
            }
            return;
        }
        try {
            Object stacks = value.getClass().getMethod("getStacks").invoke(value);
            if (stacks instanceof net.neoforged.neoforge.fluids.FluidStack[] array && array.length > 0) {
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                long amount = 1;
                for (net.neoforged.neoforge.fluids.FluidStack stack : array) {
                    if (!stack.isEmpty()) {
                        ids.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.FLUID
                                .getKey(stack.getFluid())));
                        amount = Math.max(amount, stack.getAmount());
                    }
                }
                if (!ids.isEmpty()) {
                    out.add(String.join("|", ids) + "#" + amount);
                }
            }
        } catch (Throwable ignored) {
            // 不是流体原料
        }
    }

'''

anchor = "    private static List<String> flatInputs(Object recipe) {"
if "collectCreate(" not in text:
    text = text.replace(anchor, adapter + anchor, 1)

# 在生成快照前调用专用适配
call = '''
        // 机械动力：按确切字段名读取，优先于通用扫描
        collectCreate(recipe, slots, inputs);

        slots.removeIf(RecipeScanner::isCatalystSlot);'''
old_call = '''
        slots.removeIf(RecipeScanner::isCatalystSlot);'''
if "collectCreate(recipe, slots, inputs);" not in text:
    text = text.replace(old_call, call, 1)

with open(PATH, "w", encoding="utf-8") as fh:
    fh.write(text)
print("create adapter inserted")

package com.dianzhong.lunyi.compat.jei;

import com.dianzhong.lunyi.ae2lunyi;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 客户端侧：请 JEI 判断“有没有产出这个原料的配方”。
 * <p>
 * 不再写死 JEI 的原料类型：而是构造若干可能的原料对象（原版物品栈、NeoForge 流体、
 * Architectury 流体、Mekanism 化学品），再交给 JEI 的
 * {@code createTypedIngredient} 自动识别类型。这样任何 JEI 能识别的包装都能覆盖。
 */
public final class JeiRecipeExtractor {
    private JeiRecipeExtractor() {
    }

    public static boolean hasRecipe(String kind, String id, String nbt) {
        IJeiRuntime runtime = JeiRuntimeHolder.get();
        if (runtime == null) {
            return false;
        }
        try {
            IIngredientManager manager = runtime.getIngredientManager();
            IFocusFactory factory = runtime.getJeiHelpers().getFocusFactory();
            for (Object candidate : candidates(kind, id)) {
                if (candidate == null) {
                    continue;
                }
                Optional<ITypedIngredient<Object>> typed = manager.createTypedIngredient(candidate);
                if (typed.isEmpty()) {
                    continue;
                }
                IFocus<?> focus = factory.createFocus(RecipeIngredientRole.OUTPUT, typed.get());
                boolean found = manager != null && runtime.getRecipeManager()
                        .createRecipeCategoryLookup()
                        .limitFocus(List.of(focus))
                        .get()
                        .findAny()
                        .isPresent();
                if (found) {
                    ae2lunyi.LOGGER.info("JEI fallback: {} matched as {}", id, typed.get().getType().getUid());
                    return true;
                }
            }
        } catch (Throwable t) {
            ae2lunyi.LOGGER.warn("JEI fallback: query failed for {}:{}", kind, id, t);
        }
        return false;
    }

    /** 依次构造同一原料在不同体系下的表示。 */
    private static List<Object> candidates(String kind, String id) {
        List<Object> out = new ArrayList<>();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return out;
        }
        switch (kind) {
            case "fluid" -> {
                if (!BuiltInRegistries.FLUID.containsKey(rl)) {
                    return out;
                }
                var fluid = BuiltInRegistries.FLUID.get(rl);
                // NeoForge
                out.add(new net.neoforged.neoforge.fluids.FluidStack(fluid, 1000));
                // Architectury（奥瑞科技等使用）
                try {
                    Class<?> cls = Class.forName("dev.architectury.fluid.FluidStack");
                    out.add(cls.getMethod("create", net.minecraft.world.level.material.Fluid.class, long.class)
                            .invoke(null, fluid, 1000L));
                } catch (Throwable ignored) {
                    // 没有 Architectury
                }
            }
            case "chemical" -> {
                try {
                    Class<?> apiClass = Class.forName("mekanism.api.MekanismAPI");
                    Object registry = apiClass.getField("CHEMICAL_REGISTRY").get(null);
                    Object optional = registry.getClass().getMethod("get", ResourceLocation.class)
                            .invoke(registry, rl);
                    Object chemical = optional instanceof Optional<?> opt ? opt.orElse(null) : optional;
                    if (chemical != null) {
                        Class<?> stackClass = Class.forName("mekanism.api.chemical.ChemicalStack");
                        Class<?> chemicalClass = Class.forName("mekanism.api.chemical.Chemical");
                        out.add(stackClass.getConstructor(chemicalClass, long.class)
                                .newInstance(chemical, 1000L));
                    }
                } catch (Throwable ignored) {
                    // 没有 Mekanism
                }
            }
            default -> {
                if (BuiltInRegistries.ITEM.containsKey(rl)) {
                    out.add(new ItemStack(BuiltInRegistries.ITEM.get(rl)));
                }
            }
        }
        return out;
    }
}
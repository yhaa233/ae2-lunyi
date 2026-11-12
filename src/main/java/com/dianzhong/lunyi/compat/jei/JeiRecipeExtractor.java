package com.dianzhong.lunyi.compat.jei;

import com.dianzhong.lunyi.ae2lunyi;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * 客户端侧：请 JEI 判断“有没有产出这个原料的配方”。
 * <p>
 * 支持物品 / 流体 / 化学品（化学品用反射走 Mekanism 的 JEI 类型，避免编译期依赖）。
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
            IFocusFactory focusFactory = runtime.getJeiHelpers().getFocusFactory();
            IFocus<?> focus = switch (kind) {
                case "fluid" -> fluidFocus(focusFactory, id);
                case "chemical" -> chemicalFocus(focusFactory, id);
                default -> itemFocus(focusFactory, id);
            };
            if (focus == null) {
                return false;
            }
            return runtime.getRecipeManager()
                    .createRecipeCategoryLookup()
                    .limitFocus(List.of(focus))
                    .get()
                    .findAny()
                    .isPresent();
        } catch (Throwable t) {
            ae2lunyi.LOGGER.warn("JEI fallback: query failed for {}:{}", kind, id, t);
            return false;
        }
    }

    private static IFocus<?> itemFocus(IFocusFactory factory, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return null;
        }
        return factory.createFocus(RecipeIngredientRole.OUTPUT,
                mezz.jei.api.constants.VanillaTypes.ITEM_STACK,
                new ItemStack(BuiltInRegistries.ITEM.get(rl)));
    }

    private static IFocus<?> fluidFocus(IFocusFactory factory, String id) throws Exception {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.FLUID.containsKey(rl)) {
            return null;
        }
        FluidStack stack = new FluidStack(BuiltInRegistries.FLUID.get(rl), 1000);
        // NeoForgeTypes 在某些 JEI 制品里不随 API 提供，这里反射取，避免编译期依赖
        Class<?> types = Class.forName("mezz.jei.api.neoforge.NeoForgeTypes");
        Object fluidType = types.getField("FLUID_STACK").get(null);
        return factory.createFocus(RecipeIngredientRole.OUTPUT,
                (IIngredientType<Object>) fluidType, stack);
    }

    /** 化学品：反射拿 Mekanism 的 ChemicalStack 与 JEI 类型。 */
    private static IFocus<?> chemicalFocus(IFocusFactory factory, String id) throws Exception {
        try {
            Class<?> apiClass = Class.forName("mekanism.api.MekanismAPI");
            Object registry = apiClass.getField("CHEMICAL_REGISTRY").get(null);
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                return null;
            }
            Object optional = registry.getClass().getMethod("get", ResourceLocation.class).invoke(registry, rl);
            Object chemical = optional instanceof java.util.Optional<?> opt ? opt.orElse(null) : optional;
            if (chemical == null) {
                return null;
            }
            Class<?> stackClass = Class.forName("mekanism.api.chemical.ChemicalStack");
            Object stack = stackClass.getConstructor(chemical.getClass().getInterfaces().length > 0
                            ? Class.forName("mekanism.api.chemical.Chemical") : chemical.getClass(),
                    1L <= 0 ? long.class : long.class)
                    .newInstance(chemical, 1000L);
            Class<?> jeiClass = Class.forName("mekanism.client.jei.MekanismJEI");
            Object type = jeiClass.getField("TYPE_CHEMICAL").get(null);
            return factory.createFocus(RecipeIngredientRole.OUTPUT,
                    (IIngredientType<Object>) type, stack);
        } catch (Throwable t) {
            return null;
        }
    }
}
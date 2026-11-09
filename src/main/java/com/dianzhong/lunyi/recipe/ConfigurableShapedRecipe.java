package com.dianzhong.lunyi.recipe;

import com.dianzhong.lunyi.Config;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 配方内容由配置文件决定的 3×3 有序配方。
 * <p>
 * JSON 里只写一个 {@code key}（"wheelchair" 或 "core"），真正的 9 个材料在运行时
 * 从 {@link Config} 读取，所以玩家可以自己改合成表而不需要数据包。
 */
public class ConfigurableShapedRecipe implements CraftingRecipe {
    /** 配置里的键，同时也是本条配方的唯一标识。 */
    private final String key;
    private final CraftingBookCategory category;

    public ConfigurableShapedRecipe(String key, CraftingBookCategory category) {
        this.key = key;
        this.category = category;
    }

    public String getKey() {
        return this.key;
    }

    /** 该配方对应的 9 个材料（可能含 "air" 或 "#tag"）。 */
    public static List<? extends String> ingredientsFor(String key) {
        return "core".equals(key)
                ? Config.CORE_RECIPE.get()
                : Config.WHEELCHAIR_RECIPE.get();
    }

    /** 该配方的产物。 */
    public static ItemStack resultFor(String key) {
        return "core".equals(key)
                ? new ItemStack(com.dianzhong.lunyi.registry.ModBlocks.WHEELCHAIR_CPU_ITEM.get())
                : new ItemStack(com.dianzhong.lunyi.registry.ModItems.WHEELCHAIR_SPAWN_EGG.get());
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (input.width() != 3 || input.height() != 3) {
            return false;
        }
        List<? extends String> wanted = ingredientsFor(this.key);
        if (wanted.size() < 9) {
            return false;
        }
        for (int i = 0; i < 9; i++) {
            String want = wanted.get(i);
            ItemStack have = input.getItem(i);
            if (want == null || want.isEmpty() || "air".equals(want) || "minecraft:air".equals(want)) {
                if (!have.isEmpty()) {
                    return false;
                }
                continue;
            }
            if (!matchesIngredient(want, have)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesIngredient(String want, ItemStack have) {
        if (have.isEmpty()) {
            return false;
        }
        if (want.startsWith("#")) {
            var tag = net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ITEM,
                    ResourceLocation.parse(want.substring(1)));
            return have.is(tag);
        }
        var id = ResourceLocation.tryParse(want);
        if (id == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item != null && have.is(item);
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return resultFor(this.key).copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= 3 && height >= 3;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return resultFor(this.key).copy();
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        return NonNullList.withSize(input.size(), ItemStack.EMPTY);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return com.dianzhong.lunyi.registry.ModRecipes.CONFIGURABLE_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return RecipeType.CRAFTING;
    }

    @Override
    public CraftingBookCategory category() {
        return this.category;
    }

    @Override
    public boolean isSpecial() {
        // 让配方书不要尝试展示它（材料是运行时决定的）
        return true;
    }
}

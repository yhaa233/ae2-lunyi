package com.dianzhong.lunyi.registry;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.recipe.ConfigurableShapedRecipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 我们自己的配方序列化器：JSON 里只需要一个 {@code key}。
 */
public final class ModRecipes {
    private ModRecipes() {}

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, ae2lunyi.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<ConfigurableShapedRecipe>> CONFIGURABLE_SERIALIZER =
            SERIALIZERS.register("configurable", () -> new RecipeSerializer<ConfigurableShapedRecipe>() {
                private final MapCodec<ConfigurableShapedRecipe> codec =
                        RecordCodecBuilder.mapCodec(instance -> instance.group(
                                com.mojang.serialization.Codec.STRING.fieldOf("key")
                                        .forGetter(ConfigurableShapedRecipe::getKey),
                                CraftingBookCategory.CODEC.optionalFieldOf("category", CraftingBookCategory.MISC)
                                        .forGetter(ConfigurableShapedRecipe::category)
                        ).apply(instance, ConfigurableShapedRecipe::new));

                private final StreamCodec<RegistryFriendlyByteBuf, ConfigurableShapedRecipe> streamCodec =
                        StreamCodec.composite(
                                net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8,
                                ConfigurableShapedRecipe::getKey,
                                (key) -> new ConfigurableShapedRecipe(key, CraftingBookCategory.MISC));

                @Override
                public MapCodec<ConfigurableShapedRecipe> codec() {
                    return this.codec;
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, ConfigurableShapedRecipe> streamCodec() {
                    return this.streamCodec;
                }
            });
}

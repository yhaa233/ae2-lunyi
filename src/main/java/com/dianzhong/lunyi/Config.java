package com.dianzhong.lunyi;

import com.dianzhong.lunyi.ae2lunyi;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    /** 是否把「轮椅合成核心」作为合成 CPU 暴露给 AE2。默认关闭。 */
    public static final ModConfigSpec.BooleanValue WHEELCHAIR_CPU_ENABLED = BUILDER
            .comment("Expose the wheelchair crafting core as an AE2 crafting CPU (only when a core or wheelchair is on the grid). Default: true")
            .define("wheelchairCpuEnabled", true);

    /** 轮椅（生成蛋）的 3x3 配方，按行排列，共 9 项；air 表示空。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WHEELCHAIR_RECIPE = BUILDER
            .comment("3x3 ingredients for the wheelchair spawn egg, row by row (use 'air' for empty, '#tag' for item tags)")
            .defineListAllowEmpty("wheelchairRecipe",
                    List.of("minecraft:stick", "minecraft:stick", "minecraft:stick",
                            "minecraft:stick", "#minecraft:logs", "minecraft:stick",
                            "minecraft:stick", "minecraft:stick", "minecraft:stick"),
                    () -> "minecraft:air", Config::validateIngredient);

    /** 轮椅合成核心的 3x3 配方，按行排列，共 9 项；air 表示空。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CORE_RECIPE = BUILDER
            .comment("3x3 ingredients for the wheelchair crafting core, row by row (use 'air' for empty, '#tag' for item tags)")
            .defineListAllowEmpty("coreRecipe",
                    List.of("minecraft:stick", "minecraft:stick", "minecraft:stick",
                            "minecraft:stick", "ae2_lunyi:wheelchair_spawn_egg", "minecraft:stick",
                            "minecraft:stick", "minecraft:stick", "minecraft:stick"),
                    () -> "minecraft:air", Config::validateIngredient);
    /** 样板仓是否接受所有编码样板。false 时只接受合成样板（crafting pattern）。 */
    public static final ModConfigSpec.BooleanValue PATTERN_STORE_ALL_PATTERNS = BUILDER
            .comment("Accept every encoded pattern. When false, only crafting patterns are allowed.")
            .define("patternStoreAllPatterns", true);

    /**
     * 严格校验。false 时：轮椅/核心的样板仓只收合成样板，且合成树中每个样板都必须能在
     * 服务端的配方表里找到对应配方，否则视为不可用（防止虚空请求）。
     */
    public static final ModConfigSpec.BooleanValue STRICT_PATTERN_VALIDATION = BUILDER
            .comment("Validate patterns against the recipe manager. When false, only real crafting recipes are used.")
            .define("strictPatternValidation", true);

    /**
     * 自动拦截。true（默认）：网络里有轮椅/核心时，即使没指定 CPU 也会由轮椅核心接管；
     * false：只有玩家在 CPU 列表里明确选中轮椅核心时才会拦截。
     */
    public static final ModConfigSpec.BooleanValue AUTO_INTERCEPT_CRAFTING = BUILDER
            .comment("Intercept crafting even when no CPU is explicitly selected. Default: true")
            .define("autoInterceptCrafting", true);

    /** Rider is immune to harmful potion effects while seated. */
    public static final ModConfigSpec.BooleanValue RIDER_IMMUNE_HARMFUL = BUILDER
            .comment("While riding, remove all harmful potion effects from the rider.")
            .define("riderImmuneToHarmfulEffects", true);

    /** Tell the player to add a non-wheelchair CPU when a job cannot be delegated. */
    public static final ModConfigSpec.BooleanValue NOTIFY_NO_FALLBACK_CPU = BUILDER
            .comment("Notify the player when a job needs another CPU but none is available.")
            .define("notifyNoFallbackCpu", true);

    /** Log every pattern validation decision with full input lists. */
    public static final ModConfigSpec.BooleanValue LOG_VALIDATION = BUILDER
            .comment("Log pattern validation decisions with input lists, for debugging.")
            .define("logValidation", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    /** 配方材料校验：允许物品 id、#标签、以及 air。 */
    private static boolean validateIngredient(final Object obj) {
        if (!(obj instanceof String name)) {
            return false;
        }
        if (name.startsWith("#")) {
            var id = ResourceLocation.tryParse(name.substring(1));
            return id != null;
        }
        var id = ResourceLocation.tryParse(name);
        return id != null && (BuiltInRegistries.ITEM.containsKey(id) || "minecraft:air".equals(name));
    }
    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}

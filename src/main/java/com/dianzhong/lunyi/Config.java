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

/**
 * 模组配置。
 * <p>
 * 这里的中文注释会写进 {@code config/ae2_lunyi-common.toml}，方便直接读配置文件。
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ------------------------------------------------------------------
    // 模板自带的示例项（保留以免影响启动日志，可忽略）
    // ------------------------------------------------------------------

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("是否在通用初始化阶段打印泥土方块")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("一个魔法数字（示例项，可忽略）")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("魔法数字的介绍语前缀（示例项，可忽略）")
            .define("magicNumberIntroduction", "The magic number is... ");

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("通用初始化阶段要打印的物品列表（示例项，可忽略）")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    // ------------------------------------------------------------------
    // 轮椅相关
    // ------------------------------------------------------------------

    /** 是否把「轮椅合成核心」作为合成 CPU 暴露给 AE2。 */
    public static final ModConfigSpec.BooleanValue WHEELCHAIR_CPU_ENABLED = BUILDER
            .comment("是否把「轮椅合成核心」作为 AE2 的合成 CPU 暴露出来（仅当网络里有核心或轮椅时生效）。默认：true")
            .define("wheelchairCpuEnabled", true);

    /** 轮椅（生成蛋）的 3x3 配方，按行排列，共 9 项；air 表示空。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WHEELCHAIR_RECIPE = BUILDER
            .comment("轮椅生成蛋的 3x3 配方，按从左到右、从上到下排列共 9 项；'air' 表示空位，'#标签' 表示物品标签")
            .defineListAllowEmpty("wheelchairRecipe",
                    List.of("minecraft:stick", "minecraft:stick", "minecraft:stick",
                            "minecraft:stick", "#minecraft:logs", "minecraft:stick",
                            "minecraft:stick", "minecraft:stick", "minecraft:stick"),
                    () -> "minecraft:air", Config::validateIngredient);

    /** 轮椅合成核心的 3x3 配方，按行排列，共 9 项；air 表示空。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CORE_RECIPE = BUILDER
            .comment("轮椅合成核心的 3x3 配方，按从左到右、从上到下排列共 9 项；'air' 表示空位，'#标签' 表示物品标签")
            .defineListAllowEmpty("coreRecipe",
                    List.of("minecraft:stick", "minecraft:stick", "minecraft:stick",
                            "minecraft:stick", "ae2_lunyi:wheelchair_spawn_egg", "minecraft:stick",
                            "minecraft:stick", "minecraft:stick", "minecraft:stick"),
                    () -> "minecraft:air", Config::validateIngredient);

    // ------------------------------------------------------------------
    // 样板与合成校验
    // ------------------------------------------------------------------

    /** 样板仓是否接受所有编码样板。false 时只接受合成样板（crafting pattern）。 */
    public static final ModConfigSpec.BooleanValue PATTERN_STORE_ALL_PATTERNS = BUILDER
            .comment("样板仓是否接受所有编码样板；设为 false 时只接受合成样板（crafting pattern），处理样板会被拒收")
            .define("patternStoreAllPatterns", true);

    /**
     * 严格校验。true（默认）：处理样板必须能在配方表里找到对应配方，
     * 且输入、产物、数量都要对得上；伪造样板会被整单拒绝。
     */
    public static final ModConfigSpec.BooleanValue STRICT_PATTERN_VALIDATION = BUILDER
            .comment("是否启用严格样板校验。true 时会核对配方的输入/产物/数量，伪造样板会被拒绝；false 时完全不校验")
            .define("strictPatternValidation", true);

    /**
     * 自动拦截。true（默认）：网络里有轮椅/核心时，即使没指定 CPU 也会由轮椅核心接管；
     * false：只有玩家在 CPU 列表里明确选中轮椅核心时才会拦截。
     */
    public static final ModConfigSpec.BooleanValue AUTO_INTERCEPT_CRAFTING = BUILDER
            .comment("是否自动拦截合成任务。true：网络里有轮椅或核心就直接接管；false：只在玩家于 CPU 列表中选中轮椅核心时才拦截")
            .define("autoInterceptCrafting", true);

    // ------------------------------------------------------------------
    // 骑乘与提示
    // ------------------------------------------------------------------

    /** 骑乘时是否免疫所有负面效果。 */
    public static final ModConfigSpec.BooleanValue RIDER_IMMUNE_HARMFUL = BUILDER
            .comment("骑上轮椅时是否免疫所有负面药水效果（中毒、凋零、缓慢等），每 tick 自动清除")
            .define("riderImmuneToHarmfulEffects", true);

    /** 没有其它 CPU 可分配时是否提示玩家。 */
    public static final ModConfigSpec.BooleanValue NOTIFY_NO_FALLBACK_CPU = BUILDER
            .comment("当任务需要交给其它 CPU、但网络里没有其它 CPU 时，是否提示玩家「请尝试增加额外的非本模组的CPU」")
            .define("notifyNoFallbackCpu", true);

    /** 是否把每次样板校验的明细写进日志（排查误判用）。 */
    public static final ModConfigSpec.BooleanValue LOG_VALIDATION = BUILDER
            .comment("是否在日志中输出每次样板校验的详细内容（输入清单、匹配到的配方、拒绝原因），用于排查问题")
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

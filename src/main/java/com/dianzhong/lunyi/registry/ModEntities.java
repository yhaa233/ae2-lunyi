package com.dianzhong.lunyi.registry;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.entity.WheelchairEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 实体类型注册表。
 */
public final class ModEntities {
    private ModEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ae2lunyi.MODID);

    /**
     * 轮椅。id 为 {@code ae2_lunyi:wheelchair}。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<WheelchairEntity>> WHEELCHAIR =
            ENTITY_TYPES.register("wheelchair", () -> EntityType.Builder
                    .of(WheelchairEntity::new, MobCategory.MISC)
                    .sized(0.9F, 1.15F)
                    .eyeHeight(0.8F)
                    .clientTrackingRange(10)
                    .build(ae2lunyi.MODID + ":wheelchair"));
}

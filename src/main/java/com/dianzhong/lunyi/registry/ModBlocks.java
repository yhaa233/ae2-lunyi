package com.dianzhong.lunyi.registry;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.block.WheelchairCpuBlock;
import com.dianzhong.lunyi.block.WheelchairCpuBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块注册：轮椅合成核心。
 */
public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ae2lunyi.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ae2lunyi.MODID);

    public static final DeferredHolder<Block, WheelchairCpuBlock> WHEELCHAIR_CPU =
            BLOCKS.register("wheelchair_cpu",
                    () -> new WheelchairCpuBlock(BlockBehaviour.Properties.of()
                            .strength(2.0F, 6.0F)));

    public static final DeferredItem<BlockItem> WHEELCHAIR_CPU_ITEM =
            ae2lunyi.ITEMS.registerSimpleBlockItem("wheelchair_cpu", WHEELCHAIR_CPU);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WheelchairCpuBlockEntity>> WHEELCHAIR_CPU_BE =
            BLOCK_ENTITIES.register("wheelchair_cpu",
                    () -> BlockEntityType.Builder
                            .of(ModBlocks::createBlockEntity, WHEELCHAIR_CPU.get())
                            .build(null));

    /** 单独抽成方法，避免在字段初始化里自引用 {@code WHEELCHAIR_CPU_BE}。 */
    private static WheelchairCpuBlockEntity createBlockEntity(net.minecraft.core.BlockPos pos,
                                                               net.minecraft.world.level.block.state.BlockState state) {
        return new WheelchairCpuBlockEntity(WHEELCHAIR_CPU_BE.get(), pos, state);
    }
}

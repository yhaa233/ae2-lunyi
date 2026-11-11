package com.dianzhong.lunyi.block;

import appeng.block.crafting.AbstractCraftingUnitBlock;
import appeng.block.crafting.ICraftingUnitType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 轮椅合成核心方块。
 * <p>
 * 必须继承 AE2 的 {@link AbstractCraftingUnitBlock}：{@code CraftingBlockEntity#onReady()} 会把
 * 自己的方块强转成这个类型，用普通 Block 会在放置后的第一 tick 抛 ClassCastException。
 * 外观直接复用 AE2 合成单元的模型。
 */
public class WheelchairCpuBlock extends AbstractCraftingUnitBlock<WheelchairCpuBlockEntity> {
    /** 自定义的单元类型：容量和并行都拉满。 */
    public static final ICraftingUnitType WHEELCHAIR_TYPE = new ICraftingUnitType() {
        @Override
        public long getStorageBytes() {
            // 合成容量拉到无限（取一半避免 AE2 内部相加溢出）。
            // 产物不会因此被这个缓冲吞掉：写入时 insertIntoNetwork 会临时清空 CPU 集合，
            // 让产物落进普通网络存储。
            return Long.MAX_VALUE;
        }

        @Override
        public int getAcceleratorThreads() {
            // AE2 校验：单个方块最多 16 个协同处理器，超过会抛异常
            return 16;
        }

        @Override
        public Item getItemFromType() {
            return com.dianzhong.lunyi.registry.ModBlocks.WHEELCHAIR_CPU_ITEM.get();
        }
    };

    public WheelchairCpuBlock(Properties properties) {
        super(properties, WHEELCHAIR_TYPE);
        // AbstractCraftingUnitBlock 走的是 AEBaseEntityBlock(Properties) 那个构造器，
        // 它不会设置 blockEntityClass，导致 onReady 里 NPE；这里补上。
        this.setBlockEntity(WheelchairCpuBlockEntity.class, null, null, null);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state,
                                        net.minecraft.world.entity.player.Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WheelchairCpuBlockEntity core) {
            var snapshot = new java.util.ArrayList<>(core.getPatterns());
            com.dianzhong.lunyi.ae2lunyi.LOGGER.info("Wheelchair core broken: dropped {} patterns", snapshot.size());
            for (ItemStack stack : snapshot) {
                if (!stack.isEmpty()) {
                    var drop = new net.minecraft.world.entity.item.ItemEntity(level,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, stack.copy());
                    drop.setDefaultPickUpDelay();
                    level.addFreshEntity(drop);
                }
            }
            core.getPatterns().clear();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 把玩家背包里所有合格的编码样板搬进核心样板仓，并在动作栏提示数量。
     * Shift+右键（服务端判定）与 Alt+右键（客户端发包）都会走这里。
     *
     * @return 实际搬运的数量
     */
    public static int uploadAllPatterns(Player player, WheelchairCpuBlockEntity core) {
        int moved = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !com.dianzhong.lunyi.compat.ae2.Ae2Compat.isEncodedPattern(stack)) {
                continue;
            }
            if ((!com.dianzhong.lunyi.Config.PATTERN_STORE_ALL_PATTERNS.get()
                    || !com.dianzhong.lunyi.Config.STRICT_PATTERN_VALIDATION.get())
                    && !com.dianzhong.lunyi.compat.ae2.Ae2Compat.isCraftingPattern(stack)) {
                continue;
            }
            int count = stack.getCount();
            for (int k = 0; k < count; k++) {
                core.getPatterns().add(stack.copyWithCount(1));
                moved++;
            }
            if (!player.getAbilities().instabuild) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (moved > 0) {
            core.patternsChanged();
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.ae2_lunyi.uploaded", moved), true);
        return moved;
    }

    @Override
    public WheelchairCpuBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WheelchairCpuBlockEntity(com.dianzhong.lunyi.registry.ModBlocks.WHEELCHAIR_CPU_BE.get(), pos, state);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        // 放置后安排一次重试：那一刻相邻的线缆可能还没加载好
        if (!level.isClientSide) {
            level.scheduleTick(pos, this, 20);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   net.minecraft.world.level.block.Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof WheelchairCpuBlockEntity core) {
            core.refreshNode();
        }
    }

    @Override
    protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos,
                        net.minecraft.util.RandomSource random) {
        super.tick(state, level, pos, random);
        if (level.getBlockEntity(pos) instanceof WheelchairCpuBlockEntity core) {
            core.refreshNode();
            core.ensureFormed();
            // 每 2 秒重新注入一次：AE2 在 CPU 增删时会清空并重建列表，
            // 我们靠反射加进去的条目会被丢掉。
            level.scheduleTick(pos, this, 20);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WheelchairCpuBlockEntity be) {
            // Shift + 右键：把背包里的编码样板一键塞进这个核心的样板仓
            if (player.isSecondaryUseActive()) {
                int moved = 0;
                var inv = player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()
                            || !com.dianzhong.lunyi.compat.ae2.Ae2Compat.isEncodedPattern(stack)) {
                        continue;
                    }
                    // 严格模式或关闭“接受全部样板”时，只搬合成样板
                    if ((!com.dianzhong.lunyi.Config.PATTERN_STORE_ALL_PATTERNS.get()
                            || !com.dianzhong.lunyi.Config.STRICT_PATTERN_VALIDATION.get())
                            && !com.dianzhong.lunyi.compat.ae2.Ae2Compat.isCraftingPattern(stack)) {
                        continue;
                    }
                    int count = stack.getCount();
                    for (int k = 0; k < count; k++) {
                        be.getPatterns().add(stack.copyWithCount(1));
                        moved++;
                    }
                    if (!player.getAbilities().instabuild) {
                        inv.setItem(i, ItemStack.EMPTY);
                    }
                }
                if (moved > 0) {
                    be.patternsChanged();
                }
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.ae2_lunyi.uploaded", moved), true);
                return InteractionResult.sidedSuccess(false);
            }
            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new com.dianzhong.lunyi.compat.ae2.PatternStoreMenu(id, inv, be.getPatterns(), 0, be::patternsChanged),
                    net.minecraft.network.chat.Component.translatable("block.ae2_lunyi.pattern_store")),
                    buf -> {
                        buf.writeBoolean(true);
                        buf.writeBlockPos(pos);
                        buf.writeVarInt(0);
                    });
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}

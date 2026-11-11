package com.dianzhong.lunyi.block;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.blockentity.crafting.CraftingBlockEntity;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 轮椅合成核心的方块实体。
 * <p>
 * 它同时是：AE2 的合成单元（能组成 CPU 集群）、样板提供者（规划阶段可见）、
 * 以及一个带样板仓的存储。产物交付走 {@code WheelchairCpu} 里的绕行逻辑。
 */
public class WheelchairCpuBlockEntity extends CraftingBlockEntity
        implements appeng.helpers.patternprovider.PatternContainer {
    /** 这个核心自己的样板仓，容量不设上限。 */
    private final List<ItemStack> patterns = new ArrayList<>();

    /** 世界上所有已加载的核心，供轮椅样板变化时快速刷新。 */
    private static final List<WheelchairCpuBlockEntity> ACTIVE =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 我们自己的 CPU 集群，供 CraftingSubmitMixin 判断"是否选中了轮椅核心"。 */
    private static final Set<Object> OUR_CLUSTERS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private boolean reRegistered;
    private int patternVersion;

    public WheelchairCpuBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        // 注册成样板提供者，让 AE2 的规划阶段能看见这些样板（否则连下单都点不了）。
        // 真正的合成由 WheelchairCpu + CraftingSubmitMixin 接管。
        this.getMainNode().addService(appeng.api.networking.crafting.ICraftingProvider.class,
                new com.dianzhong.lunyi.compat.ae2.CoreCraftingProvider(this));
    }

    public List<ItemStack> getPatterns() {
        return this.patterns;
    }

    public int getPatternVersion() {
        return this.patternVersion;
    }

    public static List<WheelchairCpuBlockEntity> activeCores() {
        return ACTIVE;
    }

    public static Set<Object> ourClusters() {
        return OUR_CLUSTERS;
    }

    public static boolean isOurCluster(Object cluster) {
        return cluster != null && OUR_CLUSTERS.contains(cluster);
    }

    /** 样板被改动：存盘 + 让 AE2 重新扫描。 */
    public void patternsChanged() {
        this.patternVersion++;
        this.setChanged();
        try {
            appeng.api.networking.crafting.ICraftingProvider.requestUpdate(this.getMainNode());
        } catch (Throwable t) {
            ae2lunyi.LOGGER.error("Wheelchair core: requestUpdate failed", t);
        }
    }

    /** 重新暴露所有面，并主动连一个邻居（方块实体不会像线缆那样被自动拉进网络）。 */
    public void refreshNode() {
        this.getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));

        IGridNode raw = this.getMainNode().getNode();
        if (raw == null || this.level == null) {
            return;
        }
        if (!raw.getConnections().isEmpty()) {
            return;
        }
        for (BlockPos p : BlockPos.betweenClosed(this.getBlockPos().offset(-1, -1, -1),
                this.getBlockPos().offset(1, 1, 1))) {
            var host = GridHelper.getNodeHost(this.level, p.immutable());
            if (host == null) {
                continue;
            }
            for (Direction dir : Direction.values()) {
                IGridNode other = host.getGridNode(dir);
                if (other != null && other.getGrid() != null) {
                    GridHelper.createConnection(raw, other);
                    return;
                }
            }
        }
    }

    /** 节点激活后再算一次集群，并把自己登记进合成服务（AE2 只在 onReady 算一次）。 */
    public void ensureFormed() {
        IGridNode node = this.getMainNode().getNode();
        if (node == null || !node.isActive()) {
            return;
        }

        if (!this.reRegistered) {
            this.reRegistered = true;
            this.updateMultiBlock(this.getBlockPos());
        }

        Object cluster = this.getCluster();
        if (cluster == null) {
            return;
        }
        OUR_CLUSTERS.add(cluster);

        try {
            var grid = node.getGrid();
            if (grid == null) {
                return;
            }
            var service = grid.getCraftingService();
            var field = service.getClass().getDeclaredField("craftingCPUClusters");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var set = (Set<Object>) field.get(service);
            if (set.add(cluster)) {
                ae2lunyi.LOGGER.info("Wheelchair core: injected cluster into crafting service, clusters={}",
                        set.size());
            }
        } catch (Throwable t) {
            ae2lunyi.LOGGER.error("Wheelchair core: cluster injection failed", t);
        }
    }

    /** 打印节点状态，用于确认 online/active 不是时机问题。 */
    public void logNodeState(String when) {
        IGridNode node = this.getMainNode().getNode();
        ae2lunyi.LOGGER.info(
                "Wheelchair core node state [{}]: grid={} online={} connections={} active={} formed={}",
                when,
                node == null ? null : node.getGrid(),
                this.getMainNode().isOnline(),
                node == null ? -1 : node.getConnections().size(),
                node != null && node.isActive(),
                this.isFormed());
    }

    @Override
    public void onReady() {
        super.onReady();
        if (!ACTIVE.contains(this)) {
            ACTIVE.add(this);
        }
        this.getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
        if (this.level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            serverLevel.scheduleTick(this.getBlockPos(), this.getBlockState().getBlock(), 20);
        }
        this.logNodeState("onReady");
    }

    @Override
    public void setRemoved() {
        ACTIVE.remove(this);
        OUR_CLUSTERS.remove(this.getCluster());
        super.setRemoved();
    }

    @Override
    public long getStorageBytes() {
        return Long.MAX_VALUE;
    }

    /** 不做多方块，所有面都可连接。 */
    @Override
    public Set<Direction> getGridConnectableSides(appeng.api.orientation.BlockOrientation orientation) {
        return EnumSet.allOf(Direction.class);
    }

    /** 不消耗频道：默认节点带 REQUIRE_CHANNEL，频道满了会永远离线。 */
    @Override
    protected IManagedGridNode createMainNode() {
        return super.createMainNode().setFlags();
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        this.patterns.clear();
        ListTag list = data.getList("patterns", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(registries, list.getCompound(i));
            if (!stack.isEmpty()) {
                this.patterns.add(stack);
            }
        }
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        ListTag list = new ListTag();
        for (ItemStack stack : this.patterns) {
            if (stack.isEmpty()) {
                continue;
            }
            try {
                list.add(stack.save(registries));
            } catch (Throwable t) {
                ae2lunyi.LOGGER.warn("Skipping un-saveable pattern", t);
            }
        }
        data.put("patterns", list);
    }
    // ------------------------------------------------------------------
    // 样板管理终端（Pattern Access Terminal）接入
    // ------------------------------------------------------------------

    private appeng.api.inventories.InternalInventory terminalInventory;

    @Override
    public appeng.api.networking.IGrid getGrid() {
        var node = this.getMainNode().getNode();
        return node == null ? null : node.getGrid();
    }

    @Override
    public appeng.api.inventories.InternalInventory getTerminalPatternInventory() {
        if (this.terminalInventory == null) {
            this.terminalInventory = new com.dianzhong.lunyi.compat.ae2.PatternListInventory(this.patterns);
        }
        return this.terminalInventory;
    }

    @Override
    public appeng.api.implementations.blockentities.PatternContainerGroup getTerminalGroup() {
        return new appeng.api.implementations.blockentities.PatternContainerGroup(
                appeng.api.stacks.AEItemKey.of(
                        com.dianzhong.lunyi.registry.ModBlocks.WHEELCHAIR_CPU_ITEM.get().getDefaultInstance()),
                net.minecraft.network.chat.Component.translatable("block.ae2_lunyi.wheelchair_cpu"),
                java.util.List.of());
    }
}

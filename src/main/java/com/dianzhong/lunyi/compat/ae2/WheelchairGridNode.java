package com.dianzhong.lunyi.compat.ae2;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.entity.WheelchairEntity;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 轮椅自己的 AE2 网格节点。
 * <p>
 * AE2 的网格按方块坐标组织，而 {@code ManagedGridNode} 一旦 {@code create} 就不能改坐标、
 * 也不能重复 create。所以这里不"跟着轮椅走"，而是改成<b>停稳才建节点</b>：
 * 连续 {@link #IDLE_TICKS} tick 没有跨方块移动才建，一旦移动就拆掉。
 * 这样同一时刻网络里最多只有一个属于这辆轮椅的节点，不会反复增删。
 */
final class WheelchairGridNode {
    /** 静止多久才建节点。 */
    private static final int IDLE_TICKS = 20;

    private static final Map<WheelchairEntity, State> STATES = new WeakHashMap<>();

    private static final IGridNodeListener<WheelchairProvider> LISTENER = new IGridNodeListener<>() {
        @Override
        public void onSaveChanges(WheelchairProvider nodeOwner, IGridNode node) {
            // 轮椅本身是被保存的对象，节点不需要额外写 NBT
        }
    };

    private WheelchairGridNode() {}

    static void tick(WheelchairEntity wheelchair) {
        if (!(wheelchair.level() instanceof ServerLevel level)) {
            return;
        }

        BlockPos pos = wheelchair.blockPosition();
        State state = STATES.get(wheelchair);
        if (state == null) {
            state = new State(pos);
            STATES.put(wheelchair, state);
        }

        // 节点建好之后就不再跟着轮椅搬了：AE2 的 ManagedGridNode 一旦 create 就绑定方块坐标，
        // 销毁重建会让正在跑的合成任务直接断掉（表现就是"一动就卡住"）。
        // 所以这里只记录位置，不重建——轮椅相当于一个挂在原地的"无线设备"。
        if (!state.lastPos.equals(pos)) {
            state.lastPos = pos.immutable();
            state.idleTicks = 0;
        }

        state.idleTicks++;
        if (state.node == null && state.idleTicks >= IDLE_TICKS) {
            state.node = createNode(wheelchair, level, pos);
            ae2lunyi.LOGGER.info("Wheelchair grid node created at {} (grid={})", pos, gridOf(state.node));
        }
    }

    static void remove(WheelchairEntity wheelchair) {
        State state = STATES.remove(wheelchair);
        if (state != null && state.node != null) {
            state.node.destroy();
            ae2lunyi.LOGGER.info("Wheelchair grid node destroyed");
        }
    }

    private static IManagedGridNode createNode(WheelchairEntity wheelchair, ServerLevel level, BlockPos pos) {
        WheelchairProvider provider = new WheelchairProvider(wheelchair);
        IManagedGridNode node = GridHelper.createManagedNode(provider, LISTENER);
        provider.setNode(node);
        node.setInWorldNode(true)
                .setExposedOnSides(EnumSet.allOf(Direction.class))
                .setIdlePowerUsage(0.0)
                .setTagName("wheelchair");
        // 注意：这里故意不再 addService(ICraftingProvider.class, ...)。
        // AE2 的合成 CPU 派发后要靠 waitingFor 记账收尾，我们直接写存储的模型跟它对不上，
        // 结果是任务永远卡在进行中。轮椅现在走自己的递归分配（WheelchairTermMenu#craftOne）。
        node.create(level, pos);

        // 实体所在的位置是空气，线缆不会主动连过来，所以自己找邻居连
        IGridNode raw = node.getNode();
        if (raw != null && raw.getConnections().isEmpty()) {
            IGridNode neighbour = findNeighbour(level, pos);
            if (neighbour == null) {
                ae2lunyi.LOGGER.info("Wheelchair grid node: no neighbouring node found around {}", pos);
            } else {
                GridHelper.createConnection(raw, neighbour);
                IGridNode after = node.getNode();
                ae2lunyi.LOGGER.info("Wheelchair grid node connected to a neighbour (grid={})",
                        after == null ? null : after.getGrid());
            }
        }

        // 连接建立之后再通知 AE2 来读样板：在连接之前刷新的是那个孤立网格，主网络收不到
        try {
            appeng.api.networking.crafting.ICraftingProvider.requestUpdate(node);
            IGridNode after = node.getNode();
            ae2lunyi.LOGGER.info("Wheelchair crafting provider registered on grid={}",
                    after == null || after.getGrid() == null ? null : after.getGrid());
        } catch (Throwable t) {
            ae2lunyi.LOGGER.error("Wheelchair: requestUpdate failed", t);
        }
        return node;
    }

    /** 周围 3×3×3 里找任意一台有网格的方块。 */
    @Nullable
    private static IGridNode findNeighbour(ServerLevel level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            BlockPos immutable = p.immutable();
            var host = GridHelper.getNodeHost(level, immutable);
            if (host == null) {
                continue;
            }
            for (Direction dir : Direction.values()) {
                IGridNode node = host.getGridNode(dir);
                if (node != null && node.getGrid() != null) {
                    return node;
                }
            }
        }
        return null;
    }

    private static Object gridOf(IManagedGridNode node) {
        IGridNode raw = node.getNode();
        return raw == null ? null : raw.getGrid();
    }

    private static final class State {
        private BlockPos lastPos;
        private int idleTicks;
        @Nullable
        private IManagedGridNode node;

        private State(BlockPos pos) {
            this.lastPos = pos.immutable();
        }
    }
}

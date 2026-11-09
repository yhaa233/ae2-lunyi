package com.dianzhong.lunyi.compat.ae2;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.entity.WheelchairEntity;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 轮椅被 AE2 当作"合成设备"的那一层。
 * <p>
 * AE2 的 {@code CraftingCpuLogic} 会先自己把材料从 CPU 库存里扣好，再把 {@code KeyCounter[]}
 * 交给 {@code pushPattern}；<b>只有返回 true，CPU 才会把产物记进 {@code waitingFor}</b>，
 * 之后从网络里提取这些产物来完成合成。
 * <p>
 * 所以这里绝对不能再去扣输入（那是重复扣），只负责把产物塞进网络并返回 true。
 */
public final class WheelchairProvider implements ICraftingProvider, IActionHost {
    private final WheelchairEntity wheelchair;
    private IManagedGridNode node;
    private final IActionSource source = IActionSource.empty();

    private List<IPatternDetails> cache = List.of();
    private int cacheSignature = -1;

    public WheelchairProvider(WheelchairEntity wheelchair) {
        this.wheelchair = wheelchair;
    }

    void setNode(IManagedGridNode node) {
        this.node = node;
    }

    /** 把样板仓里的样板解码成 AE2 能用的形式（按列表长度缓存，变了才重解码）。 */
    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        List<ItemStack> patterns = this.wheelchair.getPatterns();
        if (patterns.size() == this.cacheSignature) {
            return this.cache;
        }
        List<IPatternDetails> decoded = new ArrayList<>();
        for (ItemStack stack : patterns) {
            if (stack.isEmpty()) {
                continue;
            }
            IPatternDetails details = PatternDetailsHelper.decodePattern(stack, this.wheelchair.level());
            if (details != null) {
                decoded.add(details);
            }
        }
        this.cache = List.copyOf(decoded);
        this.cacheSignature = patterns.size();
        ae2lunyi.LOGGER.info("Wheelchair provider: {} patterns decoded out of {}", this.cache.size(), patterns.size());
        return this.cache;
    }

    /**
     * 材料已经由 CPU 扣好并传进来了，这里只把产物送进网络。
     *
     * @return true 表示接受；CPU 会把产物记进 waitingFor，然后从网络里取走它们
     */
    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) {
        IGridNode raw = this.node == null ? null : this.node.getNode();
        if (raw == null || raw.getGrid() == null) {
            return false;
        }
        IGrid grid = raw.getGrid();
        try {
            var storage = grid.getStorageService().getInventory();
            for (GenericStack out : details.getOutputs()) {
                long inserted = storage.insert(out.what(), out.amount(), Actionable.MODULATE, this.source);
                long leftover = out.amount() - inserted;
                if (leftover > 0 && out.what() instanceof AEItemKey itemKey) {
                    // 网络塞不下就丢在轮椅脚下，别凭空消失
                    var drop = new ItemEntity(this.wheelchair.level(),
                            this.wheelchair.getX(), this.wheelchair.getY() + 0.5, this.wheelchair.getZ(),
                            itemKey.toStack((int) leftover));
                    this.wheelchair.level().addFreshEntity(drop);
                }
            }
            return true;
        } catch (Throwable t) {
            ae2lunyi.LOGGER.error("Wheelchair pushPattern failed", t);
            return false;
        }
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    public IGridNode getActionableNode() {
        return this.node == null ? null : this.node.getNode();
    }
}

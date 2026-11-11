package com.dianzhong.lunyi.compat.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 待交付队列。
 * <p>
 * 其它 CPU 推送样板时，产物先记在这里，等这批合成结束（连续若干 tick 没有新的推送）再一次性
 * 写入网络；否则每做一步就立刻出现在终端里，玩家会误以为任务已经完成而反复取消。
 * <p>
 * 即使任务中途被取消，这里的东西也不会丢——它仍然会在超时后交付。
 */
public final class PendingDeliveries {
    /** 连续多少 tick 没有新的推送，就认为这批合成结束了。 */
    private static final int IDLE_TICKS = 20;

    /** 单个物品最多在队列里滞留这么多 tick，超过就强制交付，避免持续推送导致永不交付。 */
    private static final int MAX_AGE_TICKS = 40;

    private record Entry(IGrid grid, AEKey key, long amount, long lastTick, long createdAt) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private PendingDeliveries() {
    }

    public static synchronized void add(IGrid grid, AEKey key, long amount, long tick) {
        if (grid == null || key == null || amount <= 0) {
            return;
        }
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry entry = ENTRIES.get(i);
            if (entry.grid() == grid && entry.key().equals(key)) {
                ENTRIES.set(i, new Entry(grid, key, entry.amount() + amount, tick, entry.createdAt()));
                return;
            }
        }
        ENTRIES.add(new Entry(grid, key, amount, tick, tick));
    }

    /** 每 tick 调用一次：把这批已经结束的产物写进网络。 */
    public static synchronized void flush(long tick, java.util.function.Consumer<String> log) {
        Iterator<Entry> iterator = ENTRIES.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            boolean idle = tick - entry.lastTick() >= IDLE_TICKS;
            boolean tooOld = tick - entry.createdAt() >= MAX_AGE_TICKS;
            if (!idle && !tooOld) {
                continue;
            }
            iterator.remove();
            try {
                var storage = entry.grid().getStorageService().getInventory();
                long inserted = storage.insert(entry.key(), entry.amount(), Actionable.MODULATE,
                        IActionSource.empty());
                if (inserted < entry.amount()) {
                    log.accept("pending delivery refused " + (entry.amount() - inserted) + " of " + entry.key());
                }
            } catch (Throwable t) {
                log.accept("pending delivery failed for " + entry.key() + ": " + t);
            }
        }
    }

    /** 服务器关闭或切换存档时清空，避免跨存档残留。 */
    public static synchronized void clear() {
        ENTRIES.clear();
    }
}

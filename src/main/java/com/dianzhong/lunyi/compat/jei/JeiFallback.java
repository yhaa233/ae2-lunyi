package com.dianzhong.lunyi.compat.jei;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端侧：JEI 兜底查询（只判断“有没有配方”）。
 * <p>
 * 仅在服务端索引完全查不到某个物品时使用，结果带缓存与冷却。
 */
public final class JeiFallback {
    private static final int CACHE_TICKS = 20 * 60 * 10;
    private static final int QUERY_COOLDOWN_TICKS = 20 * 10;

    private record Entry(boolean found, long tick) {
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    /** 还没得到答案的查询（登录时重发一次）。 */
    private static final Map<String, String> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_QUERY = new ConcurrentHashMap<>();

    private JeiFallback() {
    }

    private static long now() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0L : server.getTickCount();
    }

    public static boolean isCached(String itemId) {
        Entry entry = CACHE.get(itemId);
        if (entry == null) {
            return false;
        }
        if (now() - entry.tick() > CACHE_TICKS) {
            CACHE.remove(itemId);
            return false;
        }
        return true;
    }

    public static boolean cachedFound(String itemId) {
        Entry entry = CACHE.get(itemId);
        return entry != null && entry.found();
    }

    /** 请所有在线客户端查一次（带冷却）。 */
    /** 登录时把之前没答案的查询全部重发一次。 */
    public static void reaskAll() {
        for (Map.Entry<String, String> entry : PENDING.entrySet()) {
            LAST_QUERY.remove(entry.getKey());
            String[] parts = entry.getValue().split("\\|", 3);
            if (parts.length >= 2) {
                request(entry.getKey(), parts[0], parts[1], parts.length > 2 ? parts[2] : "");
            }
        }
    }

    public static void request(String itemId, String kind, String id, String nbt) {
        long tick = now();
        Long last = LAST_QUERY.get(itemId);
        if (last != null && tick - last < QUERY_COOLDOWN_TICKS) {
            return;
        }
        LAST_QUERY.put(itemId, tick);
        PENDING.put(itemId, kind + "|" + id + "|" + nbt);
        if (ServerLifecycleHooks.getCurrentServer() == null) {
            return;
        }
        try {
            PacketDistributor.sendToAllPlayers(
                    new com.dianzhong.lunyi.network.JeiQueryPayload(itemId, kind, id, nbt));
            ae2lunyi.LOGGER.info("JEI fallback: asking clients about {}", itemId);
        } catch (Throwable t) {
            ae2lunyi.LOGGER.warn("JEI fallback: failed to send query for {}", itemId, t);
        }
    }

    /** 调试用：强制查询一个物品（忽略冷却）。 */
    public static void debugQuery(String itemId, net.minecraft.world.item.ItemStack stack) {
        LAST_QUERY.remove(itemId);
        CACHE.remove(itemId);
        request(itemId, "item", itemId, stack.isEmpty() ? "" : stack.save(
                net.minecraft.core.RegistryAccess.EMPTY).toString());
    }

    public static void accept(String itemId, boolean found) {
        CACHE.put(itemId, new Entry(found, now()));
        PENDING.remove(itemId);
        ae2lunyi.LOGGER.info("JEI fallback: {} -> found={}", itemId, found);
    }

    public static void clear() {
        PENDING.clear();
        CACHE.clear();
        LAST_QUERY.clear();
    }
}

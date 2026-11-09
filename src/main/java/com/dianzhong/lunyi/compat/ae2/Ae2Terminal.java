package com.dianzhong.lunyi.compat.ae2;

import com.dianzhong.lunyi.entity.WheelchairEntity;
import com.dianzhong.lunyi.registry.ModItems;

import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.ViewItems;
import appeng.api.features.Locatables;
import appeng.api.ids.AEComponents;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.storage.ILinkStatus;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.api.storage.SupplierStorage;
import appeng.api.util.IConfigManager;
import appeng.blockentity.qnb.QuantumBridgeBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.me.storage.NullInventory;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuHostLocator;
import appeng.menu.locator.MenuLocators;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 轮椅和 AE2 的桥。
 * <p>
 * 终端宿主挂在 {@link ItemMenuHost} 上（AE2 的菜单只认 BlockEntity / IPart / ItemMenuHost），
 * 网络通过量子桥（按奇点频率）或者身边 3×3×3 内的节点解析。
 * 样板仓是一个不做上限的列表，直接存在实体上。
 */
final class Ae2Terminal {
    /** 绑定槽的 id。 */
    static final ResourceLocation KEY_SLOT_ID = ResourceLocation.fromNamespaceAndPath("ae2_lunyi", "quantum_key");

    /** 和 {@code CraftingTerminalPart.INV_CRAFTING} 同一个值，这里不去引用 part 类。 */
    private static final ResourceLocation INV_CRAFTING =
            ResourceLocation.fromNamespaceAndPath("ae2", "crafting_terminal_crafting");

    private Ae2Terminal() {}

    // ------------------------------------------------------------------
    // 绑定物品
    // ------------------------------------------------------------------

    static boolean isEntangledSingularity(ItemStack stack) {
        return QuantumBridgeBlockEntity.isValidEntangledSingularity(stack);
    }

    static long getFrequency(ItemStack stack) {
        return stack.getOrDefault(AEComponents.ENTANGLED_SINGULARITY_ID, 0L);
    }

    // ------------------------------------------------------------------
    // 打开终端
    // ------------------------------------------------------------------

    static boolean open(Player player, WheelchairEntity wheelchair) {
        if (player.level().isClientSide()) {
            return false;
        }
        long frequency = wheelchair.getAeLinkFrequency();
        if (frequency != 0L) {
            IActionHost bridge = findBridge(player.level(), frequency);
            com.dianzhong.lunyi.ae2lunyi.LOGGER.info("Wheelchair terminal: freq={}, bridge={}",
                    frequency, bridge == null ? "NOT FOUND" : bridge.getClass().getSimpleName());
        }
        return MenuOpener.open(WheelchairTermMenu.TYPE, player, new Locator(frequency, wheelchair));
    }

    static void refreshNearbyCores(net.minecraft.world.entity.Entity entity) {
        for (var core : com.dianzhong.lunyi.block.WheelchairCpuBlockEntity.activeCores()) {
            if (core.getLevel() != null) {
                core.patternsChanged();
            }
        }
    }
    static void tickGridNode(WheelchairEntity wheelchair) {
        WheelchairGridNode.tick(wheelchair);
    }

    static void removeGridNode(WheelchairEntity wheelchair) {
        WheelchairGridNode.remove(wheelchair);
    }
    static void registerLocator() {
        MenuLocators.register(Locator.class,
                (locator, buf) -> buf.writeLong(locator.frequency),
                buf -> new Locator(buf.readLong(), null));
    }

    /** 注册我们自己的菜单类型（要在注册事件之前挂到 mod 总线上）。 */
    static void registerMenuType(net.neoforged.bus.api.IEventBus modEventBus) {
        var register = net.neoforged.neoforge.registries.DeferredRegister
                .<net.minecraft.world.inventory.MenuType<?>>create(net.minecraft.core.registries.Registries.MENU, "ae2_lunyi");
        register.register("wheelchair_terminal", () -> WheelchairTermMenu.TYPE);
        register.register(modEventBus);
    }

    /**
     * 找同频率的量子桥。AE2 两侧分别以 {@code +f} 和 {@code -f} 注册，所以两个方向都要查。
     */
    @Nullable
    private static IActionHost findBridge(Level level, long frequency) {
        IActionHost bridge = Locatables.quantumNetworkBridges().get(level, frequency);
        if (bridge == null) {
            bridge = Locatables.quantumNetworkBridges().get(level, -frequency);
        }
        return bridge;
    }

    /**
     * 在轮椅周围找一台接在网格上的方块。不能用 {@code getExposedNode}：它要求节点在那一面
     * "对外暴露"，线缆只在真的连上东西的方向算暴露，所以放在线缆旁边反而查不到。
     */
    @Nullable
    private static IGridNode findNearbyNode(Level level, BlockPos origin) {
        for (int radius = 0; radius <= 2; radius++) {
            for (BlockPos pos : BlockPos.betweenClosed(
                    origin.offset(-radius, -1, -radius), origin.offset(radius, 1, radius))) {
                BlockPos immutable = pos.immutable();
                var host = GridHelper.getNodeHost(level, immutable);
                if (host == null) {
                    continue;
                }
                for (Direction side : Direction.values()) {
                    IGridNode node = host.getGridNode(side);
                    if (node != null && node.getGrid() != null) {
                        return node;
                    }
                }
            }
        }
        return null;
    }

    /** 给 {@link ItemMenuHost} 用的虚拟定位器：只返回合法的量子缠绕奇点。 */
    private record VirtualLocator(@Nullable WheelchairEntity owner) implements ItemMenuHostLocator {
        @Override
        public ItemStack locateItem(Player player) {
            if (this.owner != null) {
                ItemStack key = this.owner.getAeKey();
                if (isEntangledSingularity(key)) {
                    return key;
                }
            }
            return new ItemStack(AEItems.QUANTUM_ENTANGLED_SINGULARITY.get());
        }

        @Nullable
        @Override
        public BlockHitResult hitResult() {
            return null;
        }
    }

    /**
     * 菜单定位器。频率会被序列化给客户端；{@code owner} 只在服务端有。
     */
    static final class Locator implements MenuHostLocator {
        private final long frequency;
        @Nullable
        private final WheelchairEntity owner;

        Locator(long frequency, @Nullable WheelchairEntity owner) {
            this.frequency = frequency;
            this.owner = owner;
        }

        @Override
        public <T> T locate(Player player, Class<T> hostInterface) {
            if (player.level().isClientSide() || this.owner == null) {
                Host stub = new Host(player, null, this.frequency, true);
                return hostInterface.isInstance(stub) ? hostInterface.cast(stub) : null;
            }

            IGridNode node = null;
            if (this.frequency != 0L) {
                IActionHost bridge = findBridge(player.level(), this.frequency);
                node = bridge != null ? bridge.getActionableNode() : null;
            }
            if (node == null) {
                node = findNearbyNode(player.level(), this.owner.blockPosition());
            }

            Host host = new Host(player, this.owner, this.frequency, false);
            host.setResolvedNode(node);
            return hostInterface.isInstance(host) ? hostInterface.cast(host) : null;
        }
    }

    /**
     * 终端宿主。合成九宫格和绑定槽都是宿主自己的库存。
     */
    static final class Host extends ItemMenuHost<Item>
            implements ITerminalHost, IActionHost, ISegmentedInventory {
        @Nullable
        private final WheelchairEntity owner;
        private final boolean clientStub;
        private final IConfigManager configManager;
        private final InternalInventory craftingGrid = new AppEngInternalInventory(9);
        private final AppEngInternalInventory keySlot;
        @Nullable
        private IGridNode node;

        /** 把保存下来的枚举名解析回枚举，失败就用默认值。 */
        private static <T extends Enum<T>> T parseEnum(Class<T> type, String name, T fallback) {
            if (name == null || name.isEmpty()) {
                return fallback;
            }
            try {
                return Enum.valueOf(type, name);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }

        Host(Player player, @Nullable WheelchairEntity owner, long frequency, boolean clientStub) {
            super(AEItems.QUANTUM_ENTANGLED_SINGULARITY.get(), player, new VirtualLocator(owner));
            this.owner = owner;
            this.clientStub = clientStub;
            this.keySlot = new AppEngInternalInventory(new InternalInventoryHost() {
                @Override
                public void saveChangedInventory(AppEngInternalInventory inv) {
                    Host.this.persistKey();
                }

                @Override
                public void onChangeInventory(AppEngInternalInventory inv, int slot) {
                    Host.this.persistKey();
                }

                @Override
                public boolean isClientSide() {
                    return Host.this.owner == null;
                }
            }, 1);
            this.keySlot.setFilter(new appeng.util.inv.filter.IAEItemFilter() {
                @Override
                public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
                    return isEntangledSingularity(stack);
                }
            });
            if (owner != null && isEntangledSingularity(owner.getAeKey())) {
                this.keySlot.setItemDirect(0, owner.getAeKey());
            }
            // 默认值取自轮椅身上保存的设置，改动时写回轮椅，这样关掉界面再打开不会重置。
            SortOrder savedSortBy = parseEnum(SortOrder.class,
                    owner == null ? null : owner.getAeSortBy(), SortOrder.NAME);
            SortDir savedSortDir = parseEnum(SortDir.class,
                    owner == null ? null : owner.getAeSortDir(), SortDir.ASCENDING);
            final IConfigManager[] holder = new IConfigManager[1];
            holder[0] = IConfigManager.builder(() -> {
                        var manager = holder[0];
                        if (this.owner != null && manager != null) {
                            this.owner.setAeSortBy(manager.getSetting(Settings.SORT_BY).name());
                            this.owner.setAeSortDir(manager.getSetting(Settings.SORT_DIRECTION).name());
                        }
                    })
                    .registerSetting(Settings.SORT_BY, savedSortBy)
                    .registerSetting(Settings.VIEW_MODE, ViewItems.ALL)
                    .registerSetting(Settings.SORT_DIRECTION, savedSortDir)
                    .build();
            this.configManager = holder[0];
        }

        /** 样板仓：实体的无上限列表。 */
        java.util.List<ItemStack> patterns() {
            return this.owner == null ? java.util.List.of() : this.owner.getPatterns();
        }

        void setResolvedNode(@Nullable IGridNode node) {
            this.node = node;
        }

        private void persistKey() {
            if (this.owner != null) {
                this.owner.setAeKey(this.keySlot.getStackInSlot(0));
                this.node = null;
            }
        }

        public WheelchairEntity getOwner() {
            return this.owner;
        }

        /** 3 个容量卡槽的库存，内容存在轮椅实体的 NBT 里。 */
        private AppEngInternalInventory cardSlots;

        public appeng.api.inventories.InternalInventory getCardInventory() {
            if (this.cardSlots == null) {
                this.cardSlots = new AppEngInternalInventory(new InternalInventoryHost() {
                    @Override
                    public void saveChangedInventory(AppEngInternalInventory inv) {
                        Host.this.syncCards(inv);
                    }

                    @Override
                    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
                        Host.this.syncCards(inv);
                    }

                    @Override
                    public boolean isClientSide() {
                        return Host.this.owner == null;
                    }
                }, 3);
                if (this.owner != null) {
                    for (int i = 0; i < 3; i++) {
                        this.cardSlots.setItemDirect(i, this.owner.getAeCards().get(i));
                    }
                }
            }
            return this.cardSlots;
        }

        private void syncCards(AppEngInternalInventory inv) {
            if (this.owner != null) {
                for (int i = 0; i < 3; i++) {
                    this.owner.getAeCards().set(i, inv.getStackInSlot(i));
                }
            }
        }

        /** 懒解析网络：槽里的奇点换了以后要能立刻生效。 */
        @Nullable
        private IGridNode node() {
            if (this.node == null && this.owner != null && !this.clientStub) {
                long frequency = this.owner.getAeLinkFrequency();
                if (frequency != 0L) {
                    IActionHost bridge = findBridge(this.owner.level(), frequency);
                    this.node = bridge == null ? null : bridge.getActionableNode();
                }
                if (this.node == null) {
                    this.node = findNearbyNode(this.owner.level(), this.owner.blockPosition());
                }
            }
            return this.node;
        }

        @Override
        public MEStorage getInventory() {
            return new SupplierStorage(() -> {
                IGridNode resolved = this.node();
                IGrid grid = resolved == null ? null : resolved.getGrid();
                return grid == null ? null : grid.getStorageService().getInventory();
            });
        }

        @Override
        public ILinkStatus getLinkStatus() {
            IGridNode resolved = this.node();
            if (resolved == null || resolved.getGrid() == null || !resolved.isActive()) {
                return ILinkStatus.ofDisconnected(Component.translatable("message.ae2_lunyi.no_network"));
            }
            return ILinkStatus.ofConnected();
        }

        @Override
        public IConfigManager getConfigManager() {
            return this.configManager;
        }

        @Override
        public void returnToMainMenu(Player player, ISubMenu subMenu) {
            MenuOpener.returnTo(WheelchairTermMenu.TYPE, player, subMenu.getLocator());
        }

        @Override
        public ItemStack getMainMenuIcon() {
            return new ItemStack(ModItems.WHEELCHAIR_SPAWN_EGG.get());
        }

        @Nullable
        @Override
        public IGridNode getActionableNode() {
            return this.clientStub ? null : this.node();
        }

        @Override
        public InternalInventory getSubInventory(ResourceLocation id) {
            if (KEY_SLOT_ID.equals(id)) {
                return this.keySlot;
            }
            return INV_CRAFTING.equals(id) ? this.craftingGrid : null;
        }
    }
}

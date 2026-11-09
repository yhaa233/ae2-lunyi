package com.dianzhong.lunyi.compat.ae2;

import com.dianzhong.lunyi.registry.ModMenus;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 样板仓界面：一页 9×6 = 54 格，可以翻页。
 * <p>
 * 关键设计：视图容器<b>只持有当前这一页的 54 格副本</b>，翻页时在服务端做
 * “存档 → 换页 → 重新装载”。不这样做的话，槽位同步用的是页内下标、而存储用的是绝对下标，
 * 两个数据包到达顺序一乱就会写错页，表现为越翻越丢。
 */
public class PatternStoreMenu extends AbstractContainerMenu {
    public static final int COLS = 9;
    public static final int ROWS = 6;
    public static final int PAGE_SIZE = COLS * ROWS;

    private static final int SLOT_X = 8;
    private static final int SLOT_Y = 18;
    private static final int INV_Y = SLOT_Y + ROWS * 18 + 14;

    private final PageView view;
    private final ContainerData data = new SimpleContainerData(2);
    @Nullable
    private final Runnable externalChanged;

    /**
     * 窗口里的样板一有变动：先写回底层列表（否则合成服务读到的还是旧数据），
     * 再通知外部刷新缓存。
     */
    private void viewChanged() {
        this.view.savePage();
        if (this.externalChanged != null) {
            this.externalChanged.run();
        }
        this.data.set(1, this.view.totalCount());
        this.broadcastChanges();
    }

    public PatternStoreMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(id, playerInventory, readSource(playerInventory.player, buf), buf.readVarInt());
    }

    public PatternStoreMenu(int id, Inventory playerInventory, List<ItemStack> list, int page) {
        this(id, playerInventory, list, page, null);
    }

    public PatternStoreMenu(int id, Inventory playerInventory, List<ItemStack> list, int page,
                            @Nullable Runnable onChanged) {
        super(ModMenus.PATTERN_STORE.get(), id);

        this.externalChanged = onChanged;
        this.view = new PageView(list, page, this::viewChanged);
        this.view.loadPage();
        this.data.set(0, Math.max(0, page));
        this.data.set(1, this.view.totalCount());
        this.addDataSlots(this.data);

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                this.addSlot(new Slot(this.view, col + row * COLS, SLOT_X + col * 18, SLOT_Y + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, SLOT_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, SLOT_X + col * 18, INV_Y + 3 * 18 + 4));
        }
    }

    /**
     * 从网络包里读"数据来源"：方块核心或轮椅实体。
     */
    private static List<ItemStack> readSource(Player player, RegistryFriendlyByteBuf buf) {
        boolean fromBlock = buf.readBoolean();
        if (fromBlock) {
            var pos = buf.readBlockPos();
            if (player.level().getBlockEntity(pos) instanceof com.dianzhong.lunyi.block.WheelchairCpuBlockEntity be) {
                return be.getPatterns();
            }
            return new ArrayList<>();
        }
        int entityId = buf.readVarInt();
        if (player.level().getEntity(entityId) instanceof com.dianzhong.lunyi.entity.WheelchairEntity wheelchair) {
            return wheelchair.getPatterns();
        }
        return new ArrayList<>();
    }

    public int getPage() {
        return Math.max(0, this.data.get(0));
    }

    /** 真实样板数量（不含空槽）。 */
    public int getStoredCount() {
        return this.data.get(1);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player.level().isClientSide) {
            return true;
        }

        int page = this.getPage();
        if (id == 0) {
            if (page <= 0) {
                return false;
            }
            this.view.savePage();
            this.data.set(0, page - 1);
        } else if (id == 1) {
            // 当前页放满才允许往后翻（下一页可以先空着，方便继续放）
            if (this.view.totalCount() < (page + 1) * PAGE_SIZE) {
                return false;
            }
            this.view.savePage();
            this.data.set(0, page + 1);
        } else {
            return false;
        }

        this.view.setPage(this.getPage());
        this.view.loadPage();
        this.data.set(1, this.view.totalCount());
        this.broadcastChanges();
        return true;
    }

    @Override
    public void broadcastChanges() {
        this.data.set(1, this.view.totalCount());
        super.broadcastChanges();
    }

    @Override
    public void removed(Player player) {
        if (!player.level().isClientSide) {
            this.view.savePage();
            this.view.compactAll();
        }
        com.dianzhong.lunyi.ae2lunyi.LOGGER.info("Wheelchair pattern store closed: {} patterns",
                this.view.totalCount());
        super.removed(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /**
     * 当前页的 54 格窗口。翻页由菜单控制，容器本身只负责这一页。
     */
    private static final class PageView extends SimpleContainer {
        private final List<ItemStack> list;
        private int page;
        @Nullable
        private final Runnable onChanged;

        PageView(List<ItemStack> list, int page, @Nullable Runnable onChanged) {
            super(PAGE_SIZE);
            this.list = list;
            this.page = Math.max(0, page);
            this.onChanged = onChanged;
        }

        void setPage(int page) {
            this.page = Math.max(0, page);
        }

        /** 把底层列表里这一页的内容装进窗口。 */
        void loadPage() {
            for (int i = 0; i < PAGE_SIZE; i++) {
                int abs = this.page * PAGE_SIZE + i;
                this.setItemSilently(i, abs < this.list.size() ? this.list.get(abs) : ItemStack.EMPTY);
            }
        }

        /** 把窗口写回底层列表。 */
        void savePage() {
            int base = this.page * PAGE_SIZE;
            for (int i = 0; i < PAGE_SIZE; i++) {
                int abs = base + i;
                ItemStack stack = this.getItem(i);
                if (abs < this.list.size()) {
                    this.list.set(abs, stack);
                } else if (!stack.isEmpty()) {
                    while (this.list.size() < abs) {
                        this.list.add(ItemStack.EMPTY);
                    }
                    this.list.add(stack);
                }
            }
        }

        /** 关界面时把整份列表压实，去掉空洞。 */
        void compactAll() {
            this.savePage();
            List<ItemStack> filled = new ArrayList<>();
            for (ItemStack stack : this.list) {
                if (!stack.isEmpty()) {
                    filled.add(stack);
                }
            }
            this.list.clear();
            this.list.addAll(filled);
        }

        int totalCount() {
            int n = 0;
            for (ItemStack stack : this.list) {
                if (!stack.isEmpty()) {
                    n++;
                }
            }
            // 当前页里还没写回的部分也要算上
            for (int i = 0; i < PAGE_SIZE; i++) {
                int abs = this.page * PAGE_SIZE + i;
                boolean inList = abs < this.list.size() && !this.list.get(abs).isEmpty();
                if (!inList && !this.getItem(i).isEmpty()) {
                    n++;
                }
            }
            return n;
        }

        private void setItemSilently(int slot, ItemStack stack) {
            super.setItem(slot, stack);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            super.setItem(slot, stack);
            if (this.onChanged != null) {
                this.onChanged.run();
            }
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            if (!Ae2Compat.isEncodedPattern(stack)) {
                return false;
            }
            // 任一开关为 false 时：只收合成样板
            if (!com.dianzhong.lunyi.Config.PATTERN_STORE_ALL_PATTERNS.get()
                    || !com.dianzhong.lunyi.Config.STRICT_PATTERN_VALIDATION.get()) {
                return Ae2Compat.isCraftingPattern(stack);
            }
            return true;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}

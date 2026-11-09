package com.dianzhong.lunyi.compat.ae2;

import com.dianzhong.lunyi.ae2lunyi;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.ITerminalHost;
import appeng.helpers.ICraftingGridMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.CraftingTermMenu;
import appeng.menu.slot.AppEngSlot;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在 AE2 合成终端的基础上，把"下单"换成轮椅自己的分配：
 * <ul>
 *   <li>不排队、不进任何合成 CPU；</li>
 *   <li>按样板仓里的样板<b>递归展开整棵合成树</b>，算出到底需要多少基础材料；</li>
 *   <li>材料够就一次性从 ME 网络扣掉，直接把最终产物塞回网络。</li>
 * </ul>
 * 因为没有中间产物、没有逐个派发，所以容量/并行/分裂都是"无限"的。
 */
public class WheelchairTermMenu extends CraftingTermMenu {
    public static final MenuType<WheelchairTermMenu> TYPE = appeng.menu.implementations.MenuTypeBuilder
            .<WheelchairTermMenu, ITerminalHost>create(WheelchairTermMenu::new, ITerminalHost.class)
            .buildUnregistered(net.minecraft.resources.ResourceLocation
                    .fromNamespaceAndPath("ae2_lunyi", "wheelchair_terminal"));

    /** 递归展开的最大深度，防样板互相指向。 */
    private static final int MAX_DEPTH = 16;

    private final Slot keySlot;
    private final Ae2Terminal.Host aeHost;

    public WheelchairTermMenu(int id, Inventory playerInventory, ITerminalHost host) {
        super(TYPE, id, playerInventory, host, true);
        this.aeHost = host instanceof Ae2Terminal.Host h ? h : null;
        var segmented = (ISegmentedInventory) host;
        // 终端右侧只有一个槽：缠绕奇点
        this.keySlot = this.addSlot(new AppEngSlot(segmented.getSubInventory(Ae2Terminal.KEY_SLOT_ID), 0),
                SlotSemantics.UPGRADE);
    }

    /** 这个终端所属的轮椅（客户端也拿得到）。 */
    public com.dianzhong.lunyi.entity.WheelchairEntity getOwnerEntity() {
        return this.aeHost == null ? null : this.aeHost.getOwner();
    }

    public Slot getKeySlot() {
        return this.keySlot;
    }

    @Override
    public void startAutoCrafting(List<ICraftingGridMenu.AutoCraftEntry> toCraft) {
        try {
            for (ICraftingGridMenu.AutoCraftEntry entry : toCraft) {
                if (this.craftOne(entry.what())) {
                    return;
                }
            }
        } catch (Throwable t) {
            ae2lunyi.LOGGER.error("Wheelchair instant crafting failed, falling back to AE2", t);
        }

        try {
            if (this.instantCraftFromGrid()) {
                return;
            }
        } catch (Throwable t) {
            ae2lunyi.LOGGER.error("Wheelchair grid crafting failed, falling back to AE2", t);
        }

        super.startAutoCrafting(toCraft);
    }

    /**
     * 用样板仓递归合成 1 个目标物品：把整棵树展开成基础材料，一次扣掉，直接把产物入网。
     */
    private boolean craftOne(AEKey requested) {
        if (requested == null || this.aeHost == null) {
            return false;
        }
        Level level = this.getPlayer().level();

        // 输出 -> 样板
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        for (ItemStack encoded : new ArrayList<>(this.aeHost.patterns())) {
            if (encoded.isEmpty()) {
                continue;
            }
            IPatternDetails details = PatternDetailsHelper.decodePattern(encoded, level);
            if (details == null) {
                continue;
            }
            for (GenericStack out : details.getOutputs()) {
                byOutput.putIfAbsent(out.what(), details);
            }
        }
        if (!byOutput.containsKey(requested)) {
            return false;
        }

        Map<AEKey, Long> baseNeeds = new HashMap<>();
        if (!this.expand(requested, 1L, byOutput, baseNeeds, new HashSet<>(), 0)) {
            return false;
        }

        IActionSource source = this.getActionSource();

        // 材料够不够
        for (Map.Entry<AEKey, Long> need : baseNeeds.entrySet()) {
            long have = this.storage.extract(need.getKey(), need.getValue(), Actionable.SIMULATE, source);
            if (have < need.getValue()) {
                return false;
            }
        }

        // 扣材料
        for (Map.Entry<AEKey, Long> need : baseNeeds.entrySet()) {
            this.storage.extract(need.getKey(), need.getValue(), Actionable.MODULATE, source);
        }

        // 出最终产物
        IPatternDetails pattern = byOutput.get(requested);
        if (pattern != null) {
            for (GenericStack out : pattern.getOutputs()) {
                long inserted = this.storage.insert(out.what(), out.amount(), Actionable.MODULATE, source);
                long leftover = out.amount() - inserted;
                if (leftover > 0 && out.what() instanceof AEItemKey itemKey) {
                    this.getPlayer().getInventory().placeItemBackInInventory(itemKey.toStack((int) leftover));
                }
            }
        }
        ae2lunyi.LOGGER.info("Wheelchair crafted {} from {} base ingredients", requested, baseNeeds.size());
        return true;
    }

    /**
     * 递归展开：能由样板合成的就继续往下拆，拆不动的就是基础材料。
     */
    private boolean expand(AEKey what, long amount, Map<AEKey, IPatternDetails> byOutput,
                           Map<AEKey, Long> baseNeeds, Set<AEKey> path, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        IPatternDetails pattern = byOutput.get(what);
        if (pattern == null) {
            baseNeeds.merge(what, amount, Long::sum);
            return true;
        }
        if (!path.add(what)) {
            // 样板互相指向，直接当成基础材料，避免死循环
            baseNeeds.merge(what, amount, Long::sum);
            return true;
        }
        try {
            for (IPatternDetails.IInput input : pattern.getInputs()) {
                GenericStack[] possible = input.getPossibleInputs();
                if (possible.length == 0) {
                    return false;
                }
                long need = possible[0].amount() * input.getMultiplier() * amount;
                if (!this.expand(possible[0].what(), need, byOutput, baseNeeds, path, depth + 1)) {
                    return false;
                }
            }
        } finally {
            path.remove(what);
        }
        return true;
    }

    /** 合成格里的普通配方（没有样板时用）。 */
    private boolean instantCraftFromGrid() {
        RecipeHolder<CraftingRecipe> holder = this.getCurrentRecipe();
        if (holder == null) {
            return false;
        }

        var matrix = this.getCraftingMatrix();
        List<ItemStack> stacks = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            stacks.add(matrix.getStackInSlot(i));
        }

        ItemStack result = holder.value().assemble(CraftingInput.of(3, 3, stacks),
                this.getPlayer().level().registryAccess());
        if (result.isEmpty()) {
            return false;
        }

        IActionSource source = this.getActionSource();
        AEItemKey resultKey = AEItemKey.of(result);
        if (resultKey == null) {
            return false;
        }
        long accepted = this.storage.insert(resultKey, result.getCount(), Actionable.SIMULATE, source);
        if (accepted < result.getCount()) {
            return false;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = matrix.getStackInSlot(i);
            if (!stack.isEmpty()) {
                stack.shrink(1);
                matrix.setItemDirect(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
        }

        long inserted = this.storage.insert(resultKey, result.getCount(), Actionable.MODULATE, source);
        if (inserted < result.getCount()) {
            ItemStack leftover = result.copyWithCount((int) (result.getCount() - inserted));
            this.getPlayer().getInventory().placeItemBackInInventory(leftover);
        }
        return true;
    }
}

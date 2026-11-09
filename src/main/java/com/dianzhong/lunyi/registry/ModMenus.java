package com.dianzhong.lunyi.registry;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.compat.ae2.PatternStoreMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 菜单注册。样板仓界面是普通（非 AE2）容器，所以独立注册，不依赖 AE2。
 */
public final class ModMenus {
    private ModMenus() {}

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, ae2lunyi.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<PatternStoreMenu>> PATTERN_STORE =
            MENUS.register("pattern_store", () -> IMenuTypeExtension.create(PatternStoreMenu::new));
}

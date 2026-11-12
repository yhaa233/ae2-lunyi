package com.dianzhong.lunyi.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.resources.ResourceLocation;

/**
 * JEI 插件：只用来拿到 {@link IJeiRuntime}，不注册任何分类。
 * <p>
 * 通过它，服务端的“查不到配方”兜底逻辑可以请客户端去 JEI 里查一次。
 */
@JeiPlugin
public final class JeiFallbackPlugin implements IModPlugin {
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("ae2_lunyi", "jei_fallback");

    @Override
    public ResourceLocation getPluginUid() {
        return ID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiRuntimeHolder.set(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiRuntimeHolder.set(null);
    }
}

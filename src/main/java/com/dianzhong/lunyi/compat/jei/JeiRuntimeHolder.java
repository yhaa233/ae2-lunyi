package com.dianzhong.lunyi.compat.jei;

import mezz.jei.api.runtime.IJeiRuntime;

import org.jetbrains.annotations.Nullable;

/**
 * 保存客户端的 JEI 运行时。
 * <p>
 * 由 {@link JeiFallbackPlugin} 在 JEI 准备好之后写入；服务端兜底逻辑只通过封包间接使用它，
 * 因此这个类只在客户端有值。
 */
public final class JeiRuntimeHolder {
    @Nullable
    private static volatile IJeiRuntime runtime;

    private JeiRuntimeHolder() {
    }

    public static void set(@Nullable IJeiRuntime value) {
        runtime = value;
    }

    @Nullable
    public static IJeiRuntime get() {
        return runtime;
    }
}

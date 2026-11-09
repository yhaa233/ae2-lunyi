package com.dianzhong.lunyi.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键。默认 R 键脱离轮椅，玩家可以在"选项 - 控制"里改。
 */
public final class ModKeyMappings {
    private ModKeyMappings() {}

    public static final String CATEGORY = "key.categories.ae2_lunyi";

    public static final KeyMapping DISMOUNT = new KeyMapping(
            "key.ae2_lunyi.dismount",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY);
}

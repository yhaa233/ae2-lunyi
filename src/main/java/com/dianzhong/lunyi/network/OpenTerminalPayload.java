package com.dianzhong.lunyi.network;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 -> 服务端：坐在轮椅上按 E，请求打开 AE2 终端。
 */
public record OpenTerminalPayload() implements CustomPacketPayload {
    public static final OpenTerminalPayload INSTANCE = new OpenTerminalPayload();

    public static final CustomPacketPayload.Type<OpenTerminalPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ae2lunyi.MODID, "open_terminal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTerminalPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

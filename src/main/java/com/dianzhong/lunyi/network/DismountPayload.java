package com.dianzhong.lunyi.network;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 -> 服务端：请求从轮椅上下车。载荷本身没有字段。
 */
public record DismountPayload() implements CustomPacketPayload {
    public static final DismountPayload INSTANCE = new DismountPayload();

    public static final CustomPacketPayload.Type<DismountPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ae2lunyi.MODID, "dismount"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DismountPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

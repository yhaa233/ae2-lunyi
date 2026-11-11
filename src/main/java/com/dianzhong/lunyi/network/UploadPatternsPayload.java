package com.dianzhong.lunyi.network;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端检测到 Alt+右键核心方块时发给服务端，让服务端把背包里的样板全部搬进核心。
 */
public record UploadPatternsPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<UploadPatternsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ae2lunyi.MODID, "upload_patterns"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UploadPatternsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, UploadPatternsPayload::pos,
                    UploadPatternsPayload::new);

    @Override
    public Type<UploadPatternsPayload> type() {
        return TYPE;
    }
}

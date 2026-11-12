package com.dianzhong.lunyi.network;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 客户端 → 服务端：JEI 里是否有产出该物品栈的配方。 */
public record JeiReplyPayload(String key, boolean found) implements CustomPacketPayload {
    public static final Type<JeiReplyPayload> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    ae2lunyi.MODID, "jei_reply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, JeiReplyPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeUtf(payload.key(), 512);
                        buffer.writeBoolean(payload.found());
                    },
                    buffer -> new JeiReplyPayload(buffer.readUtf(512), buffer.readBoolean()));

    @Override
    public Type<JeiReplyPayload> type() {
        return TYPE;
    }
}
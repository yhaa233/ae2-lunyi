package com.dianzhong.lunyi.network;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：请客户端去 JEI 查这个原料的配方。
 * <p>
 * kind 取值：{@code item} / {@code fluid} / {@code chemical}。
 * 物品额外带一段组件 NBT（可为空）。
 */
public record JeiQueryPayload(String key, String kind, String id, String nbt)
        implements CustomPacketPayload {
    public static final Type<JeiQueryPayload> TYPE =
            new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    ae2lunyi.MODID, "jei_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, JeiQueryPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeUtf(payload.key(), 512);
                        buffer.writeUtf(payload.kind(), 16);
                        buffer.writeUtf(payload.id(), 256);
                        buffer.writeUtf(payload.nbt(), 4096);
                    },
                    buffer -> new JeiQueryPayload(
                            buffer.readUtf(512),
                            buffer.readUtf(16),
                            buffer.readUtf(256),
                            buffer.readUtf(4096)));

    @Override
    public Type<JeiQueryPayload> type() {
        return TYPE;
    }
}
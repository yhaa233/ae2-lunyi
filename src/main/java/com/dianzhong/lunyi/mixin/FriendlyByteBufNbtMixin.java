package com.dianzhong.lunyi.mixin;

import com.dianzhong.lunyi.ae2lunyi;

import io.netty.buffer.ByteBufOutputStream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataOutputStream;

/**
 * 网络发包时的 NBT 兜底。
 * <p>
 * 有些物品的 NBT 内部结构已经损坏（典型现象：
 * {@code NullPointerException: ObjectArrayList.get(int) because "this.wrapped" is null}
 * 出现在 {@code CompoundTag.write} 里），一旦它被放进容器同步包，整个连接会因为编码失败被踢掉。
 * <p>
 * 这里把 {@code FriendlyByteBuf#writeNbt} 包一层：序列化失败时改发一个空 compound，
 * 保证包仍然合法、连接不断。物品显示可能异常，但不会掉线。
 */
@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufNbtMixin {

    @Inject(method = "writeNbt(Lnet/minecraft/nbt/Tag;)Lnet/minecraft/network/FriendlyByteBuf;",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ae2lunyi$safeWriteNbt(Tag tag, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        if (!write(self, tag)) {
            ae2lunyi.LOGGER.error("Malformed NBT suppressed while writing a packet");
            write(self, new CompoundTag());
        }
        cir.setReturnValue(self);
    }

    private static boolean write(FriendlyByteBuf buffer, Tag tag) {
        try {
            DataOutputStream out = new DataOutputStream(new ByteBufOutputStream(buffer));
            NbtIo.writeAnyTag(tag, out);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

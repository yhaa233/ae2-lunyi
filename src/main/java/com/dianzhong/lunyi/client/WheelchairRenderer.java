package com.dianzhong.lunyi.client;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.entity.WheelchairEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import com.mojang.math.Axis;

/**
 * 轮椅渲染器。模型共用一张 16x16 的纯白贴图，坐垫/靠背用木色、轮子用深灰，
 * 颜色靠 {@link net.minecraft.client.model.geom.ModelPart#render} 的 color 参数逐部件染色。
 * <p>
 * 注意：1.21 的 {@code EntityRenderDispatcher} 只负责平移到实体位置，<b>不会</b>应用实体朝向，
 * yaw 必须由渲染器自己做（{@code LivingEntityRenderer#setupRotations} 和 {@code BoatRenderer}
 * 都是这么写的）。早期版本漏了这一步，导致轮椅永远朝着同一个方向。
 */
public class WheelchairRenderer extends EntityRenderer<WheelchairEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(ae2lunyi.MODID,
            "textures/entity/wheelchair.png");
    /** 靠背上贴的那张图。 */
    private static final ResourceLocation BACK_TEXTURE = ResourceLocation.fromNamespaceAndPath(ae2lunyi.MODID,
            "textures/entity/wheelchair_back.png");
    /** 木头/坐垫颜色（ARGB）。 */
    private static final int FRAME_COLOR = 0xFFB07A45;
    /** 轮胎颜色（ARGB）。 */
    private static final int WHEEL_COLOR = 0xFF2C2C2E;
    /** 辐条和轮毂的颜色（ARGB）。 */
    private static final int RIM_COLOR = 0xFFA8A8B0;

    private final WheelchairModel model;

    public WheelchairRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new WheelchairModel(context.bakeLayer(WheelchairModel.LAYER_LOCATION));
        this.shadowRadius = 0.6F;
    }

    @Override
    public ResourceLocation getTextureLocation(WheelchairEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(WheelchairEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        poseStack.pushPose();
        // 朝向：entityYaw 是 LevelRenderer 已经按 partialTick 在上一 tick 与这一 tick 之间插值过的值
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.501F, 0.0F);

        this.model.setupAnim(entity, 0.0F, 0.0F, entity.tickCount + partialTick, 0.0F, 0.0F);
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entitySolid(TEXTURE));
        this.model.frame().render(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, FRAME_COLOR);
        this.model.wheels().render(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, WHEEL_COLOR);
        this.model.rims().render(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, RIM_COLOR);
        this.renderBackPanel(poseStack, bufferSource, packedLight);

        poseStack.popPose();
    }

    /**
     * 靠背上的贴图。这里直接画一个四边形而不是塞进模型里，是为了让 UV 正好是整张图（0..1），
     * 不用去猜方块展开的 UV 布局。
     * <p>
     * 坐标：模型空间 y 轴向下、1 像素 = 1/16 格，靠背面在 z = 6 像素处。
     * 因为整块模型被 {@code scale(-1,-1,1)} 翻过，模型 +x 在玩家眼里是左边，
     * 所以 u=0 对应 x=+4。为了让正反两面都可见，用的 no-cull 渲染类型，免得绕序写反就消失。
     */
    private void renderBackPanel(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(BACK_TEXTURE));
        PoseStack.Pose pose = poseStack.last();

        float xLeft = 4.0F / 16.0F;    // 玩家视角的左边
        float xRight = -4.0F / 16.0F;  // 玩家视角的右边
        float yTop = 4.0F / 16.0F;
        float yBottom = 12.0F / 16.0F;
        float z = 6.05F / 16.0F;

        vertex(buffer, pose, packedLight, xLeft, yTop, z, 0.0F, 0.0F);
        vertex(buffer, pose, packedLight, xRight, yTop, z, 1.0F, 0.0F);
        vertex(buffer, pose, packedLight, xRight, yBottom, z, 1.0F, 1.0F);
        vertex(buffer, pose, packedLight, xLeft, yBottom, z, 0.0F, 1.0F);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, int packedLight,
                               float x, float y, float z, float u, float v) {
        buffer.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(packedLight)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}

package com.dianzhong.lunyi.client;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.entity.WheelchairEntity;

import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 轮椅模型。整块贴图是纯白的，颜色由渲染器按部件分别染色，
 * 所以这里所有 cube 的 UV 都无所谓，{@code texOffs(0, 0)} 就够了。
 * <p>
 * 坐标系：y 轴向下，y = 24 像素（1.5 格）是地面，和原版生物模型的约定一致。
 * <p>
 * 分成三个部件，方便渲染器上不同颜色：
 * <ul>
 *   <li>{@code frame} — 木架：坐垫、靠背、扶手、立柱、踏板；</li>
 *   <li>{@code wheels} — 轮胎：两侧八边形大轮 + 前导向小轮 + 后横撑；</li>
 *   <li>{@code rims} — 轮圈里的辐条和轮毂，浅灰色。</li>
 * </ul>
 * 每个轮子是一个独立的子部件（原点在轮心），转动时直接改它的 {@code xRot}。
 */
public class WheelchairModel extends HierarchicalModel<WheelchairEntity> {
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(ae2lunyi.MODID, "wheelchair"), "main");

    /** 大轮半径 7 像素，轮心离地 7 像素，正好接地。 */
    private static final float BIG_WHEEL_RADIUS = 7.0F;
    private static final float BIG_WHEEL_X = 7.2F;
    /** 前导向小轮。 */
    private static final float CASTER_RADIUS = 2.0F;
    private static final float CASTER_X = 4.6F;
    private static final float CASTER_Z = -6.6F;

    /** 0/1 = 左右大轮，2/3 = 左右前小轮。 */
    private static final String[] WHEEL_NAMES = {"left_big", "right_big", "left_caster", "right_caster"};

    private final ModelPart root;
    private final ModelPart frame;
    private final ModelPart wheels;
    private final ModelPart rims;

    private final ModelPart[] tireGroups = new ModelPart[WHEEL_NAMES.length];
    private final ModelPart[] rimGroups = new ModelPart[WHEEL_NAMES.length];

    public WheelchairModel(ModelPart root) {
        this.root = root;
        this.frame = root.getChild("frame");
        this.wheels = root.getChild("wheels");
        this.rims = root.getChild("rims");
        for (int i = 0; i < WHEEL_NAMES.length; i++) {
            this.tireGroups[i] = this.wheels.getChild(WHEEL_NAMES[i]);
            this.rimGroups[i] = this.rims.getChild(WHEEL_NAMES[i]);
        }
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // ---- 木架 ----
        root.addOrReplaceChild("frame", CubeListBuilder.create()
                // 坐垫
                .texOffs(0, 0).addBox(-6.0F, 12.0F, -5.0F, 12.0F, 1.0F, 10.0F)
                // 靠背
                .texOffs(0, 0).addBox(-6.0F, 3.0F, 5.0F, 12.0F, 10.0F, 1.0F)
                // 推手
                .texOffs(0, 0).addBox(-6.0F, 2.0F, 5.0F, 12.0F, 1.0F, 2.0F)
                // 左右扶手
                .texOffs(0, 0).addBox(-6.5F, 11.0F, -3.0F, 1.0F, 1.0F, 8.0F)
                .texOffs(0, 0).addBox(5.5F, 11.0F, -3.0F, 1.0F, 1.0F, 8.0F)
                // 前立柱
                .texOffs(0, 0).addBox(-6.0F, 13.0F, -5.0F, 1.0F, 7.0F, 1.0F)
                .texOffs(0, 0).addBox(5.0F, 13.0F, -5.0F, 1.0F, 7.0F, 1.0F)
                // 踏板
                .texOffs(0, 0).addBox(-5.0F, 19.0F, -8.0F, 10.0F, 1.0F, 3.0F)
                // 前小轮的支架
                .texOffs(0, 0).addBox(-5.1F, 19.0F, -7.1F, 1.0F, 3.0F, 1.0F)
                .texOffs(0, 0).addBox(4.1F, 19.0F, -7.1F, 1.0F, 3.0F, 1.0F)
                , PartPose.ZERO);

        // ---- 轮胎 / 轮辐 两个容器 ----
        PartDefinition wheels = root.addOrReplaceChild("wheels", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition rims = root.addOrReplaceChild("rims", CubeListBuilder.create(), PartPose.ZERO);

        addWheel(wheels, rims, "left_big", -BIG_WHEEL_X, 17.0F, 0.0F, BIG_WHEEL_RADIUS, 1.5F, 8);
        addWheel(wheels, rims, "right_big", BIG_WHEEL_X, 17.0F, 0.0F, BIG_WHEEL_RADIUS, 1.5F, 8);
        addWheel(wheels, rims, "left_caster", -CASTER_X, 24.0F - CASTER_RADIUS, CASTER_Z, CASTER_RADIUS, 1.1F, 8);
        addWheel(wheels, rims, "right_caster", CASTER_X, 24.0F - CASTER_RADIUS, CASTER_Z, CASTER_RADIUS, 1.1F, 8);

        // 后轮之间的横撑
        wheels.addOrReplaceChild("rear_brace",
                CubeListBuilder.create().texOffs(0, 0).addBox(-6.0F, 22.0F, 3.5F, 12.0F, 1.0F, 1.0F),
                PartPose.ZERO);

        return LayerDefinition.create(mesh, 16, 16);
    }

    /**
     * 用 8 段短条拼一个八边形轮圈，再加两根辐条和一个轮毂。
     * 轮胎和轮辐分别挂在两棵树上（渲染颜色不同），但位置一致。
     * 每段都要单独成为子部件，因为只有 {@link PartPose} 才带旋转。
     */
    private static void addWheel(PartDefinition tires, PartDefinition rims, String name,
                                 float x, float cy, float cz, float radius, float thickness, int segments) {
        PartDefinition tire = tires.addOrReplaceChild(name, CubeListBuilder.create(), PartPose.offset(x, cy, cz));
        PartDefinition rim = rims.addOrReplaceChild(name, CubeListBuilder.create(), PartPose.offset(x, cy, cz));

        float chord = 2.0F * radius * Mth.sin((float) (Math.PI / segments)) * 1.2F;
        for (int i = 0; i < segments; i++) {
            float angle = (float) (2.0D * Math.PI * i / segments);
            tire.addOrReplaceChild("segment_" + i,
                    CubeListBuilder.create().texOffs(0, 0)
                            .addBox(-thickness / 2.0F, -chord / 2.0F, -thickness / 2.0F,
                                    thickness, chord, thickness),
                    PartPose.offsetAndRotation(0.0F, radius * Mth.cos(angle), radius * Mth.sin(angle),
                            angle + (float) (Math.PI / 2.0), 0.0F, 0.0F));
        }

        rim.addOrReplaceChild("spoke_y",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-thickness * 0.3F, -radius + 0.75F, -thickness * 0.3F,
                                thickness * 0.6F, 2.0F * radius - 1.5F, thickness * 0.6F),
                PartPose.ZERO);
        rim.addOrReplaceChild("spoke_z",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-thickness * 0.3F, -thickness * 0.3F, -radius + 0.75F,
                                thickness * 0.6F, thickness * 0.6F, 2.0F * radius - 1.5F),
                PartPose.ZERO);
        rim.addOrReplaceChild("hub",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-thickness * 0.55F, -1.0F, -1.0F, thickness * 1.1F, 2.0F, 2.0F),
                PartPose.ZERO);
    }

    @Override
    public ModelPart root() {
        return this.root;
    }

    public ModelPart frame() {
        return this.frame;
    }

    public ModelPart wheels() {
        return this.wheels;
    }

    public ModelPart rims() {
        return this.rims;
    }

    /**
     * 轮子转动：角度由实体按"贴地行走距离 / 轮子半径"累计好，这里只做两 tick 之间的插值。
     * 小轮半径是大轮的 2/7，同样的距离转得更多，所以角度按 7/2 放大。
     */
    @Override
    public void setupAnim(WheelchairEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        float partial = ageInTicks - (float) Math.floor(ageInTicks);
        float bigSpin = Mth.lerp(partial, entity.getWheelSpinO(), entity.getWheelSpin());
        float casterSpin = bigSpin * (BIG_WHEEL_RADIUS / CASTER_RADIUS);

        for (int i = 0; i < WHEEL_NAMES.length; i++) {
            float spin = i < 2 ? bigSpin : casterSpin;
            this.tireGroups[i].xRot = spin;
            this.rimGroups[i].xRot = spin;
        }
    }
}

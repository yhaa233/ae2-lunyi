package com.dianzhong.lunyi.entity;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.compat.ae2.Ae2Compat;
import com.dianzhong.lunyi.registry.ModItems;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 轮椅。玩家右键坐上去之后：
 * <ul>
 *   <li>持续获得 10 秒（200 tick）的抗性提升、生命恢复、抗火、饱和、夜视；</li>
 *   <li>双击空格切换飞行（和创造模式一样）。飞行时空格上升、Shift 下降；</li>
 *   <li>按 R 键脱离（见 {@code ModKeyMappings}）；</li>
 *   <li>Shift + 右键打开 AE2 的合成终端（需要附近有 ME 网络）。</li>
 * </ul>
 * 效果是"边骑边刷新"的：坐着时每 10 tick 重新给一次 200 tick 的效果，
 * 因此下车的瞬间开始算，最多还能再享受 10 秒。
 */
public class WheelchairEntity extends Mob {
    /** 效果持续时间：10 秒。 */
    public static final int EFFECT_DURATION_TICKS = 200;
    /** 夜视单独延长到 1 分钟。 */
    public static final int NIGHT_VISION_DURATION_TICKS = 1200;
    /** 效果等级 255 对应的 amplifier（游戏内显示为 amplifier + 1）。 */
    public static final int EFFECT_AMPLIFIER = 254;
    /** 骑着时的移动速度（每 tick 的基准速度）。 */
    private static final float RIDE_SPEED = 0.30F;
    /** 起跳初速度。 */
    private static final double JUMP_POWER = 0.42D;
    /** 每多少 tick 刷新一次效果。 */
    private static final int EFFECT_REFRESH_INTERVAL = 10;
    /** 双击空格判定窗口，和原版创造模式飞行一致。 */
    private static final int DOUBLE_TAP_WINDOW_TICKS = 7;
    /** 大轮半径（格），模型里是 7 像素。用来把行走距离换算成转角。 */
    private static final double BIG_WHEEL_RADIUS_BLOCKS = 7.0D / 16.0D;

    private static final EntityDataAccessor<Boolean> DATA_FLYING =
            SynchedEntityData.defineId(WheelchairEntity.class, EntityDataSerializers.BOOLEAN);

    /** 服务端用来判断双击空格。 */
    private boolean lastJumping;

    /** UUID of the last rider, used to catch up after a teleport. */
    @Nullable
    private java.util.UUID followRiderId;
    /** Ticks spent without that rider. */
    private int followTicks;
    /** Rider position when they left the seat, used to detect a teleport jump. */
    @Nullable
    private net.minecraft.world.phys.Vec3 followOrigin;
    /** Last known rider position while seated. */
    private net.minecraft.world.phys.Vec3 riderLastPos;
    /** Whether we currently hold a forced chunk ticket. */
    private boolean chunkForced;
    private int forcedChunkX;
    private int forcedChunkZ;
    private int lastJumpTick = -DOUBLE_TAP_WINDOW_TICKS - 1;

    /** 客户端渲染用：大轮累计转角（弧度）和上一 tick 的值，用来做插值。 */
    private float wheelSpin;
    private float wheelSpinO;
    private double lastWalkDist;

    /** 绑定的量子缠绕奇点频率，0 表示没绑定。 */
    private long aeLink;

    /** 绑定的那个奇点本身（放在终端界面的槽位里，玩家可以拿走）。 */
    private ItemStack aeKey = ItemStack.EMPTY;

    /** 终端排序方式 / 升降序（跨界面重开保留）。 */
    /** 读档后需要通知核心重新扫描样板（AE2 会缓存 provider 的样板列表）。 */
    private boolean patternRefreshPending;

    private String aeSortBy = "NAME";
    private String aeSortDir = "ASCENDING";

    public String getAeSortBy() { return this.aeSortBy; }
    public void setAeSortBy(String v) { this.aeSortBy = v; }
    public String getAeSortDir() { return this.aeSortDir; }
    public void setAeSortDir(String v) { this.aeSortDir = v; }

    /** 终端右侧的 3 个卡槽（第 0 格是奇点槽，另外 3 格放容量卡）。 */
    private final net.minecraft.core.NonNullList<ItemStack> aeCards =
            net.minecraft.core.NonNullList.withSize(3, ItemStack.EMPTY);

    /** 样板仓：无上限的样板列表（玩家往里放多少就有多少）。 */
    private final java.util.List<ItemStack> aePatterns = new java.util.ArrayList<>();

    public WheelchairEntity(EntityType<? extends WheelchairEntity> entityType, Level level) {
        super(entityType, level);
        // 别让它在玩家走远以后被刷掉
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
                .add(Attributes.FLYING_SPEED, 0.35D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FLYING, false);
    }

    public boolean isFlying() {
        return this.entityData.get(DATA_FLYING);
    }

    public void setFlying(boolean flying) {
        this.entityData.set(DATA_FLYING, flying);
        if (flying) {
            this.setDeltaMovement(this.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
            this.resetFallDistance();
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Flying", this.isFlying());
        tag.putLong("AeLink", this.aeLink);
        if (!this.aeKey.isEmpty()) {
            tag.put("AeKey", this.aeKey.save(this.level().registryAccess()));
        }
        net.minecraft.nbt.ListTag patternList = new net.minecraft.nbt.ListTag();
        for (ItemStack stack : this.aePatterns) {
            if (stack.isEmpty()) {
                continue;
            }
            try {
                patternList.add(stack.save(this.level().registryAccess()));
            } catch (Throwable t) {
                // 样板里含失效的注册表引用（比如被移除的附魔）时跳过，别让它带崩存档
                ae2lunyi.LOGGER.warn("Skipping un-saveable pattern", t);
            }
        }
        tag.put("AePatterns", patternList);
        tag.putString("AeSortBy", this.aeSortBy);
        tag.putString("AeSortDir", this.aeSortDir);
        for (int i = 0; i < this.aeCards.size(); i++) {
            ItemStack card = this.aeCards.get(i);
            if (!card.isEmpty()) {
                tag.put("AeCard" + i, card.save(this.level().registryAccess()));
            }
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setFlying(tag.getBoolean("Flying"));
        this.aeLink = tag.getLong("AeLink");
        this.aeKey = ItemStack.parseOptional(this.level().registryAccess(), tag.getCompound("AeKey"));
        if (tag.contains("AeSortBy")) { this.aeSortBy = tag.getString("AeSortBy"); }
        if (tag.contains("AeSortDir")) { this.aeSortDir = tag.getString("AeSortDir"); }
        this.patternRefreshPending = true;
        for (int i = 0; i < this.aeCards.size(); i++) {
            this.aeCards.set(i, ItemStack.parseOptional(this.level().registryAccess(),
                    tag.getCompound("AeCard" + i)));
        }
        this.aePatterns.clear();
        net.minecraft.nbt.ListTag patternList = tag.getList("AePatterns", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < patternList.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(this.level().registryAccess(), patternList.getCompound(i));
            if (!stack.isEmpty()) {
                this.aePatterns.add(stack);
            }
        }
    }

    /** 样板仓（无上限）。 */
    public java.util.List<ItemStack> getPatterns() {
        return this.aePatterns;
    }

    /** 绑定的量子桥频率，0 表示没绑定。 */
    public long getAeLink() {
        return this.aeLink;
    }

    public boolean hasAeLink() {
        return this.getAeLinkFrequency() != 0L;
    }

    /** 绑定槽里那个奇点的频率；槽里没有就用记住的频率。 */
    public long getAeLinkFrequency() {
        if (!this.aeKey.isEmpty()) {
            long frequency = Ae2Compat.getSingularityFrequency(this.aeKey);
            if (frequency != 0L) {
                return frequency;
            }
        }
        return this.aeLink;
    }

    public net.minecraft.core.NonNullList<ItemStack> getAeCards() {
        return this.aeCards;
    }

    public ItemStack getAeKey() {
        return this.aeKey;
    }

    public void setAeKey(ItemStack stack) {
        this.aeKey = stack.copy();
        long frequency = Ae2Compat.getSingularityFrequency(this.aeKey);
        this.aeLink = frequency != 0L ? frequency : 0L;
    }

    // ------------------------------------------------------------------
    // 骑乘
    // ------------------------------------------------------------------

    @Override
    protected void registerGoals() {
        // 没有 AI：它就是一架等着人坐的轮椅
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return this.getFirstPassenger() instanceof Player player ? player : null;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** 所有已加载的轮椅（服务端），供跨维度的样板查询使用。 */
    private static final java.util.List<WheelchairEntity> ACTIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static java.util.List<WheelchairEntity> activeWheelchairs() {
        return ACTIVE;
    }
    @Override
    public void remove(RemovalReason reason) {
        ACTIVE.remove(this);
        this.releaseChunkTicket();
        Ae2Compat.removeGridNode(this);
        super.remove(reason);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);

        // 手里拿着编码好的样板右键：把背包里所有样板一次放进去（不设上限）
        if (Ae2Compat.isEncodedPattern(held)) {
            if (!this.level().isClientSide()) {
                int moved = this.uploadPatternsFrom(player);
                if (moved > 0) {
                    Ae2Compat.refreshNearbyCores(this);
                }
                player.displayClientMessage(Component.translatable("message.ae2_lunyi.pattern_added",
                        this.aePatterns.size()), true);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }

        // 手里拿着量子缠绕奇点 + Shift + 右键：直接用这个奇点开终端，不需要先绑定
        if (Ae2Compat.isEntangledSingularity(held) && player.isSecondaryUseActive()) {
            if (!this.level().isClientSide()) {
                Ae2Compat.tryOpenTerminal(player, this);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }

        // 单手拿着奇点右键：把它塞进轮椅，作为绑定
        if (Ae2Compat.isEntangledSingularity(held)) {
            if (!this.level().isClientSide()) {
                long frequency = Ae2Compat.getSingularityFrequency(held);
                if (frequency != 0L) {
                    this.setAeKey(held.copy());
                    if (!player.getAbilities().instabuild) {
                        held.shrink(1);
                    }
                    player.displayClientMessage(Component.translatable("message.ae2_lunyi.bound"), true);
                }
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }

        // Shift + 右键：先把背包里的编码样板一键塞进样板仓；
        // 没有可上传的（0 个）才退回去打开合成终端。
        if (player.isSecondaryUseActive()) {
            if (!this.level().isClientSide()) {
                int moved = this.uploadPatternsFrom(player);
                if (moved > 0) {
                    player.displayClientMessage(
                            Component.translatable("message.ae2_lunyi.uploaded", moved), true);
                    Ae2Compat.refreshNearbyCores(this);
                } else if (!Ae2Compat.tryOpenTerminal(player, this)) {
                    player.displayClientMessage(Component.translatable("message.ae2_lunyi.no_terminal"), true);
                }
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }

        if (this.getPassengers().isEmpty()) {
            if (!this.level().isClientSide()) {
                player.startRiding(this);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        return InteractionResult.PASS;
    }

    /**
     * 玩家脚底落在坐垫上（模型里坐垫在 y=12 像素处，即离地 0.75 格）。
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity entity, EntityDimensions dimensions, float partialTick) {
        return new Vec3(0.0D, 0.72D, 0.0D);
    }

    // ------------------------------------------------------------------
    // 无敌 / 拆除
    // ------------------------------------------------------------------

    /**
     * 轮椅不吃任何伤害。唯一的"拆除"方式：玩家按住 Shift 左键打它，
     * 这时会先弹出骑手、掉一个刷怪蛋，然后把自己移除。
     * <p>
     * 注意这里不用 {@code setInvulnerable(true)}：原版那个标记对创造模式玩家和
     * {@code BYPASSES_INVULNERABILITY} 伤害是穿透的，会留后门。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide()) {
            return false;
        }
        if (source.getDirectEntity() instanceof Player player && player.isShiftKeyDown() && !this.isRemoved()) {
            this.breakApart(player);
            return true;
        }
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    /** 把玩家背包里所有编码样板搬进随身样板仓，返回搬了几个。 */
    public int uploadPatternsFrom(Player player) {
        int moved = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !Ae2Compat.isEncodedPattern(stack)) {
                continue;
            }
            // 严格模式或关闭“接受全部样板”时，只搬合成样板
            if ((!com.dianzhong.lunyi.Config.PATTERN_STORE_ALL_PATTERNS.get()
                    || !com.dianzhong.lunyi.Config.STRICT_PATTERN_VALIDATION.get())
                    && !Ae2Compat.isCraftingPattern(stack)) {
                continue;
            }
            int count = stack.getCount();
            for (int k = 0; k < count; k++) {
                this.aePatterns.add(stack.copyWithCount(1));
                moved++;
            }
            if (!player.getAbilities().instabuild) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        return moved;
    }

    private void breakApart(Player player) {
        this.ejectPassengers();

        ItemStack egg = new ItemStack(ModItems.WHEELCHAIR_SPAWN_EGG.get());
        ItemEntity drop = new ItemEntity(this.level(), this.getX(), this.getY() + 0.4D, this.getZ(), egg);
        drop.setDefaultPickUpDelay();
        this.level().addFreshEntity(drop);

        // 随身样板仓里的样板也一起返还，别让它们跟着实体消失
        for (ItemStack pattern : new java.util.ArrayList<>(this.getPatterns())) {
            if (pattern.isEmpty()) {
                continue;
            }
            ItemEntity patternDrop = new ItemEntity(this.level(), this.getX(), this.getY() + 0.4D, this.getZ(),
                    pattern.copy());
            patternDrop.setDefaultPickUpDelay();
            this.level().addFreshEntity(patternDrop);
        }
        this.getPatterns().clear();

        ae2lunyi.LOGGER.debug("Wheelchair at {} removed by {}", this.blockPosition(), player.getName().getString());
        this.discard();
    }

    // ------------------------------------------------------------------
    // 飞行与转向
    // ------------------------------------------------------------------

    @Override
    protected void tickRidden(Player player, Vec3 travelVector) {
        super.tickRidden(player, travelVector);
        // 水平朝向完全跟随视角。俯仰角这里刻意不跟：模型一旦立起来，乘客的挂点不会跟着转，
        // 看上去会"人椅分离"。
        float previous = this.getYRot();
        this.setRot(player.getYRot(), 0.0F);
        // setYRot 会把角度折回 0..360。跨过正北时上一 tick 是 359、这一 tick 是 1，
        // 渲染端直接 lerp 会绕远路（一帧抽搐），这里把 yRotO 修正成"未折回"的上一 tick 值。
        float current = this.getYRot();
        this.yRotO = current - Mth.wrapDegrees(current - previous);
        this.yBodyRot = current;
        this.yHeadRot = current;
    }

    /**
     * 把骑手的按键翻译成方向向量：x = 左右，y = 升降，z = 前后。
     * 只有飞行状态下 y 才非 0。
     */
    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 travelVector) {
        float strafe = player.xxa;
        float forward = player.zza;
        double vertical = 0.0D;
        if (this.isFlying()) {
            if (player.jumping) {
                vertical += 1.0D;
            }
            if (player.isShiftKeyDown()) {
                vertical -= 1.0D;
            }
        }
        return new Vec3(strafe, vertical, forward);
    }

    @Override
    protected float getRiddenSpeed(Player player) {
        return RIDE_SPEED;
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isFlying() && this.isVehicle() && this.getControllingPassenger() != null) {
            if (this.isControlledByLocalInstance()) {
                // 飞行：不施加重力，速度完全由输入决定
                this.moveRelative(this.getSpeed(), travelVector);
                this.move(MoverType.SELF, this.getDeltaMovement());
                this.setDeltaMovement(this.getDeltaMovement().scale(0.9D));
                this.resetFallDistance();
            }
            return;
        }
        // 地面模式：骑手按空格就跳
        if (!this.isFlying() && this.onGround()
                && this.getControllingPassenger() instanceof Player rider && rider.jumping) {
            Vec3 motion = this.getDeltaMovement();
            this.setDeltaMovement(motion.x, JUMP_POWER, motion.z);
            this.hasImpulse = true;
        }
        super.travel(travelVector);
    }

    // ------------------------------------------------------------------
    // tick：双击空格切飞行 + 轮子转动 + 给骑手上效果
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            this.tickWheelSpin();
            return;
        }

        if (!ACTIVE.contains(this)) {
            ACTIVE.add(this);
        }
        if (this.patternRefreshPending && !this.level().isClientSide()) {
            this.patternRefreshPending = false;
            Ae2Compat.refreshNearbyCores(this);
        }

        // 维护自己的 AE2 网格节点（装了 AE2 才有意义）
        Ae2Compat.tickGridNode(this);

        if (this.getFirstPassenger() instanceof Player rider) {
            this.followRiderId = rider.getUUID();
            this.followTicks = 0;
            this.followOrigin = null;
            this.riderLastPos = rider.position();
            this.handleFlightToggle(rider);
            if (this.tickCount % EFFECT_REFRESH_INTERVAL == 0) {
                this.applyRiderBuffs(rider);
            }
        } else {
            this.lastJumping = false;
            if (this.followOrigin == null && this.followRiderId != null) {
                // remember where the rider was sitting, so a teleport jump can be detected
                this.followOrigin = this.riderLastPos != null ? this.riderLastPos : this.position();
            }
            this.tickFollowLostRider();
        }

        // keep our chunk loaded while someone is (or just was) riding, so we keep ticking
        // even after the rider teleports to another chunk or dimension
        if (this.tickCount % 20 == 0) {
            this.maintainChunkTicket(this.getFirstPassenger() != null || this.followRiderId != null);
        }
    }

    /** Force-load the chunk we are in so this entity keeps ticking when the rider leaves. */
    private void maintainChunkTicket(boolean keepLoaded) {
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        int chunkX = this.chunkPosition().x;
        int chunkZ = this.chunkPosition().z;

        if (this.chunkForced && (!keepLoaded || this.forcedChunkX != chunkX || this.forcedChunkZ != chunkZ)) {
            serverLevel.setChunkForced(this.forcedChunkX, this.forcedChunkZ, false);
            this.chunkForced = false;
        }
        if (keepLoaded && !this.chunkForced) {
            serverLevel.setChunkForced(chunkX, chunkZ, true);
            this.chunkForced = true;
            this.forcedChunkX = chunkX;
            this.forcedChunkZ = chunkZ;
        }
    }

    private void releaseChunkTicket() {
        if (this.chunkForced && this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            serverLevel.setChunkForced(this.forcedChunkX, this.forcedChunkZ, false);
            this.chunkForced = false;
        }
    }
    /**
     * 轮子转动完全由客户端算：只有贴地且没在飞的时候才累计，
     * 所以飞行时轮子会自然停住，落地后接着转。
     */
    private void tickWheelSpin() {
        this.wheelSpinO = this.wheelSpin;
        if (!this.isFlying() && this.onGround()) {
            // walkDist 是按 0.6 的系数累加的水平距离
            double distance = (this.walkDist - this.lastWalkDist) / 0.6D;
            if (distance > 0.0D) {
                this.wheelSpin += (float) (distance / BIG_WHEEL_RADIUS_BLOCKS);
            }
        }
        this.lastWalkDist = this.walkDist;
    }

    public float getWheelSpin() {
        return this.wheelSpin;
    }

    public float getWheelSpinO() {
        return this.wheelSpinO;
    }

    /**
     * 双击空格 = 切换飞行。客户端每 tick 都会把 {@code jumping} 发到服务端，
     * 所以这里直接用玩家自己的跳跃状态做边沿检测，不需要额外的网络包。
     */
    private void handleFlightToggle(Player rider) {
        boolean jumping = rider.jumping;
        if (jumping && !this.lastJumping) {
            if (this.tickCount - this.lastJumpTick <= DOUBLE_TAP_WINDOW_TICKS) {
                this.setFlying(!this.isFlying());
                this.lastJumpTick = -DOUBLE_TAP_WINDOW_TICKS - 1;
            } else {
                this.lastJumpTick = this.tickCount;
            }
        }
        this.lastJumping = jumping;
    }

    private void applyRiderBuffs(Player rider) {
        rider.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, EFFECT_DURATION_TICKS, EFFECT_AMPLIFIER,
                false, false, true));
        rider.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_DURATION_TICKS, EFFECT_AMPLIFIER,
                false, false, true));
        rider.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, EFFECT_DURATION_TICKS, EFFECT_AMPLIFIER,
                false, false, true));
        rider.addEffect(new MobEffectInstance(MobEffects.SATURATION, EFFECT_DURATION_TICKS, EFFECT_AMPLIFIER,
                false, false, true));
        // 夜视单独给 1 分钟（其它效果还是 10 秒）
        rider.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_DURATION_TICKS, 0,
                false, false, true));
    }

    /** Cross-dimension follow: rebuild this wheelchair in the target level and mount the rider. */
    private void relocateToLevel(net.minecraft.server.level.ServerLevel target, Player rider) {
        try {
            var type = com.dianzhong.lunyi.registry.ModEntities.WHEELCHAIR.get();
            WheelchairEntity copy = type.create(target);
            if (copy == null) {
                return;
            }
            var tag = new CompoundTag();
            this.saveWithoutId(tag);
            copy.load(tag);
            copy.moveTo(rider.getX(), rider.getY(), rider.getZ(), rider.getYRot(), 0.0F);
            copy.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            target.addFreshEntity(copy);
            rider.startRiding(copy, true);
            copy.followRiderId = rider.getUUID();
            copy.followTicks = 0;
            this.discard();
        } catch (Throwable t) {
            com.dianzhong.lunyi.ae2lunyi.LOGGER.error("Wheelchair: cross-dimension follow failed", t);
        }
    }
    /**
     * Only follow the rider when they jumped a long distance right after dismounting
     * (which is what a teleport looks like). Walking away never triggers it.
     */
    private void tickFollowLostRider() {
        if (this.followRiderId == null) {
            return;
        }
        // watch for one second after the rider left the seat
        if (++this.followTicks > 20) {
            this.followRiderId = null;
            this.followOrigin = null;
            return;
        }
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        Player rider = serverLevel.getServer().getPlayerList().getPlayer(this.followRiderId);
        if (rider == null || rider.getVehicle() != null) {
            this.followRiderId = null;
            this.followOrigin = null;
            return;
        }
        // a real teleport jumps far in a single tick; walking cannot
        double moved = this.followOrigin == null ? 0.0D : this.followOrigin.distanceToSqr(rider.position());
        if (moved < 1024.0D) {
            return;
        }
        this.followRiderId = null;
        this.followOrigin = null;
        if (rider.level() != this.level()) {
            if (rider.level() instanceof net.minecraft.server.level.ServerLevel target) {
                this.relocateToLevel(target, rider);
            }
            return;
        }
        this.setPos(rider.getX(), rider.getY(), rider.getZ());
        this.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        this.setYRot(rider.getYRot());
        rider.startRiding(this, true);
    }
}

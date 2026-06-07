package com.example.fabricmod.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * 引诱与跟随（双向链接）：
 * - 阵型跟随：怪物在玩家身后呈扇形散开，不挤在一起
 * - 引诱链接：手持吸引物品时，怪物更紧密跟随
 * - 双向反馈：跟随中的怪物头顶飘浮灵魂粒子，指示状态
 * - 卡住检测 + 超距传送沿用
 */
public class FollowOwnerGoal extends Goal {

    private final MobEntity mob;
    private final PlayerEntity player;
    private final double speed;
    private final double stopDistance;
    private final double teleportDistance;
    private final int formationIndex;     // 阵型位置索引，决定散开角度

    private int cooldown;
    private int stuckTimer;
    private Vec3d lastPos;
    private int failedPathCount;
    private int retryCooldown;
    private int particleTimer;            // 粒子显示计时器

    private static final int STUCK_THRESHOLD = 40;
    private static final int MAX_FAILED_PATH = 10;
    private static final int TELEPORT_COOLDOWN = 30;

    /**
     * @param formationIndex 阵型位置索引：0=正后方，1=左后，2=右后，依次扩散
     */
    public FollowOwnerGoal(MobEntity mob, PlayerEntity player, double speed,
                            double stopDistance, double teleportDistance, int formationIndex) {
        this.mob = mob;
        this.player = player;
        this.speed = speed;
        this.stopDistance = stopDistance;
        this.teleportDistance = teleportDistance;
        this.formationIndex = formationIndex;
        this.setControls(EnumSet.of(Control.MOVE, Control.JUMP));
    }

    public FollowOwnerGoal(MobEntity mob, PlayerEntity player, double speed, double stopDistance) {
        this(mob, player, speed, stopDistance, 12.0, 0);
    }

    @Override
    public boolean canStart() {
        // 只有玩家手持旗帜时才会跟随（放下旗帜→原地待命）
        if (player == null || !player.isAlive()) return false;
        if (!(player.getMainHandStack().getItem() instanceof net.minecraft.item.BannerItem))
            return false;
        return mob.distanceTo(player) > stopDistance;
    }

    @Override
    public boolean shouldContinue() {
        // 放下旗帜或玩家死亡 → 停止跟随
        if (player == null || !player.isAlive()) return false;
        return player.getMainHandStack().getItem() instanceof net.minecraft.item.BannerItem;
    }

    @Override
    public void start() {
        resetState();
        if (mob.distanceTo(player) > stuckTeleportDistance()) {
            tryTeleport();
        }
        tryNavigate();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        resetState();
    }

    @Override
    public void tick() {
        if (player == null || player.isRemoved()) return;

        // —— 判断玩家是否手持旗帜（引诱模式） ——
        boolean holdingBanner = player.getMainHandStack().getItem()
                instanceof net.minecraft.item.BannerItem;
        double effectiveStopDist = holdingBanner ? stopDistance * 0.6 : stopDistance;
        double effectiveSpeed = holdingBanner ? speed * 1.1 : speed;

        double dist = mob.distanceTo(player);

        // —— ① 超距传送 ——
        if (dist > teleportDistance || failedPathCount >= MAX_FAILED_PATH) {
            tryTeleport();
            return;
        }

        // —— ② 卡住检测 ——
        Vec3d pos = mob.getPos();
        if (lastPos != null && dist > effectiveStopDist) {
            if (pos.distanceTo(lastPos) < 0.05) {
                if (++stuckTimer > STUCK_THRESHOLD) {
                    mob.getJumpControl().setActive();
                    tryTeleport();
                    stuckTimer = 0;
                    return;
                }
            } else stuckTimer = 0;
        }
        lastPos = pos;

        // —— ③ 引诱模式：头部转向玩家 ——
        if (holdingBanner) {
            mob.getLookControl().lookAt(player, 60.0F, 60.0F);
            // 引诱粒子：金色光芒环绕
            if (mob.getWorld() instanceof ServerWorld sw && mob.getRandom().nextInt(10) == 0) {
                sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                        mob.getX(), mob.getY() + 1.5, mob.getZ(),
                        1, 0.2, 0.3, 0.2, 0.01);
            }
        }

        // —— ④ 走向阵型位置 ——
        Vec3d formationTarget = getFormationPosition();
        double formationDist = mob.squaredDistanceTo(formationTarget);

        if (formationDist > effectiveStopDist * effectiveStopDist) {
            mob.getNavigation().startMovingTo(
                    formationTarget.x, formationTarget.y, formationTarget.z, effectiveSpeed);
        } else {
            mob.getNavigation().stop();
            failedPathCount = 0;
        }

        // —— ⑤ 状态粒子 ——
        particleTimer--;
        if (particleTimer <= 0 && mob.getWorld() instanceof ServerWorld sw) {
            particleTimer = holdingBanner ? 15 : 30;
            sw.spawnParticles(
                    holdingBanner
                        ? net.minecraft.particle.ParticleTypes.END_ROD
                        : net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,
                    mob.getX(), mob.getY() + 2.2, mob.getZ(),
                    1, 0.1, 0, 0.1, 0.005);
        }
    }

    /**
     * 计算阵型位置：玩家身后扇形散开。
     * 索引 0 → 正后方 2 格
     * 索引 1 → 左后方 2.5 格
     * 索引 2 → 右后方 2.5 格
     * 索引 3+ → 更外侧扩散
     */
    private Vec3d getFormationPosition() {
        double angleOffset = 0;
        double distance = 2.0;
        double spread = Math.PI / 3;  // 60 度扇形

        if (formationIndex == 0) {
            angleOffset = 0;           // 正后方
            distance = 2.0;
        } else if (formationIndex == 1) {
            angleOffset = -spread * 0.4;  // 左后
            distance = 2.5;
        } else if (formationIndex == 2) {
            angleOffset = spread * 0.4;   // 右后
            distance = 2.5;
        } else {
            int side = (formationIndex % 2 == 0) ? -1 : 1;
            int tier = (formationIndex - (formationIndex % 2)) / 2;
            angleOffset = side * spread * (0.5 + tier * 0.3);
            distance = 3.0 + tier * 0.8;
        }

        float yaw = player.getYaw();
        double behindAngle = Math.toRadians(yaw + 180 + Math.toDegrees(angleOffset));

        double tx = player.getX() + distance * Math.sin(behindAngle);
        double tz = player.getZ() + distance * Math.cos(behindAngle);

        return new Vec3d(tx, player.getY(), tz);
    }

    private double stuckTeleportDistance() {
        return teleportDistance * 1.5;
    }

    private void resetState() {
        cooldown = 0; stuckTimer = 0; lastPos = null;
        failedPathCount = 0; retryCooldown = 0; particleTimer = 0;
    }

    private boolean tryNavigate() {
        if (retryCooldown > 0) { retryCooldown--; return false; }
        Vec3d f = getFormationPosition();
        boolean ok = mob.getNavigation().startMovingTo(f.x, f.y, f.z, speed);
        if (!ok) { failedPathCount++; retryCooldown = 5; }
        else failedPathCount = 0;
        return ok;
    }

    private void tryTeleport() {
        if (cooldown > 0) { cooldown--; return; }
        cooldown = TELEPORT_COOLDOWN;
        if (!(mob.getWorld() instanceof ServerWorld sw)) return;
        if (player == null || player.isRemoved()) return;

        for (int a = 0; a < 12; a++) {
            double rad = mob.getRandom().nextDouble() * 2 * Math.PI;
            double r = 2 + mob.getRandom().nextDouble() * 3;
            double x = player.getX() + r * Math.cos(rad);
            double z = player.getZ() + r * Math.sin(rad);
            mob.refreshPositionAndAngles(x, player.getY(), z, mob.getYaw(), mob.getPitch());
            if (!sw.isSpaceEmpty(mob)) continue;
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.REVERSE_PORTAL,
                    mob.getX(), mob.getBodyY(0.5), mob.getZ(),
                    8, 0.5, 0.5, 0.5, 0.1);
            mob.getNavigation().stop();
            failedPathCount = 0; stuckTimer = 0;
            return;
        }
    }
}

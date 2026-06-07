package com.example.fabricmod.entity.goal;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;

/**
 * 自定义攻击 AI（替代原版 MeleeAttackGoal）：
 * - 攻击冷却：攻击后等待 cooldown 帧再攻击
 * - 分散走位：接近目标时避让其他友军，形成包围阵型
 * - 到达攻击距离后才触发伤害
 */
public class RtsAttackGoal extends Goal {

    private final PathAwareEntity mob;
    private final double speed;
    private final int attackCooldown;        // 攻击后冷却 tick
    private final double minAllyDistance;    // 与其他友军的最小距离
    private int cooldown;
    private int repathTimer;
    private double targetX, targetY, targetZ;

    public RtsAttackGoal(PathAwareEntity mob, double speed, int attackCooldown, double minAllyDistance) {
        this.mob = mob;
        this.speed = speed;
        this.attackCooldown = attackCooldown;
        this.minAllyDistance = minAllyDistance;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    public RtsAttackGoal(PathAwareEntity mob, double speed) {
        this(mob, speed, 20, 2.0);  // 默认 1 秒冷却，2 格间距
    }

    @Override
    public boolean canStart() {
        return mob.getTarget() != null && mob.getTarget().isAlive();
    }

    @Override
    public boolean shouldContinue() {
        return mob.getTarget() != null && mob.getTarget().isAlive();
    }

    @Override
    public void start() {
        LivingEntity target = mob.getTarget();
        if (target != null) {
            this.targetX = target.getX();
            this.targetY = target.getY();
            this.targetZ = target.getZ();
        }
        this.cooldown = 0;
        this.repathTimer = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        this.cooldown = 0;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return;

        double dist = mob.distanceTo(target);
        double attackRange = mob.getWidth() + target.getWidth() + 1.0;

        // —— 寻找散开位置 ——
        Vec3d moveTarget = findSpreadPosition(target);

        // —— 导航 ——
        if (dist > attackRange * 1.5) {
            mob.getNavigation().startMovingTo(moveTarget.x, moveTarget.y, moveTarget.z, speed);
            mob.getLookControl().lookAt(target, 30.0F, 30.0F);
        } else if (dist > attackRange) {
            mob.getNavigation().startMovingTo(moveTarget.x, moveTarget.y, moveTarget.z, speed);
        } else {
            mob.getNavigation().stop();
            mob.getLookControl().lookAt(target, 30.0F, 30.0F);

            // —— 攻击 ——
            if (cooldown <= 0) {
                mob.tryAttack(target);
                cooldown = attackCooldown + mob.getRandom().nextInt(5);  // 冷却+随机偏移
            }
        }

        if (cooldown > 0) cooldown--;

        // 定时刷新目标位置
        repathTimer++;
        if (repathTimer >= 20) {
            this.targetX = target.getX();
            this.targetY = target.getY();
            this.targetZ = target.getZ();
            repathTimer = 0;
        }
    }

    /**
     * 计算散开位置：围绕目标选择与友军保持距离的角度。
     */
    private Vec3d findSpreadPosition(LivingEntity target) {
        double bestAngle = -1;
        double bestScore = Double.NEGATIVE_INFINITY;

        // 尝试 8 个方向，选离其他友军最远的角度
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            double testX = target.getX() + 2.5 * Math.cos(angle);
            double testZ = target.getZ() + 2.5 * Math.sin(angle);

            double score = 0;
            // 检查附近的友军
            List<MobEntity> allies = mob.getWorld().getEntitiesByClass(
                    MobEntity.class,
                    mob.getBoundingBox().expand(8),
                    e -> e != mob && e.isAlive()
                            && e.getType() == net.minecraft.entity.EntityType.SKELETON
                            || e.getType() == net.minecraft.entity.EntityType.ZOMBIE
            );
            for (MobEntity ally : allies) {
                double dx = testX - ally.getX();
                double dz = testZ - ally.getZ();
                double allyDist = Math.sqrt(dx * dx + dz * dz);
                if (allyDist < minAllyDistance) {
                    score -= (minAllyDistance - allyDist) * 2;
                }
            }

            // 偏向当前角度，减少来回摆动
            if (bestAngle >= 0) {
                double angleDiff = Math.abs(angle - bestAngle);
                if (angleDiff < Math.PI / 4) {
                    score += 1;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestAngle = angle;
            }
        }

        return new Vec3d(
                target.getX() + 2.5 * Math.cos(bestAngle),
                target.getY(),
                target.getZ() + 2.5 * Math.sin(bestAngle)
        );
    }
}

package com.example.fabricmod.entity.goal;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;

/**
 * 冲锋信标目标（优先级 1）。
 *
 * 当玩家激活冲锋信标后，附近召唤物会优先攻击信标区域内的敌对生物。
 * 进入信标范围内最近的敌对目标，发起冲锋攻击。
 * 优先级高于自动索敌（p2），低于手动锁定（p0）。
 */
public class ChargeTargetGoal extends Goal {

    private final MobEntity mob;
    private final double chargeRange;       // 信标影响半径
    private LivingEntity target;
    private Vec3d beaconPos;
    private boolean active;
    private int scanTimer;

    public ChargeTargetGoal(MobEntity mob, double chargeRange) {
        this.mob = mob;
        this.chargeRange = chargeRange;
        this.scanTimer = 0;
        this.active = false;
        this.setControls(EnumSet.of(Control.TARGET, Control.MOVE));
    }

    public ChargeTargetGoal(MobEntity mob) {
        this(mob, 8.0);
    }

    /**
     * 设置冲锋信标位置并激活。
     */
    public void activate(Vec3d pos) {
        this.beaconPos = pos;
        this.active = true;
        this.scanTimer = 0;
    }

    /**
     * 停用冲锋。
     */
    public void deactivate() {
        this.active = false;
        this.beaconPos = null;
        this.target = null;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean canStart() {
        if (!active || beaconPos == null) return false;
        if (mob.getTarget() != null && mob.getTarget().isAlive()) return false;

        scanTimer--;
        if (scanTimer > 0) return false;
        scanTimer = 20;  // 每秒扫描一次

        // 扫描信标周围的敌对生物
        List<HostileEntity> hostiles = mob.getWorld().getEntitiesByClass(
                HostileEntity.class,
                mob.getBoundingBox().expand(chargeRange),
                e -> e != mob && e.isAlive()
        );

        if (hostiles.isEmpty()) return false;

        // 选择信标附近最近的敌对目标
        target = null;
        double nearest = Double.MAX_VALUE;
        for (HostileEntity h : hostiles) {
            double dist = h.squaredDistanceTo(beaconPos);
            if (dist < chargeRange * chargeRange && dist < nearest) {
                nearest = dist;
                target = h;
            }
        }

        return target != null && target.isAlive();
    }

    @Override
    public boolean shouldContinue() {
        if (!active) return false;
        return target != null && target.isAlive() && mob.getTarget() == target;
    }

    @Override
    public void start() {
        mob.setTarget(target);
        mob.getNavigation().startMovingTo(target, 1.3);  // 冲锋速度略快
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        this.target = null;
    }
}

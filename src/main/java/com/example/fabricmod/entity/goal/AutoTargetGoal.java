package com.example.fabricmod.entity.goal;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumSet;
import java.util.List;

/**
 * 自动索敌目标（优先级 2，低于手动集火和冲锋信标）。
 *
 * 每隔 interval tick 扫描附近 12 格内的敌对生物（HostileEntity），
 * 排除同阵营单位，选择最近的有效敌人作为攻击目标。
 */
public class AutoTargetGoal extends Goal {

    private final MobEntity mob;
    private final double range;
    private final int interval;
    private LivingEntity target;
    private int scanTimer;

    public AutoTargetGoal(MobEntity mob, double range, int interval) {
        this.mob = mob;
        this.range = range;
        this.interval = interval;
        this.scanTimer = mob.getRandom().nextInt(interval);
        this.setControls(EnumSet.of(Control.TARGET));
    }

    public AutoTargetGoal(MobEntity mob) {
        this(mob, 12.0, 40);
    }

    @Override
    public boolean canStart() {
        if (mob.getTarget() != null && mob.getTarget().isAlive()) return false;
        scanTimer--;
        if (scanTimer > 0) return false;
        scanTimer = interval;

        // 扫描附近敌对生物，排除己方阵营
        java.util.Collection<String> armyMembers = getArmyMembers();
        List<HostileEntity> hostiles = mob.getWorld().getEntitiesByClass(
                HostileEntity.class,
                mob.getBoundingBox().expand(range),
                e -> e != mob && e.isAlive()
                        && (armyMembers == null || !armyMembers.contains(e.getUuidAsString()))
        );

        if (hostiles.isEmpty()) return false;

        // 选最近的
        target = hostiles.get(0);
        double nearest = mob.squaredDistanceTo(target);
        for (HostileEntity h : hostiles) {
            double d = mob.squaredDistanceTo(h);
            if (d < nearest) {
                nearest = d;
                target = h;
            }
        }
        return target != null && target.isAlive();
    }

    @Override
    public boolean shouldContinue() {
        return target != null && target.isAlive() && mob.getTarget() == target;
    }

    @Override
    public void start() {
        mob.setTarget(target);
    }

    @Override
    public void stop() {
        this.target = null;
    }

    /**
     * 获取己方阵营的 UUID 集合，用于排除误伤。
     */
    private java.util.Collection<String> getArmyMembers() {
        if (!(mob.getWorld() instanceof ServerWorld serverWorld)) return null;
        net.minecraft.scoreboard.Team team = serverWorld.getScoreboard().getTeam("fabricmod_army");
        return team != null ? team.getPlayerList() : null;
    }
}

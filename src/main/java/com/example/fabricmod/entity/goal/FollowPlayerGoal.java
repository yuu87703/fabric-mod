package com.example.fabricmod.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.EnumSet;

/**
 * 自定义生物 AI 目标：让生物以指定速度跟随特定玩家，
 * 当距离小于 stopDistance 格时停止移动（原地待命），
 * 玩家走远后继续跟随。
 */
public class FollowPlayerGoal extends Goal {

    private final MobEntity mob;
    private final PlayerEntity player;
    private final double speed;
    private final double stopDistance;

    /**
     * @param mob          要添加该目标的生物
     * @param player       要跟随的玩家
     * @param speed        移动速度（如 1.2）
     * @param stopDistance 距离玩家小于此值时停止移动
     */
    public FollowPlayerGoal(MobEntity mob, PlayerEntity player, double speed, double stopDistance) {
        this.mob = mob;
        this.player = player;
        this.speed = speed;
        this.stopDistance = stopDistance;
        // 控制该目标占用生物的 "移动" 和 "视角" 行为
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    /**
     * 判断该目标是否可以启动。
     * 只有当玩家存活且距离 > stopDistance 时才去追赶。
     */
    @Override
    public boolean canStart() {
        if (player == null || !player.isAlive()) return false;
        return mob.distanceTo(player) > stopDistance;
    }

    /**
     * 目标启动后是否持续运行。
     * 只要玩家存活就持续保持激活（距离近时由 tick() 控制停走，不退出目标）。
     */
    @Override
    public boolean shouldContinue() {
        return player != null && player.isAlive();
    }

    /**
     * 目标启动时开始导航走向玩家。
     */
    @Override
    public void start() {
        mob.getNavigation().startMovingTo(player, speed);
    }

    /**
     * 目标停止时停止导航。
     */
    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    /**
     * 每 tick 执行：根据距离控制移动，并始终看向玩家。
     */
    @Override
    public void tick() {
        if (mob.distanceTo(player) > stopDistance) {
            // 距离远 → 继续走向玩家
            mob.getNavigation().startMovingTo(player, speed);
        } else {
            // 距离 ≤ stopDistance → 停止移动（原地待命）
            mob.getNavigation().stop();
        }
        // 始终面向玩家
        mob.getLookControl().lookAt(player, 30.0F, 30.0F);
    }
}

package com.example.fabricmod.entity.goal;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.EnumSet;

/**
 * 护卫目标
 *
 * 优先级：
 *   ① 优先攻击玩家正在攻击的目标（player.getAttacking()）
 *   ② 其次攻击最近伤害了玩家的实体（player.getAttacker()）
 *
 * tick 逻辑：每帧刷新，始终追踪玩家最新的敌对目标。
 */
public class DefendPlayerTargetGoal extends Goal {

    private final MobEntity mob;
    private final PlayerEntity player;
    private LivingEntity target;

    public DefendPlayerTargetGoal(MobEntity mob, PlayerEntity player) {
        this.mob = mob;
        this.player = player;
        this.setControls(EnumSet.of(Control.TARGET));
    }

    /**
     * 启动条件：
     * - 生物距离玩家 > 2 格（正在跟随途中）→ 不攻击
     * - 玩家有攻击目标 或 玩家被攻击
     */
    @Override
    public boolean canStart() {
        // —— 正在跟随途中（距离 > 2 格）→ 不攻击 ——
        if (mob.distanceTo(player) > 2.0) return false;

        // ① 优先：玩家正在攻击的目标
        LivingEntity attacking = player.getAttacking();
        if (attacking != null && attacking.isAlive() && attacking != player) {
            target = attacking;
            return true;
        }

        // ② 其次：最近攻击了玩家的实体
        LivingEntity attacker = player.getAttacker();
        if (attacker != null && attacker.isAlive() && attacker != player) {
            target = attacker;
            return true;
        }

        return false;
    }

    /**
     * 持续条件：目标存活、玩家存活、且生物在玩家身边。
     */
    @Override
    public boolean shouldContinue() {
        // 玩家走远 → 放弃攻击去跟随
        if (mob.distanceTo(player) > 2.0) return false;
        return target != null && target.isAlive()
                && player != null && player.isAlive();
    }

    @Override
    public void start() {
        this.mob.setTarget(target);
    }

    @Override
    public void stop() {
        this.mob.setTarget(null);
        this.target = null;
    }

    /**
     * 每 tick 执行：刷新目标。
     * 若玩家走远，立即清除仇恨让生物去跟随。
     */
    @Override
    public void tick() {
        // 玩家走远 → 放弃攻击
        if (mob.distanceTo(player) > 2.0) {
            this.mob.setTarget(null);
            return;
        }

        // 检查玩家是否切换了攻击目标
        LivingEntity attacking = player.getAttacking();
        if (attacking != null && attacking.isAlive() && attacking != player) {
            target = attacking;
            this.mob.setTarget(target);
            return;
        }

        // 检查玩家是否被新的实体攻击
        LivingEntity attacker = player.getAttacker();
        if (attacker != null && attacker.isAlive() && attacker != player) {
            target = attacker;
            this.mob.setTarget(target);
            return;
        }

        // 目标已死亡或无效 → 清除
        if (target == null || !target.isAlive()) {
            this.mob.setTarget(null);
        }
    }
}

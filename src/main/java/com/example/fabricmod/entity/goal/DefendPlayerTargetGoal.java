package com.example.fabricmod.entity.goal;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.TargetGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.EnumSet;

/**
 * 自定义目标：怪物会攻击玩家当前的攻击目标（getAttacking），
 * 或攻击对玩家造成伤害的敌对实体（getAttacker）。
 * 基于 TargetGoal 实现。
 */
public class DefendPlayerTargetGoal extends TargetGoal {

    private final PlayerEntity player;
    private LivingEntity target;

    public DefendPlayerTargetGoal(MobEntity mob, PlayerEntity player) {
        super(mob, false, false);
        this.player = player;
        this.setControls(EnumSet.of(Control.TARGET));
    }

    @Override
    public boolean canStart() {
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

    @Override
    public boolean shouldContinue() {
        return target != null && target.isAlive()
                && player != null && player.isAlive();
    }

    @Override
    public void start() {
        this.mob.setTarget(target);
        super.start();
    }

    @Override
    public void stop() {
        this.mob.setTarget(null);
        this.target = null;
        super.stop();
    }

    @Override
    public void tick() {
        // 每 tick 刷新目标
        LivingEntity attacking = player.getAttacking();
        if (attacking != null && attacking.isAlive() && attacking != player) {
            target = attacking;
            this.mob.setTarget(target);
            return;
        }

        LivingEntity attacker = player.getAttacker();
        if (attacker != null && attacker.isAlive() && attacker != player) {
            target = attacker;
            this.mob.setTarget(target);
            return;
        }

        if (target == null || !target.isAlive()) {
            this.mob.setTarget(null);
        }
    }
}

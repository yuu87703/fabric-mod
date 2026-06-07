package com.example.fabricmod.networking;

import com.example.fabricmod.entity.goal.DefendPlayerTargetGoal;
import com.example.fabricmod.entity.goal.FollowOwnerGoal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import net.minecraft.scoreboard.Team;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * RTS command handler (singleton) for Minecraft 1.21.1 Fabric.
 *
 * Commands:
 *   commandMove    → bind RtsMoveGoal, march to coordinate
 *   commandAttack  → set target, chase
 *   commandFollow  → rebind FollowOwnerGoal + DefendPlayerTargetGoal
 *   commandStop    → clear target, stop navigation
 *   clearAI        → full reset
 *   selectNearbyMobs → scan 16 blocks for skeletons/zombies
 */
public class RtsCommandHandler {

    private static final RtsCommandHandler INSTANCE = new RtsCommandHandler();
    private RtsCommandHandler() {}
    public static RtsCommandHandler getInstance() { return INSTANCE; }

    // ═══════════════════════════════════════════════
    //  Selection — 选择单位
    // ═══════════════════════════════════════════════

    public static List<MobEntity> selectNearbyMobs(ServerPlayerEntity player) {
        List<MobEntity> list = new ArrayList<>();
        for (Entity e : player.getServerWorld().getEntitiesByClass(MobEntity.class,
                player.getBoundingBox().expand(16),
                e -> (e.getType() == net.minecraft.entity.EntityType.SKELETON
                        || e.getType() == net.minecraft.entity.EntityType.ZOMBIE)
                        && e.isAlive())) {
            list.add((MobEntity) e);
        }
        player.sendMessage(Text.literal("§e[RTS] §6" + list.size() + " §e个单位"), false);
        return list;
    }

    // ═══════════════════════════════════════════════
    //  Commands — 指挥命令
    // ═══════════════════════════════════════════════

    public static void commandMove(List<MobEntity> mobs, ServerPlayerEntity p, Vec3d target) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetState(m);
            m.goalSelector.add(1, new RtsMoveGoal(m, target, 1.0));
        }
        p.sendMessage(Text.literal("§e[RTS] §6" + mobs.size() + " §e个单位正在移动"), false);
    }

    public static void commandAttack(List<MobEntity> mobs, ServerPlayerEntity p, LivingEntity target) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetState(m);
            m.setTarget(target);
            m.getNavigation().startMovingTo(target, 1.2);
            m.goalSelector.add(2, new MeleeAttackGoal(m, 1.2, true));
            // 锁定攻击目标（最高优先级，但不移除原有的 DefendPlayerTargetGoal）
            m.targetSelector.add(1, new LockTargetGoal(m, target));
        }
        p.sendMessage(Text.literal("§c[RTS] §6" + mobs.size()
                + " §c个单位正在攻击 §e" + target.getName().getString()), false);
    }

    public static void commandFollow(List<MobEntity> mobs, ServerPlayerEntity p) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetState(m);
            m.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE,
                    net.minecraft.entity.effect.StatusEffectInstance.INFINITE,
                    0, false, false, false));
            m.goalSelector.add(1, new FollowOwnerGoal(m, p, 1.2, 2.0));
            m.targetSelector.add(1, new DefendPlayerTargetGoal(m, p));
        }
        p.sendMessage(Text.literal("§a[RTS] §6" + mobs.size() + " §a个单位切换为跟随模式"), false);
    }

    public static void commandStop(List<MobEntity> mobs, ServerPlayerEntity p) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetState(m);
        }
        p.sendMessage(Text.literal("§7[RTS] §6" + mobs.size() + " §7个单位已停止"), false);
    }

    public static void clearAI(List<MobEntity> mobs) {
        if (mobs == null) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetState(m);
        }
    }

    // ──────── Reset mob state (stop + clear target) ────────
    private static void resetState(MobEntity mob) {
        mob.getNavigation().stop();
        mob.setTarget(null);
    }

    // ═══════════════════════════════════════════════
    //  Inner class: LockTargetGoal (优先于 DefendPlayerTargetGoal)
    // ═══════════════════════════════════════════════
    //  Manually specified attack target — priority 0 (highest).
    //  Will not remove the auto-defend goal (priority 1), but takes precedence.
    //  手动指定的攻击目标，优先级 0（最高），不覆盖自动防御目标。

    public static class LockTargetGoal extends Goal {
        private final MobEntity mob;
        private final LivingEntity target;

        public LockTargetGoal(MobEntity mob, LivingEntity target) {
            this.mob = mob;
            this.target = target;
            this.setControls(EnumSet.of(Control.TARGET));
        }

        @Override
        public boolean canStart() {
            return target != null && target.isAlive();
        }

        @Override
        public boolean shouldContinue() {
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            mob.setTarget(target);
        }

        @Override
        public void stop() {
            // Don't clear target here — let DefendPlayerTargetGoal take over
        }

        @Override
        public void tick() {
            if (target != null && target.isAlive()) {
                mob.setTarget(target);
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  Inner class: RtsMoveGoal
    // ═══════════════════════════════════════════════

    public static class RtsMoveGoal extends Goal {
        private final MobEntity mob;
        private final Vec3d target;
        private final double speed;
        private final double stopDistance;

        public RtsMoveGoal(MobEntity mob, Vec3d target, double speed, double stopDistance) {
            this.mob = mob;
            this.target = target;
            this.speed = speed;
            this.stopDistance = stopDistance;
            this.setControls(EnumSet.of(Control.MOVE));
        }
        public RtsMoveGoal(MobEntity mob, Vec3d target, double speed) {
            this(mob, target, speed, 1.0);
        }

        @Override public boolean canStart() {
            return mob.squaredDistanceTo(target) > stopDistance * stopDistance;
        }
        @Override public boolean shouldContinue() {
            return mob.squaredDistanceTo(target) > stopDistance * stopDistance;
        }
        @Override public void start() {
            mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
        }
        @Override public void stop() { mob.getNavigation().stop(); }
        @Override public void tick() {
            if (mob.squaredDistanceTo(target) > stopDistance * stopDistance)
                mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
            else mob.getNavigation().stop();
        }
    }
}

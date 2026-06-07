package com.example.fabricmod.networking;

import com.example.fabricmod.entity.goal.DefendPlayerTargetGoal;
import com.example.fabricmod.entity.goal.FollowOwnerGoal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * RTS command handler (singleton).
 * Uses vanilla Minecraft APIs: Scoreboard teams, GoalSelector, Navigation.
 *
 * 队伍管理 → 防止误伤
 * 选择单位 → 扫描 16 格内骷髅/僵尸
 * 指挥命令 → 移动 / 攻击 / 跟随 / 停止
 */
public class RtsCommandHandler {

    private static final RtsCommandHandler INSTANCE = new RtsCommandHandler();
    private static final String TEAM_PREFIX = "fabricmod_";

    private RtsCommandHandler() {}

    public static RtsCommandHandler getInstance() { return INSTANCE; }

    // ═══════════════════════════════════════════════
    //  ① Teams — 队伍管理
    // ═══════════════════════════════════════════════
    //  Player → fabricmod_owner | Mobs → fabricmod_summon
    //  Both with friendlyFire=false to prevent friendly fire.
    //  玩家和召唤物分属两个队伍，均关闭友军伤害。

    public static net.minecraft.scoreboard.Team getOrCreateSummonTeam(ServerWorld world) {
        Scoreboard sb = world.getScoreboard();
        String name = TEAM_PREFIX + "summon";
        net.minecraft.scoreboard.Team t = sb.getTeam(name);
        if (t == null) { t = sb.addTeam(name); t.setFriendlyFire(false); }
        return t;
    }

    public static net.minecraft.scoreboard.Team getOrCreateOwnerTeam(ServerWorld world) {
        Scoreboard sb = world.getScoreboard();
        String name = TEAM_PREFIX + "owner";
        net.minecraft.scoreboard.Team t = sb.getTeam(name);
        if (t == null) { t = sb.addTeam(name); t.setFriendlyFire(false); }
        return t;
    }

    public static void addPlayerToTeam(ServerPlayerEntity player) {
        ServerWorld w = player.getServerWorld();
        Scoreboard sb = w.getScoreboard();
        net.minecraft.scoreboard.Team team = getOrCreateOwnerTeam(w);
        String name = player.getName().getString();
        net.minecraft.scoreboard.Team cur = sb.getPlayerTeam(name);
        if (cur != null && !cur.getName().equals(team.getName()))
            sb.removePlayerFromTeam(name, cur);
        sb.addPlayerToTeam(name, team);
    }

    public static void addMobToTeam(MobEntity mob, ServerWorld world) {
        Scoreboard sb = world.getScoreboard();
        net.minecraft.scoreboard.Team team = getOrCreateSummonTeam(world);
        String key = mob.getUuidAsString();
        net.minecraft.scoreboard.Team cur = sb.getTeam(key);
        if (cur != null && !cur.getName().equals(team.getName()))
            sb.removePlayerFromTeam(key, cur);
        sb.addPlayerToTeam(key, team);
    }

    // ═══════════════════════════════════════════════
    //  ② Unit Selection — 选择单位
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
    //  ③ Commands — 指挥命令
    // ═══════════════════════════════════════════════
    //  Each command: clear old goals, apply new behavior, send feedback.
    //  每条命令：清除旧 AI → 应用新行为 → 发送反馈

    public static void commandMove(List<MobEntity> mobs, ServerPlayerEntity p, Vec3d target) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetGoals(m);
            m.goalSelector.add(1, new RtsMoveGoal(m, target, 1.0));
        }
        p.sendMessage(Text.literal("§e[RTS] §6" + mobs.size() + " §e个单位正在移动"), false);
    }

    public static void commandAttack(List<MobEntity> mobs, ServerPlayerEntity p, LivingEntity target) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetGoals(m);
            m.setTarget(target);
            m.getNavigation().startMovingTo(target, 1.2);
        }
        p.sendMessage(
                Text.literal("§c[RTS] §6" + mobs.size() + " §c个单位正在攻击 §e" + target.getName().getString()),
                false);
    }

    public static void commandFollow(List<MobEntity> mobs, ServerPlayerEntity p) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetGoals(m);
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
            resetGoals(m);
            m.getNavigation().stop();
            m.setTarget(null);
        }
        p.sendMessage(Text.literal("§7[RTS] §6" + mobs.size() + " §7个单位已停止"), false);
    }

    public static void clearAI(List<MobEntity> mobs) {
        if (mobs == null) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetGoals(m);
            m.getNavigation().stop();
            m.setTarget(null);
            m.setAttacker(null);
            m.setAttacking(null);
        }
    }

    // ──────── Helper: clear all goals from goalSelector & targetSelector ────────
    // 1.21.1 GoalSelector doesn't have clear(). We use getGoals().clear() instead.
    // GoalSelector 在 1.21.1 没有 clear() 方法，改用 getGoals().clear()。

    private static void resetGoals(MobEntity mob) {
        try {
            mob.goalSelector.getGoals().clear();
        } catch (Exception ignored) {}
        try {
            mob.targetSelector.getGoals().clear();
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════
    //  ④ Inner class: RtsMoveGoal
    // ═══════════════════════════════════════════════
    //  Move to fixed coordinate, stop on arrival, no auto-attack.
    //  定点移动，到达后停步，不沿途攻击。

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
            return mob.distanceTo(target.x, target.y, target.z) > stopDistance;
        }
        @Override public boolean shouldContinue() {
            return mob.distanceTo(target.x, target.y, target.z) > stopDistance;
        }
        @Override public void start() {
            mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
        }
        @Override public void stop() { mob.getNavigation().stop(); }
        @Override public void tick() {
            if (mob.distanceTo(target.x, target.y, target.z) > stopDistance)
                mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
            else mob.getNavigation().stop();
        }
    }
}

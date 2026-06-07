package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import com.example.fabricmod.entity.goal.DefendPlayerTargetGoal;
import com.example.fabricmod.entity.goal.FollowOwnerGoal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * RTS 命令处理器（单例模式）
 *
 * 通过原版 Minecraft API 实现生物指挥功能：
 * - 队伍管理（防止误伤）
 * - 选择单位
 * - 移动 / 攻击 / 跟随 / 停止 命令
 */
public class RtsCommandHandler {

    private static final RtsCommandHandler INSTANCE = new RtsCommandHandler();
    private static final String TEAM_PREFIX = "fabricmod_";

    private RtsCommandHandler() {}

    public static RtsCommandHandler getInstance() {
        return INSTANCE;
    }

    // ═══════════════════════════════════════════════
    //  ① Team Management — 队伍管理
    // ═══════════════════════════════════════════════
    //  Use vanilla Scoreboard team system to prevent friendly fire.
    //  Player → fabricmod_owner team
    //  Summoned mobs → fabricmod_summon team
    //  Both teams have friendlyFire=false, so they won't hurt each other.
    //  使用原版计分板队伍系统防止误伤。玩家和召唤物分属不同队伍，
    //  均关闭友军伤害（friendlyFire=false）。

    /**
     * Get or create the summon team (fabricmod_summon) on the scoreboard.
     * 获取或创建召唤物队伍。
     */
    public static net.minecraft.scoreboard.Team getOrCreateSummonTeam(ServerWorld world) {
        return INSTANCE._getOrCreateSummonTeam(world);
    }

    /**
     * Get or create the owner team (fabricmod_owner) for the player.
     * 获取或创建玩家（主人）队伍。
     */
    public static net.minecraft.scoreboard.Team getOrCreateOwnerTeam(ServerWorld world) {
        return INSTANCE._getOrCreateOwnerTeam(world);
    }

    /**
     * Add player to owner team — prevents summoned mobs from attacking them.
     * 将玩家加入 owner 队伍，防止召唤物攻击玩家。
     */
    public static void addPlayerToTeam(ServerPlayerEntity player) {
        INSTANCE._addPlayerToTeam(player);
    }

    /**
     * Add a summoned mob to the summon team — prevents friendly fire between mobs.
     * 将召唤生物加入 summon 队伍，防止召唤物互相误伤。
     */
    public static void addMobToTeam(MobEntity mob, ServerWorld world) {
        INSTANCE._addMobToTeam(mob, world);
    }

    // ────────── 实例方法 ──────────

    private net.minecraft.scoreboard.Team _getOrCreateSummonTeam(ServerWorld world) {
        Scoreboard scoreboard = world.getScoreboard();
        String teamName = TEAM_PREFIX + "summon";
        net.minecraft.scoreboard.Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.addTeam(teamName);
            team.setFriendlyFire(false);
            team.setShowFriendlyInvisibles(true);
        }
        return team;
    }

    private net.minecraft.scoreboard.Team _getOrCreateOwnerTeam(ServerWorld world) {
        Scoreboard scoreboard = world.getScoreboard();
        String teamName = TEAM_PREFIX + "owner";
        net.minecraft.scoreboard.Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.addTeam(teamName);
            team.setFriendlyFire(false);
            team.setShowFriendlyInvisibles(true);
        }
        return team;
    }

    private void _addPlayerToTeam(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        net.minecraft.scoreboard.Team team = _getOrCreateOwnerTeam(world);
        Scoreboard scoreboard = world.getScoreboard();
        String entryName = player.getName().getString();

        net.minecraft.scoreboard.Team current = scoreboard.getPlayerTeam(entryName);
        if (current != null && !current.getName().equals(team.getName())) {
            scoreboard.removePlayerFromTeam(entryName, current);
        }
        scoreboard.addPlayerToTeam(entryName, team);
    }

    private void _addMobToTeam(MobEntity mob, ServerWorld world) {
        net.minecraft.scoreboard.Team team = _getOrCreateSummonTeam(world);
        Scoreboard scoreboard = world.getScoreboard();
        String entryName = mob.getUuidAsString();

        net.minecraft.scoreboard.Team current = scoreboard.getTeam(entryName);
        if (current != null && !current.getName().equals(team.getName())) {
            scoreboard.removePlayerFromTeam(entryName, current);
        }
        scoreboard.addPlayerToTeam(entryName, team);
    }

    // ═══════════════════════════════════════════════
    //  ② Unit Selection — 选择单位
    // ═══════════════════════════════════════════════
    //  Scan for skeleton/zombie within 16 blocks of the player that belong to this mod.
    //  扫描玩家周围 16 格内的骷髅/僵尸，判断为本模组召唤的单位。

    public static List<MobEntity> selectNearbyMobs(ServerPlayerEntity player) {
        return INSTANCE._selectNearbyMobs(player);
    }

    private List<MobEntity> _selectNearbyMobs(ServerPlayerEntity player) {
        List<MobEntity> selected = new ArrayList<>();
        ServerWorld world = player.getServerWorld();

        for (Entity entity : world.getEntitiesByClass(MobEntity.class,
                player.getBoundingBox().expand(16),
                e -> (e.getType() == net.minecraft.entity.EntityType.SKELETON
                        || e.getType() == net.minecraft.entity.EntityType.ZOMBIE)
                        && e.isAlive())) {
            selected.add((MobEntity) entity);
        }

        player.sendMessage(Text.literal("§e[RTS] 选中了 §6" + selected.size() + " §e个单位"), false);
        return selected;
    }

    // ═══════════════════════════════════════════════
    //  ③ Commands — 指挥命令
    // ═══════════════════════════════════════════════
    //  All commands follow the same pattern:
    //  1. Static method → delegates to private instance method
    //  2. Clear old AI (goalSelector + targetSelector)
    //  3. Apply new behavior
    //  4. Send feedback message to player
    //
    //  所有命令遵循统一模式：
    //  静态方法 → 委托给实例方法 → 清除旧 AI → 应用新行为 → 发送反馈

    /**
     * Move command: clear all AI, bind RtsMoveGoal, march to target coordinates.
     * Mob will stop and idle after arrival (won't revert to follow).
     * 移动命令：清除旧 AI，绑定 RtsMoveGoal 走向目标点，到达后原地待命。
     */
    public static void commandMove(List<MobEntity> mobs, ServerPlayerEntity player, Vec3d target) {
        INSTANCE._commandMove(mobs, player, target);
    }

    /**
     * Attack command: clear all AI, set target, start pathfinding toward it.
     * 攻击命令：清除旧 AI，设置攻击目标并走向该实体。
     */
    public static void commandAttack(List<MobEntity> mobs, ServerPlayerEntity player, LivingEntity target) {
        INSTANCE._commandAttack(mobs, player, target);
    }

    /**
     * Follow command: rebind FollowOwnerGoal + DefendPlayerTargetGoal + FireResistance.
     * 跟随命令：重新绑定跟随 + 护卫 + 防火 AI。
     */
    public static void commandFollow(List<MobEntity> mobs, ServerPlayerEntity player) {
        INSTANCE._commandFollow(mobs, player);
    }

    /**
     * Stop command: clear all AI, stop navigation, clear target.
     * 停止命令：清除 AI，停下导航，清空目标。
     */
    public static void commandStop(List<MobEntity> mobs, ServerPlayerEntity player) {
        INSTANCE._commandStop(mobs, player);
    }

    /**
     * Clear all AI and target selectors, fully reset mob state.
     * 清空所有 AI 和目标选择器，完全重置生物状态。
     */
    public static void clearAI(List<MobEntity> mobs) {
        INSTANCE._clearAI(mobs);
    }

    // ────────── Instance implementations 实例实现 ──────────

    private void _commandMove(List<MobEntity> mobs, ServerPlayerEntity player, Vec3d target) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();               // Clear old movement AI  清除旧移动 AI
            mob.targetSelector.clear();             // Clear old targeting    清除旧目标选择器
            mob.setTarget(null);
            mob.goalSelector.add(1, new RtsMoveGoal(mob, target, 1.0));  // Bind move goal  绑定移动目标
        }
        player.sendMessage(Text.literal("§e[RTS] §6" + mobs.size() + " §e个单位正在移动"), false);
    }

    private void _commandAttack(List<MobEntity> mobs, ServerPlayerEntity player, LivingEntity target) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();               // Clear old AI  清除旧 AI
            mob.targetSelector.clear();
            mob.setTarget(target);                  // Set combat target  设置攻击目标
            mob.getNavigation().startMovingTo(target, 1.2);  // Chase  追击
        }
        player.sendMessage(
                Text.literal("§c[RTS] §6" + mobs.size() + " §c个单位正在攻击 §e" + target.getName().getString()),
                false
        );
    }

    private void _commandFollow(List<MobEntity> mobs, ServerPlayerEntity player) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();
            mob.targetSelector.clear();

            // Re-apply fire resistance (NBT/effect persists, but safety net)  重新确保防火
            mob.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE,
                    net.minecraft.entity.effect.StatusEffectInstance.INFINITE,
                    0, false, false, false
            ));

            mob.goalSelector.add(1, new FollowOwnerGoal(mob, player, 1.2, 2.0));   // Follow  跟随
            mob.targetSelector.add(1, new DefendPlayerTargetGoal(mob, player));    // Guard  护卫
        }
        player.sendMessage(Text.literal("§a[RTS] §6" + mobs.size() + " §a个单位切换为跟随模式"), false);
    }

    private void _commandStop(List<MobEntity> mobs, ServerPlayerEntity player) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();               // Remove all goals  移除所有目标
            mob.targetSelector.clear();
            mob.getNavigation().stop();             // Stop moving  停止移动
            mob.setTarget(null);                    // Clear aggro  清除仇恨
        }
        player.sendMessage(Text.literal("§7[RTS] §6" + mobs.size() + " §7个单位已停止"), false);
    }

    private void _clearAI(List<MobEntity> mobs) {
        if (mobs == null) return;
        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();               // Clear movement AI  清除移动 AI
            mob.targetSelector.clear();             // Clear targeting AI  清除目标选择器
            mob.getNavigation().stop();             // Stop navigation  停止导航
            mob.setTarget(null);                    // Clear combat target  清除战斗目标
            mob.setAttacker(null);                  // Clear last attacker  清除攻击者记录
            mob.setAttacking(null);                 // Clear attack target  清除正在攻击的目标
        }
    }

    // ═══════════════════════════════════════════════
    //  ④ Inner class: RtsMoveGoal 定点移动 AI
    // ═══════════════════════════════════════════════
    //  Moves the mob toward a fixed coordinate and stops on arrival.
    //  Unlike FollowOwnerGoal, it does NOT revert to following the player.
    //  Unlike vanilla move, it does NOT auto-attack along the way.
    //
    //  让生物走向固定坐标，到达后原地待命：
    //  - 不会退回跟随玩家（不同于 FollowOwnerGoal）
    //  - 不会沿途自动攻击（不同于原版移动）

    public static class RtsMoveGoal extends Goal {

        private final PathAwareEntity mob;
        private final Vec3d target;            // Destination  目标坐标
        private final double speed;            // Movement speed  移动速度
        private final double stopDistance;     // How close to stop  停止距离

        public RtsMoveGoal(PathAwareEntity mob, Vec3d target, double speed, double stopDistance) {
            this.mob = mob;
            this.target = target;
            this.speed = speed;
            this.stopDistance = stopDistance;
            this.setControls(EnumSet.of(Control.MOVE));  // Only MOVE, no LOOK  仅控制移动
        }

        public RtsMoveGoal(PathAwareEntity mob, Vec3d target, double speed) {
            this(mob, target, speed, 1.0);
        }

        @Override
        public boolean canStart() {
            // Start moving if still far from target  距离远则开始移动
            return mob.distanceTo(target.x, target.y, target.z) > stopDistance;
        }

        @Override
        public boolean shouldContinue() {
            // Keep running until arrived  持续运行直到到达
            return mob.distanceTo(target.x, target.y, target.z) > stopDistance;
        }

        @Override
        public void start() {
            mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();  // Arrived, stop  到达，停下
        }

        @Override
        public void tick() {
            // Re-issue move command each tick while far away  每帧重新下达移动指令
            if (mob.distanceTo(target.x, target.y, target.z) > stopDistance) {
                mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
            } else {
                mob.getNavigation().stop();
            }
        }
    }
}

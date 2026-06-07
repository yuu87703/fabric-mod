package com.example.fabricmod.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumSet;

/**
 * 仿马匹跟随逻辑：
 * - 以指定速度走向玩家
 * - 距离小于 stopDistance 时停止移动（原地待命）
 * - 距离超过 teleportDistance 时传送到玩家附近（类驯服马传送机制）
 * - 不强行锁定视角，保留生物自然转头行为
 */
public class FollowOwnerGoal extends Goal {

    private final MobEntity mob;
    private final PlayerEntity player;
    private final double speed;
    private final double stopDistance;
    private final double teleportDistance;
    private int cooldown;

    /**
     * @param mob              要跟随的生物
     * @param player           要跟随的玩家（主人）
     * @param speed            移动速度（如 1.2）
     * @param stopDistance     距离主人小于此值时停止移动
     * @param teleportDistance 距离超过此值时传送回主人身边（默认 12）
     */
    public FollowOwnerGoal(MobEntity mob, PlayerEntity player, double speed,
                            double stopDistance, double teleportDistance) {
        this.mob = mob;
        this.player = player;
        this.speed = speed;
        this.stopDistance = stopDistance;
        this.teleportDistance = teleportDistance;
        this.setControls(EnumSet.of(Control.MOVE));  // 不占用 LOOK，保留自然转头
    }

    public FollowOwnerGoal(MobEntity mob, PlayerEntity player, double speed, double stopDistance) {
        this(mob, player, speed, stopDistance, 12.0);
    }

    @Override
    public boolean canStart() {
        if (player == null || !player.isAlive()) return false;
        double dist = mob.distanceTo(player);
        return dist > stopDistance || dist > teleportDistance;
    }

    @Override
    public boolean shouldContinue() {
        return player != null && player.isAlive();
    }

    @Override
    public void start() {
        cooldown = 0;
        tryTeleport();
        if (mob.distanceTo(player) > stopDistance) {
            mob.getNavigation().startMovingTo(player, speed);
        }
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        cooldown = 0;
    }

    @Override
    public void tick() {
        double dist = mob.distanceTo(player);

        // —— 超距传送 ——
        if (dist > teleportDistance) {
            tryTeleport();
            return;
        }

        // —— 正常跟随/停步 ——
        if (dist > stopDistance) {
            mob.getNavigation().startMovingTo(player, speed);
        } else {
            mob.getNavigation().stop();
        }
    }

    /**
     * 尝试将生物传送至玩家附近（仿马匹传送逻辑）。
     */
    private void tryTeleport() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = 10;  // 传送冷却，防止高频传送

        if (!(mob.getWorld() instanceof ServerWorld serverWorld)) return;
        if (player == null || player.isRemoved()) return;

        // 寻找玩家周围 2~4 格的安全位置
        for (int attempt = 0; attempt < 8; attempt++) {
            double rad = mob.getRandom().nextDouble() * 2 * Math.PI;
            double r = 2 + mob.getRandom().nextDouble() * 2;  // 2~4 格
            double x = player.getX() + r * Math.cos(rad);
            double z = player.getZ() + r * Math.sin(rad);
            double y = player.getY();

            mob.refreshPositionAndAngles(x, y, z, mob.getYaw(), mob.getPitch());
            if (!serverWorld.isSpaceEmpty(mob)) continue;

            // 传送成功，触发传送粒子效果
            serverWorld.spawnParticles(
                    net.minecraft.particle.ParticleTypes.REVERSE_PORTAL,
                    mob.getX(), mob.getBodyY(0.5), mob.getZ(),
                    8, 0.5, 0.5, 0.5, 0.1
            );
            mob.getNavigation().stop();
            return;
        }
    }
}

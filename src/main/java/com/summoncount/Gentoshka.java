package com.summoncount;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Boss "Gentoshka". Health like a Warden (500).
 * - She never melees. Her target is the nearest living thing (players, animals, mobs...), not only players.
 * - Every 10 seconds: 75% summons mini Big Macs, 25% jumps and lands with a TNT-like explosion.
 * - She takes no damage from her own explosion, and her mini Big Macs take none either.
 * - Monsters and iron golems go after her (about half go for her minions while minions exist).
 */
public class Gentoshka extends IronGolem {
    private static final int ABILITY_INTERVAL_TICKS = 200; // 10 seconds
    private static final int MAX_MINIONS_NEARBY = 15;

    /** True only while Gentoshka's own slam explosion is being calculated. */
    private static boolean slamExplosionActive = false;

    private final ServerBossEvent bossEvent = new ServerBossEvent(
            Component.translatable("entity.summoncount.gentoshka"),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS);

    private LivingEntity currentTarget = null;
    private int abilityTimer = 0;
    private boolean slamming = false;
    private int slamTicks = 0;

    public Gentoshka(EntityType<? extends IronGolem> type, Level level) {
        super(type, level);
    }

    public static boolean isSlamExplosionActive() {
        return slamExplosionActive;
    }

    public static AttributeSupplier.Builder bossAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 500.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 40.0D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.SCALE, 1.3D);
    }

    @Override
    protected void registerGoals() {
        // She does not melee anyone, so no attack goals.
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 24.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    public void aiStep() {
        super.aiStep();

        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());

        // make monsters and golems come for her
        if (this.tickCount % 10 == 0) {
            this.provokeMobs(serverLevel);
        }

        // pick the nearest living thing as her target
        if (this.currentTarget == null || !this.currentTarget.isAlive() || this.tickCount % 10 == 0) {
            this.currentTarget = this.findTarget(serverLevel);
        }
        LivingEntity target = this.currentTarget;
        if (target == null) {
            this.abilityTimer = 0;
            return;
        }

        if (this.slamming) {
            this.handleSlam(serverLevel);
            return;
        }

        // slowly walk closer to the target, but do not attack
        if (this.tickCount % 10 == 0 && this.distanceToSqr(target) > 36.0D) {
            this.getNavigation().moveTo(target, 1.0D);
        }
        this.getLookControl().setLookAt(target, 30.0F, 30.0F);

        this.abilityTimer++;
        if (this.abilityTimer >= ABILITY_INTERVAL_TICKS) {
            this.abilityTimer = 0;
            if (this.random.nextFloat() < 0.75F) {
                this.summonMinions(serverLevel, target);
            } else {
                this.startSlam(target);
            }
        }
    }

    private LivingEntity findTarget(ServerLevel level) {
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(40.0D),
                e -> e != this
                        && e.isAlive()
                        && !(e instanceof Gentoshka)
                        && !(e instanceof MiniBigMac)
                        && !(e instanceof Player p && p.isSpectator())
                        && (e instanceof Mob || e instanceof Player));

        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            double distance = this.distanceToSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Monsters and iron golems make her their priority target.
     * While minions exist, about half of them go for the nearest minion instead.
     */
    private void provokeMobs(ServerLevel level) {
        List<MiniBigMac> minions = level.getEntitiesOfClass(MiniBigMac.class, this.getBoundingBox().inflate(48.0D));
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, this.getBoundingBox().inflate(48.0D),
                m -> (m instanceof Monster || m instanceof IronGolem)
                        && !(m instanceof MiniBigMac)
                        && !(m instanceof Gentoshka));

        for (Mob mob : mobs) {
            LivingEntity wanted = this;
            if (!minions.isEmpty() && mob.getId() % 2 == 0) {
                MiniBigMac nearest = null;
                double bestDistance = Double.MAX_VALUE;
                for (MiniBigMac minion : minions) {
                    double distance = mob.distanceToSqr(minion);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        nearest = minion;
                    }
                }
                if (nearest != null) {
                    wanted = nearest;
                }
            }
            if (mob.getTarget() != wanted) {
                mob.setTarget(wanted);
            }
        }
    }

    private void summonMinions(ServerLevel level, LivingEntity target) {
        int existing = level.getEntitiesOfClass(MiniBigMac.class, this.getBoundingBox().inflate(48.0D)).size();
        if (existing >= MAX_MINIONS_NEARBY) {
            return;
        }

        int count = 3 + this.random.nextInt(3); // 3-5
        for (int i = 0; i < count; i++) {
            MiniBigMac mini = new MiniBigMac(SummonCountMod.MINI_BIGMAC.get(), level);
            double angle = this.random.nextDouble() * Math.PI * 2.0D;
            double distance = 2.5D + this.random.nextDouble() * 1.5D;
            mini.setPos(this.getX() + Math.cos(angle) * distance, this.getY(), this.getZ() + Math.sin(angle) * distance);
            mini.setTarget(target);
            level.addFreshEntity(mini);
        }
    }

    private void startSlam(LivingEntity target) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        double motionX = length > 0.001D ? dx / length * 0.5D : 0.0D;
        double motionZ = length > 0.001D ? dz / length * 0.5D : 0.0D;

        this.getNavigation().stop();
        this.setDeltaMovement(new Vec3(motionX, 1.1D, motionZ));
        this.slamming = true;
        this.slamTicks = 0;
    }

    private void handleSlam(ServerLevel level) {
        this.slamTicks++;
        if (this.slamTicks > 5 && this.onGround()) {
            slamExplosionActive = true;
            try {
                level.explode(this, this.getX(), this.getY(), this.getZ(), 4.0F, Level.ExplosionInteraction.MOB);
            } finally {
                slamExplosionActive = false;
            }
            this.slamming = false;
        } else if (this.slamTicks > 100) {
            this.slamming = false;
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        // no fall damage, and no damage from her own explosion (other explosions still hurt her)
        if (source.is(DamageTypeTags.IS_FALL)) {
            return false;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)
                && (slamExplosionActive || source.getDirectEntity() == this || source.getEntity() == this)) {
            return false;
        }
        return super.hurtServer(level, source, amount);
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }
}

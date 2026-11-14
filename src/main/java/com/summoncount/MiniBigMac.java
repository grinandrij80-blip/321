package com.summoncount;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Mini Big Mac: small, 25 HP, 2 damage, fast like a spider.
 * Attacks any living thing (players, mobs, animals) except Gentoshka and other mini Big Macs.
 * Takes no damage from Gentoshka's slam explosion.
 */
public class MiniBigMac extends Spider {

    public MiniBigMac(EntityType<? extends Spider> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder miniAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 25.0D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.SCALE, 0.6D);
    }

    @Override
    protected void registerGoals() {
        // Targets are chosen in aiStep(), so there are no target goals here.
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
    }

    @Override
    public void setTarget(LivingEntity target) {
        // never go after the boss or each other
        if (target instanceof Gentoshka || target instanceof MiniBigMac) {
            return;
        }
        super.setTarget(target);
    }

    @Override
    public void aiStep() {
        super.aiStep();

        if (!(this.level() instanceof ServerLevel level) || this.tickCount % 10 != 0) {
            return;
        }

        LivingEntity current = this.getTarget();
        if (current != null && current.isAlive()) {
            return;
        }

        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(24.0D),
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
        if (best != null) {
            this.setTarget(best);
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        // Gentoshka's slam explosion does not hurt her minions
        if (source.is(DamageTypeTags.IS_EXPLOSION)
                && (Gentoshka.isSlamExplosionActive()
                    || source.getDirectEntity() instanceof Gentoshka
                    || source.getEntity() instanceof Gentoshka)) {
            return false;
        }
        return super.hurtServer(level, source, amount);
    }
}

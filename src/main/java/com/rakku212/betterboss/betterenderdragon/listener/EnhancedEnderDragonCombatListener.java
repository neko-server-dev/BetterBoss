package com.rakku212.betterboss.betterenderdragon.listener;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.block.Block;
import org.bukkit.boss.DragonBattle;

import com.rakku212.betterboss.betterenderdragon.manager.BetterEnderDragonCrystalManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class EnhancedEnderDragonCombatListener implements Listener {

    private static final double ENDER_DRAGON_OUTGOING_ATTACK_MULTIPLIER_PHASE1 = 1.5;
    private static final double ENDER_DRAGON_OUTGOING_ATTACK_MULTIPLIER_PHASE2 = 2.6;
    private static final int DRAGON_BREATH_POISON_DURATION_TICKS = 10 * 20;

    private static final double ENHANCED_DRAGON_DEATH_XP_MULT_MIN = 1.3;
    private static final double ENHANCED_DRAGON_DEATH_XP_MULT_MAX = 1.8;

    private static final int ENHANCED_ENDER_DRAGON_HP_MIN = 300;
    private static final int ENHANCED_ENDER_DRAGON_HP_MAX = 450;
    private static final int CENTRAL_BEDROCK_SCAN_HORIZONTAL_RADIUS = 4;

    private final JavaPlugin plugin;
    private final BetterEnderDragonCrystalManager crystalManager;
    private final Map<UUID, UUID> enhancedDragonRandomHpAppliedEntityByWorld = new ConcurrentHashMap<>();

    public EnhancedEnderDragonCombatListener(JavaPlugin plugin, BetterEnderDragonCrystalManager crystalManager) {
        this.plugin = plugin;
        this.crystalManager = crystalManager;
    }

    @EventHandler
    public void onEnhancedEnderDragonSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!crystalManager.isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyRandomMaxHealthToEnhancedDragon(dragon), 1L);
    }

    @EventHandler
    public void onOutgoingDragonAttackDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (victim instanceof EnderDragon) {
            return;
        }
        World world = victim.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!crystalManager.isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        if (!isEnderDragonOriginatedAttack(event)) {
            return;
        }
        if (victim instanceof Enderman) {
            event.setCancelled(true);
            return;
        }
        double damage = event.getDamage();
        if (damage <= 0) {
            return;
        }
        double outgoingAttackMultiplier = crystalManager.isEnhancedDragonPhase2(world.getUID())
                ? ENDER_DRAGON_OUTGOING_ATTACK_MULTIPLIER_PHASE2
                : ENDER_DRAGON_OUTGOING_ATTACK_MULTIPLIER_PHASE1;
        event.setCancelled(true);
        if (isDragonBreathDamage(event)) {
            victim.addPotionEffect(new PotionEffect(
                    PotionEffectType.POISON,
                    DRAGON_BREATH_POISON_DURATION_TICKS,
                    0,
                    false,
                    false,
                    false
            ));
        }
        victim.damage(damage * outgoingAttackMultiplier);
    }

    @EventHandler
    public void onDragonDamagePhaseTransitionInvulnerable(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!crystalManager.isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        if (crystalManager.isEnhancedDragonPhaseTransition(world.getUID())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEnhancedDragonDamageBeforeApply(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        World world = dragon.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!crystalManager.isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        if (crystalManager.isEnhancedDragonPhaseTransition(world.getUID())
                || crystalManager.isEnhancedDragonPhase2(world.getUID())) {
            return;
        }
        AttributeInstance maxHealthAttr = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) {
            return;
        }
        double originalMax = crystalManager.getEnhancedDragonBossOriginalMaxHealth(world.getUID());
        double halfThreshold = originalMax > 0 ? originalMax * 0.5 : maxHealthAttr.getValue() * 0.5;
        if (halfThreshold <= 0) {
            return;
        }
        double currentHealth = dragon.getHealth();
        if (currentHealth <= halfThreshold) {
            return;
        }
        double finalDamage = event.getFinalDamage();
        if (finalDamage <= 0) {
            return;
        }
        if (currentHealth - finalDamage <= halfThreshold) {
            crystalManager.onEnhancedEnderDragonHealthChanged(dragon);
        }
    }

    @EventHandler
    public void onEnhancedDragonHealthAfterDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        if (!dragon.isValid() || dragon.isDead()) {
            return;
        }
        World world = dragon.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!crystalManager.isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        crystalManager.onEnhancedEnderDragonHealthChanged(dragon);
    }

    @EventHandler
    public void onDragonDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!crystalManager.isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        if (crystalManager.isEnhancedDragonPhaseTransition(world.getUID())) {
            return;
        }
        AttributeInstance maxHealthAttr = dragon.getAttribute(Attribute.MAX_HEALTH); 
        if (maxHealthAttr == null) {
            return;
        }
        double max = maxHealthAttr.getValue();
        if (max <= 0) {
            return;
        }
        double originalMax = crystalManager.getEnhancedDragonBossOriginalMaxHealth(world.getUID());
        double halfThreshold = originalMax > 0 ? originalMax * 0.5 : max * 0.5;
        if (dragon.getHealth() > halfThreshold) {
            return;
        }
        if (isArrowLikeProjectileDamage(event) || isExplosionDamage(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEnhancedEnderDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!crystalManager.isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        enhancedDragonRandomHpAppliedEntityByWorld.remove(world.getUID());
        crystalManager.stopEnhancedFightPillarRegenAfterDragonDefeat(world);
        crystalManager.resetEnhancedDragonBossPhase(world.getUID());
        spawnRewardChestOnCentralBedrock(world);
        int base = event.getDroppedExp();
        if (base <= 0) {
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double mult = ENHANCED_DRAGON_DEATH_XP_MULT_MIN
                + rng.nextDouble() * (ENHANCED_DRAGON_DEATH_XP_MULT_MAX - ENHANCED_DRAGON_DEATH_XP_MULT_MIN);
        int scaled = (int) Math.round(base * mult);
        event.setDroppedExp(Math.max(scaled, base));
    }

    private static void spawnRewardChestOnCentralBedrock(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null) {
            return;
        }
        Location portal = battle.getEndPortalLocation();
        if (portal == null || portal.getWorld() == null) {
            return;
        }
        int cx = portal.getBlockX();
        int cz = portal.getBlockZ();
        Block topBedrock = null;
        int bestY = Integer.MIN_VALUE;
        for (int x = cx - CENTRAL_BEDROCK_SCAN_HORIZONTAL_RADIUS; x <= cx + CENTRAL_BEDROCK_SCAN_HORIZONTAL_RADIUS; x++) {
            for (int z = cz - CENTRAL_BEDROCK_SCAN_HORIZONTAL_RADIUS; z <= cz + CENTRAL_BEDROCK_SCAN_HORIZONTAL_RADIUS; z++) {
                for (int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.BEDROCK) {
                        continue;
                    }
                    if (y > bestY) {
                        bestY = y;
                        topBedrock = block;
                    }
                    break;
                }
            }
        }
        Location chestLoc = (topBedrock != null ? topBedrock.getLocation() : portal).add(0, 1, 0);
        Block chestBlock = chestLoc.getBlock();
        if (chestBlock.getType() != Material.CHEST) {
            chestBlock.setType(Material.CHEST, false);
        }
    }

    private void applyRandomMaxHealthToEnhancedDragon(EnderDragon dragon) {
        if (dragon == null || !dragon.isValid()) {
            return;
        }
        World world = dragon.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!crystalManager.isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        UUID wid = world.getUID();
        UUID eid = dragon.getUniqueId();
        UUID already = enhancedDragonRandomHpAppliedEntityByWorld.get(wid);
        if (eid.equals(already)) {
            return;
        }
        double persistedOriginalMax = crystalManager.getEnhancedDragonBossOriginalMaxHealth(wid);
        if (persistedOriginalMax > 0) {
            if (crystalManager.isEnhancedDragonPhase2(wid)) {
                AttributeInstance phase2Attr = dragon.getAttribute(Attribute.MAX_HEALTH);
                if (phase2Attr != null) {
                    double phase2Pool = persistedOriginalMax * 0.5;
                    phase2Attr.setBaseValue(phase2Pool);
                    if (dragon.getHealth() > phase2Pool) {
                        dragon.setHealth(phase2Pool);
                    }
                    crystalManager.refreshEnhancedDragonBossBarCache(dragon);
                    enhancedDragonRandomHpAppliedEntityByWorld.put(wid, eid);
                    return;
                }
            }
            if (crystalManager.isEnhancedDragonPhaseTransition(wid)) {
                double half = persistedOriginalMax * 0.5;
                if (dragon.getHealth() > half) {
                    dragon.setHealth(half);
                }
                dragon.setInvulnerable(true);
                crystalManager.refreshEnhancedDragonBossBarCache(dragon);
                enhancedDragonRandomHpAppliedEntityByWorld.put(wid, eid);
                return;
            }
        }
        crystalManager.resetEnhancedDragonBossPhase(wid);
        AttributeInstance attr = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return;
        }
        int hp = ThreadLocalRandom.current().nextInt(ENHANCED_ENDER_DRAGON_HP_MIN, ENHANCED_ENDER_DRAGON_HP_MAX + 1);
        attr.setBaseValue(hp);
        dragon.setHealth(attr.getValue());
        crystalManager.seedEnhancedDragonBossPhaseOriginalMax(dragon, hp);
        crystalManager.refreshEnhancedDragonBossBarCache(dragon);
        enhancedDragonRandomHpAppliedEntityByWorld.put(wid, eid);
    }

    private static boolean isExplosionDamage(EntityDamageEvent event) {
        return switch (event.getCause()) {
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> true;
            default -> false;
        };
    }

    private static boolean isArrowLikeProjectileDamage(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent by)) {
            return false;
        }
        return by.getDamager() instanceof AbstractArrow;
    }

    private static boolean isEnderDragonOriginatedAttack(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent by) {
            return damagerChainIndicatesEnderDragon(by.getDamager());
        }
        DamageSource source = event.getDamageSource();
        if (source.getDamageType() == DamageType.DRAGON_BREATH) {
            Entity causing = source.getCausingEntity();
            if (causing instanceof EnderDragon) {
                return true;
            }
            Entity direct = source.getDirectEntity();
            return direct instanceof AreaEffectCloud cloud && cloud.getSource() instanceof EnderDragon;
        }
        return false;
    }

    private static boolean isDragonBreathDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent by) {
            Entity damager = by.getDamager();
            if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof EnderDragon) {
                return true;
            }
            if (damager instanceof DragonFireball fireball && fireball.getShooter() instanceof EnderDragon) {
                return true;
            }
        }
        DamageSource source = event.getDamageSource();
        if (source.getDamageType() == DamageType.DRAGON_BREATH) {
            return true;
        }
        Entity direct = source.getDirectEntity();
        if (direct instanceof AreaEffectCloud cloud && cloud.getSource() instanceof EnderDragon) {
            return true;
        }
        Entity causing = source.getCausingEntity();
        return causing instanceof EnderDragon
                && (direct instanceof DragonFireball || direct instanceof AreaEffectCloud);
    }

    private static boolean damagerChainIndicatesEnderDragon(Entity damager) {
        if (damager instanceof EnderDragon) {
            return true;
        }
        if (damager instanceof AreaEffectCloud cloud) {
            return cloud.getSource() instanceof EnderDragon;
        }
        if (damager instanceof DragonFireball fireball) {
            return fireball.getShooter() instanceof EnderDragon;
        }
        if (damager instanceof Projectile projectile) {
            return projectile.getShooter() instanceof EnderDragon;
        }
        return false;
    }
}

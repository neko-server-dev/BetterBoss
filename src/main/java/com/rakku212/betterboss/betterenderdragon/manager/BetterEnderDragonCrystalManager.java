package com.rakku212.betterboss.betterenderdragon.manager;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.rakku212.betterboss.betterenderdragon.display.CrystalHeartDisplayFactory;
import com.rakku212.betterboss.betterenderdragon.item.EnhancedEndCrystalItem;
import com.rakku212.betterboss.betterenderdragon.packet.EnhancedDragonBossBarPacketListener;
import com.rakku212.betterboss.betterenderdragon.packet.EnhancedDragonBossPhaseTracker;
import com.rakku212.betterboss.betterenderdragon.persistence.EnhancedDragonFightWorldStore;
import com.rakku212.betterboss.betterenderdragon.respawn.EnderDragonRespawnWatcher;
import com.rakku212.betterboss.betterenderdragon.ritual.EnhancedDragonRitualEvaluator;
import com.rakku212.util.combat.PlayerOriginatedAttacks;
import com.rakku212.util.persistentdata.PersistentDataFlags;
import com.rakku212.util.state.BossHeartStackState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class BetterEnderDragonCrystalManager {

    private static final long RESCAN_FOLLOWUP_DELAY_TICKS = 15L;

    private static final long PILLAR_CRYSTAL_REGEN_INTERVAL_MS = 3L * 60L * 1000L;
    private static final long PILLAR_CRYSTAL_REGEN_INTERVAL_PHASE2_MS = 60L * 1000L;
    private static final int SECOND_PHASE_DARKNESS_DURATION_TICKS = 7 * 20;
    private static final int SECOND_PHASE_REGENERATION_DURATION_TICKS = 5 * 20;
    private static final double PILLAR_CRYSTAL_NEAR_RADIUS_SQ = 2.5 * 2.5;

    private static final long LOW_HEALTH_ENDERMAN_AGGRO_PERIOD_TICKS = 80L;
    private static final double LOW_HEALTH_ENDERMAN_AGGRO_WAVE_CHANCE = 0.35;
    private static final int LOW_HEALTH_ENDERMAN_AGGRO_MIN_PER_WAVE = 1;
    private static final int LOW_HEALTH_ENDERMAN_AGGRO_MAX_PER_WAVE = 4;
    private static final double LOW_HEALTH_ENDERMAN_NEAR_PLAYER_RADIUS_SQ = 64.0 * 64.0;

    private final JavaPlugin plugin;
    private final EnhancedDragonFightWorldStore enhancedDragonFightWorldStore;
    private final NamespacedKey playerPlacedCrystalKey;
    private final NamespacedKey enhancedRitualCrystalKey;
    private final NamespacedKey heartPillarCrystalKey;
    private final CrystalHeartDisplayFactory displayFactory = new CrystalHeartDisplayFactory();
    private final EnderDragonRespawnWatcher respawnWatcher;
    private final EnhancedDragonBossPhaseTracker enhancedDragonBossPhaseTracker;
    private final Map<UUID, BossHeartStackState> crystalStates = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingEnhancedEndCrystalPlacement = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> enhancedPillarHeartsByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> enhancedPillarPeriodicRegenByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Location>> pillarRegenSlotsByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPillarRegenCheckWallMs = new ConcurrentHashMap<>();

    public BetterEnderDragonCrystalManager(JavaPlugin plugin, EnhancedDragonFightWorldStore enhancedDragonFightWorldStore) {
        this.plugin = plugin;
        this.enhancedDragonFightWorldStore = enhancedDragonFightWorldStore;
        this.playerPlacedCrystalKey = new NamespacedKey(plugin, "player_placed_end_crystal");
        this.enhancedRitualCrystalKey = new NamespacedKey(plugin, "enhanced_ritual_end_crystal");
        this.heartPillarCrystalKey = new NamespacedKey(plugin, "heart_pillar_end_crystal");
        this.respawnWatcher = new EnderDragonRespawnWatcher(
                this::clearAllCrystalTracking,
                this::rescanUntrackedEndCrystalsWithFollowUp,
                this::isPlayerPlaced,
                this::onDragonRespawnEntering,
                this::onDragonRespawnIdle
        );
        this.enhancedDragonBossPhaseTracker = new EnhancedDragonBossPhaseTracker(this);
        enhancedDragonFightWorldStore.loadAllPersistedAsync(
                enhancedPillarPeriodicRegenByWorld,
                lastPillarRegenCheckWallMs,
                map -> enhancedDragonBossPhaseTracker.restorePersistedStates(map)
        );
        Bukkit.getScheduler().runTaskTimer(plugin, enhancedDragonBossPhaseTracker::tickEveryWorld, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            respawnWatcher.tick();
            tickDisplays();
            tickPeriodicPillarCrystalRegen();
        }, 2L, 2L);
        Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tickLowHealthEndermanAggro,
                LOW_HEALTH_ENDERMAN_AGGRO_PERIOD_TICKS,
                LOW_HEALTH_ENDERMAN_AGGRO_PERIOD_TICKS
        );
        Bukkit.getScheduler().runTaskLater(plugin, this::rescanUntrackedEndCrystals, 1L);
    }

    /**
     * ボスバー送信用リスナーをフェーズトラッカーに結線する（{@link net.point7845.nisetikuwa.bootstrap.feature.FeatureBootstrap} から）
     */
    public void attachBossBarPacketListener(EnhancedDragonBossBarPacketListener listener) {
        enhancedDragonBossPhaseTracker.setBossBarPacketListener(listener);
    }

    /**
     * エンドクリスタルをブロックに対して右クリックしたとき
     * 設置成否に先立ち強化アイテムか記録する
     */
    public void onPlayerInteractEndCrystalPlacement(UUID playerId, ItemStack item) {
        if (playerId == null || item == null || item.getType() != Material.END_CRYSTAL) {
            return;
        }
        recordPendingEnhancedEndCrystalPlacement(playerId, EnhancedEndCrystalItem.isEnhanced(item, plugin));
    }

    /**
     * 強化エンドクリスタルを置こうとした直後に呼ぶ（{@link org.bukkit.event.player.PlayerInteractEvent}）
     */
    public void recordPendingEnhancedEndCrystalPlacement(UUID playerId, boolean enhancedItem) {
        if (playerId == null) {
            return;
        }
        pendingEnhancedEndCrystalPlacement.put(playerId, enhancedItem);
        Bukkit.getScheduler().runTaskLater(plugin, () -> pendingEnhancedEndCrystalPlacement.remove(playerId), 15L);
    }

    public Boolean takePendingEnhancedPlacement(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        return pendingEnhancedEndCrystalPlacement.remove(playerId);
    }

    private void onDragonRespawnEntering(World world, DragonBattle battle) {
        pillarRegenSlotsByWorld.remove(world.getUID());
        boolean all = EnhancedDragonRitualEvaluator.areAllFourRitualCrystalsEnhanced(
                world,
                battle,
                enhancedRitualCrystalKey
        );
        enhancedPillarHeartsByWorld.put(world.getUID(), all);
        setEnhancedPeriodicRegenActive(world.getUID(), all);
    }

    private void onDragonRespawnIdle(World world) {
        enhancedPillarHeartsByWorld.remove(world.getUID());
        if (Boolean.TRUE.equals(enhancedPillarPeriodicRegenByWorld.get(world.getUID()))) {
            lastPillarRegenCheckWallMs.put(world.getUID(), System.currentTimeMillis());
        }
    }

    public boolean isEnhancedDragonFightWorld(UUID worldUid) {
        return Boolean.TRUE.equals(enhancedPillarPeriodicRegenByWorld.get(worldUid));
    }

    public void syncEnhancedRitualFlagsDuringRespawn(World world, DragonBattle battle) {
        if (world == null || world.getEnvironment() != World.Environment.THE_END || battle == null) {
            return;
        }
        if (battle.getRespawnPhase() == DragonBattle.RespawnPhase.NONE) {
            return;
        }
        UUID wid = world.getUID();
        if (Boolean.TRUE.equals(enhancedPillarPeriodicRegenByWorld.get(wid))) {
            return;
        }
        boolean all = EnhancedDragonRitualEvaluator.areAllFourRitualCrystalsEnhanced(
                world,
                battle,
                enhancedRitualCrystalKey
        );
        enhancedPillarHeartsByWorld.put(wid, all);
        setEnhancedPeriodicRegenActive(wid, all);
    }

    private void setEnhancedPeriodicRegenActive(UUID worldId, boolean active) {
        if (active) {
            enhancedPillarPeriodicRegenByWorld.put(worldId, true);
        } else {
            enhancedPillarPeriodicRegenByWorld.remove(worldId);
            lastPillarRegenCheckWallMs.remove(worldId);
        }
        enhancedDragonFightWorldStore.setActive(worldId, active);
    }

    /** 強化戦フラグが立ち、エンドのドラゴン償還シーケンス進行中なら true（柱クリスタル復元〜召喚まで） */
    public boolean isEnhancedEnderDragonRespawnActive(World world) {
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return false;
        }
        if (!isEnhancedDragonFightWorld(world.getUID())) {
            return false;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        return battle != null && battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE;
    }

    public void resetEnhancedDragonBossPhase(UUID worldId) {
        enhancedDragonBossPhaseTracker.reset(worldId);
    }

    public void saveEnhancedDragonBossPhase(UUID worldId, String phaseName, double originalMaxHealth) {
        if (worldId == null || !isEnhancedDragonFightWorld(worldId)) {
            return;
        }
        enhancedDragonFightWorldStore.saveBossPhase(worldId, phaseName, originalMaxHealth);
    }

    public void stopEnhancedFightPillarRegenAfterDragonDefeat(World world) {
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID wid = world.getUID();
        pillarRegenSlotsByWorld.remove(wid);
        lastPillarRegenCheckWallMs.remove(wid);
        enhancedPillarHeartsByWorld.remove(wid);
        setEnhancedPeriodicRegenActive(wid, false);
    }

    public void seedEnhancedDragonBossPhaseOriginalMax(EnderDragon dragon, double originalMax) {
        enhancedDragonBossPhaseTracker.seedOriginalMaxHealth(dragon, originalMax);
    }

    public double getEnhancedDragonBossOriginalMaxHealth(UUID worldId) {
        return enhancedDragonBossPhaseTracker.getOriginalMaxHealth(worldId);
    }

    public boolean isEnhancedDragonPhaseTransition(UUID worldId) {
        return enhancedDragonBossPhaseTracker.isTransitioning(worldId);
    }

    public boolean isEnhancedDragonPhase2(UUID worldId) {
        return enhancedDragonBossPhaseTracker.isPhase2(worldId);
    }

    public void onEnhancedEnderDragonHealthChanged(EnderDragon dragon) {
        enhancedDragonBossPhaseTracker.onDragonHealthChanged(dragon);
        enhancedDragonBossPhaseTracker.refreshBossBarCacheFor(dragon);
    }

    public void refreshEnhancedDragonBossBarCache(EnderDragon dragon) {
        enhancedDragonBossPhaseTracker.refreshBossBarCacheFor(dragon);
    }

    public float getCachedEnhancedDragonBossBarHealth(UUID worldId) {
        return enhancedDragonBossPhaseTracker.getCachedBossBarHealthFraction(worldId);
    }

    public void restorePillarCrystalsForSecondPhase(World world) {
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null || battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) {
            return;
        }
        clearAllCrystalTracking();
        battle.resetCrystals();
        UUID wid = world.getUID();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World w = Bukkit.getWorld(wid);
            if (w == null || w.getEnvironment() != World.Environment.THE_END) {
                return;
            }
            if (!isEnhancedDragonFightWorld(wid)) {
                return;
            }
            DragonBattle b = w.getEnderDragonBattle();
            if (b == null || b.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) {
                return;
            }
            EnderDragon dragon = b.getEnderDragon();
            if (dragon == null || !dragon.isValid() || dragon.isDead() || dragon.getHealth() <= 0) {
                return;
            }
            if (!enhancedDragonBossPhaseTracker.isPhase2(wid)) {
                return;
            }
            replenishPillarCrystalsFromSavedSlots(w);
            rescanUntrackedEndCrystalsWithFollowUp();
        }, 1L);
    }

    private void replenishPillarCrystalsFromSavedSlots(World world) {
        Map<String, Location> slots = pillarRegenSlotsByWorld.get(world.getUID());
        if (slots == null || slots.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(slots.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            Location spawnAt = slots.get(key);
            if (spawnAt == null || spawnAt.getWorld() == null || !spawnAt.getWorld().equals(world)) {
                continue;
            }
            EnderCrystal existing = findClosestNonPlayerPlacedCrystalNear(spawnAt);
            if (existing != null) {
                if (!PersistentDataFlags.hasByteFlag(existing, heartPillarCrystalKey)) {
                    PersistentDataFlags.setByteFlag(existing, heartPillarCrystalKey, true);
                }
                ensureCrystalTracked(existing);
                continue;
            }
            EnderCrystal spawned = world.spawn(spawnAt, EnderCrystal.class);
            spawned.setShowingBottom(true);
            PersistentDataFlags.setByteFlag(spawned, heartPillarCrystalKey, true);
            ensureCrystalTracked(spawned);
        }
    }

    private EnderCrystal findClosestNonPlayerPlacedCrystalNear(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }
        EnderCrystal best = null;
        double bestSq = Double.MAX_VALUE;
        for (EnderCrystal c : world.getEntitiesByClass(EnderCrystal.class)) {
            if (!c.isValid() || isPlayerPlaced(c)) {
                continue;
            }
            double d = c.getLocation().distanceSquared(center);
            if (d <= PILLAR_CRYSTAL_NEAR_RADIUS_SQ && d < bestSq) {
                bestSq = d;
                best = c;
            }
        }
        return best;
    }

    public void applyEnhancedDragonPhaseTransitionEffects(World world) {
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        int ticks = EnhancedDragonBossPhaseTracker.TRANSITION_DURATION_TICKS;
        for (Player player : world.getPlayers()) {
            if (!player.isValid()) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.LEVITATION,
                    ticks,
                    0,
                    false,
                    true,
                    true
            ));
        }
    }

    public void applySecondPhaseEnterEffects(World world) {
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!isEnhancedDragonFightWorld(world.getUID())) {
            return;
        }
        lastPillarRegenCheckWallMs.put(world.getUID(), System.currentTimeMillis());
        for (Player player : world.getPlayers()) {
            if (!player.isValid()) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.DARKNESS,
                    SECOND_PHASE_DARKNESS_DURATION_TICKS,
                    0,
                    false,
                    true,
                    true
            ));
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.REGENERATION,
                    SECOND_PHASE_REGENERATION_DURATION_TICKS,
                    0,
                    false,
                    true,
                    true
            ));
        }
    }

    private boolean isEnhancedPillarHeartsActive(World world) {
        return Boolean.TRUE.equals(enhancedPillarHeartsByWorld.get(world.getUID()));
    }

    public void onPlayerPlacedEndCrystal(EnderCrystal crystal, Boolean pendingEnhancedFromItem) {
        PersistentDataFlags.setByteFlag(crystal, playerPlacedCrystalKey, true);
        boolean enhanced = Boolean.TRUE.equals(pendingEnhancedFromItem);
        if (enhanced) {
            PersistentDataFlags.setByteFlag(crystal, enhancedRitualCrystalKey, true);
        }
        BossHeartStackState removed = crystalStates.remove(crystal.getUniqueId());
        if (removed != null) {
            CrystalHeartDisplayFactory.removeDisplayEntity(plugin, removed.displayId);
        }
    }

    public void tickDisplays() {
        Iterator<Map.Entry<UUID, BossHeartStackState>> it = crystalStates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BossHeartStackState> entry = it.next();
            UUID crystalId = entry.getKey();
            BossHeartStackState state = entry.getValue();
            Entity crystalEntity = Bukkit.getEntity(crystalId);
            if (!(crystalEntity instanceof EnderCrystal crystal) || !crystal.isValid()) {
                CrystalHeartDisplayFactory.removeDisplayEntity(plugin, state.displayId);
                it.remove();
                continue;
            }
            if (!shouldUseHeartSystem(crystal)) {
                CrystalHeartDisplayFactory.removeDisplayEntity(plugin, state.displayId);
                it.remove();
                continue;
            }
            if (state.heartsRemaining <= 0) {
                continue;
            }
            TextDisplay display = displayFactory.resolveOrRespawnDisplay(plugin, crystal, state);
            if (display != null) {
                displayFactory.teleportDisplayAboveCrystal(display, crystal);
            }
        }
    }

    public void onEndCrystalSpawned(EnderCrystal crystal) {
        if (!isEndWorld(crystal.getWorld())) {
            return;
        }
        tryTagHeartPillarIfNeeded(crystal);
        if (shouldUseHeartSystem(crystal)) {
            ensureCrystalTracked(crystal);
        }
    }

    public void onEndChunkLoaded(World world, Entity[] entities) {
        if (!isEndWorld(world)) {
            return;
        }
        for (Entity entity : entities) {
            if (entity instanceof EnderCrystal crystal) {
                tryTagHeartPillarIfNeeded(crystal);
                if (shouldUseHeartSystem(crystal)) {
                    ensureCrystalTracked(crystal);
                }
            }
        }
    }

    public void scheduleDelayedEndChunkRescan(World world, int chunkX, int chunkZ) {
        if (!isEndWorld(world)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return;
            }
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            onEndChunkLoaded(world, chunk.getEntities());
        }, 1L);
    }

    private void rescanUntrackedEndCrystalsWithFollowUp() {
        rescanUntrackedEndCrystals();
        Bukkit.getScheduler().runTaskLater(plugin, this::rescanUntrackedEndCrystals, RESCAN_FOLLOWUP_DELAY_TICKS);
    }

    private void rescanUntrackedEndCrystals() {
        for (World world : Bukkit.getWorlds()) {
            if (!isEndWorld(world)) {
                continue;
            }
            for (EnderCrystal crystal : world.getEntitiesByClass(EnderCrystal.class)) {
                if (crystal.isValid() && !isPlayerPlaced(crystal)) {
                    tryTagHeartPillarIfNeeded(crystal);
                    if (shouldUseHeartSystem(crystal)) {
                        ensureCrystalTracked(crystal);
                    }
                }
            }
        }
    }

    public void handleCrystalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal) || !isEndWorld(crystal.getWorld())) {
            return;
        }
        if (shouldIgnoreProjectileDamageInPhase2(event, crystal)) {
            event.setCancelled(true);
            return;
        }
        if (!shouldUseHeartSystem(crystal)) {
            return;
        }

        BossHeartStackState state = crystalStates.computeIfAbsent(
                crystal.getUniqueId(),
                id -> new BossHeartStackState(
                        CrystalHeartDisplayFactory.MAX_HEARTS,
                        displayFactory.spawnHeartDisplay(crystal, CrystalHeartDisplayFactory.MAX_HEARTS)
                )
        );

        if (state.heartsRemaining == 0) {
            CrystalHeartDisplayFactory.removeDisplayEntity(plugin, state.displayId);
            state.displayId = null;
            return;
        }

        event.setCancelled(true);

        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        if (!PlayerOriginatedAttacks.isPlayerOriginatedAttack(byEntity.getDamager())) {
            return;
        }

        state.heartsRemaining--;
        displayFactory.updateHeartDisplayText(state.displayId, state.heartsRemaining);
    }

    private boolean shouldIgnoreProjectileDamageInPhase2(EntityDamageEvent event, EnderCrystal crystal) {
        UUID worldId = crystal.getWorld().getUID();
        if (!isEnhancedDragonFightWorld(worldId) || !isEnhancedDragonPhase2(worldId)) {
            return false;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Projectile) {
            return true;
        }
        return event.getDamageSource().getDirectEntity() instanceof Projectile
                || event.getDamageSource().getCausingEntity() instanceof Projectile;
    }

    public void cleanupCrystal(Entity entity) {
        if (!(entity instanceof EnderCrystal crystal) || !isEndWorld(crystal.getWorld())) {
            return;
        }
        BossHeartStackState removed = crystalStates.remove(crystal.getUniqueId());
        if (removed != null) {
            CrystalHeartDisplayFactory.removeDisplayEntity(plugin, removed.displayId);
        }
    }

    public boolean isEnhancedPillarCombatCrystal(EnderCrystal crystal) {
        if (crystal == null || !crystal.isValid() || !isEndWorld(crystal.getWorld())) {
            return false;
        }
        return shouldUseHeartSystem(crystal);
    }

    private void tryTagHeartPillarIfNeeded(EnderCrystal crystal) {
        World world = crystal.getWorld();
        if (!isEnhancedPillarHeartsActive(world)) {
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null || battle.getRespawnPhase() == DragonBattle.RespawnPhase.NONE) {
            return;
        }
        Location portal = battle.getEndPortalLocation();
        if (portal == null || EnhancedDragonRitualEvaluator.isInRitualZone(crystal, portal)) {
            return;
        }
        PersistentDataFlags.setByteFlag(crystal, heartPillarCrystalKey, true);
    }

    private void ensureCrystalTracked(EnderCrystal crystal) {
        if (!shouldUseHeartSystem(crystal)) {
            return;
        }
        registerPillarSlotForRegen(crystal.getWorld(), crystal.getLocation());
        crystalStates.computeIfAbsent(
                crystal.getUniqueId(),
                id -> new BossHeartStackState(
                        CrystalHeartDisplayFactory.MAX_HEARTS,
                        displayFactory.spawnHeartDisplay(crystal, CrystalHeartDisplayFactory.MAX_HEARTS)
                )
        );
    }

    private boolean shouldUseHeartSystem(EnderCrystal crystal) {
        if (isPlayerPlaced(crystal)) {
            return false;
        }
        return PersistentDataFlags.hasByteFlag(crystal, heartPillarCrystalKey);
    }

    private boolean isPlayerPlaced(EnderCrystal crystal) {
        return PersistentDataFlags.hasByteFlag(crystal, playerPlacedCrystalKey);
    }

    private void clearAllCrystalTracking() {
        for (BossHeartStackState state : crystalStates.values()) {
            CrystalHeartDisplayFactory.removeDisplayEntity(plugin, state.displayId);
        }
        crystalStates.clear();
    }

    private void registerPillarSlotForRegen(World world, Location crystalLocation) {
        if (!Boolean.TRUE.equals(enhancedPillarPeriodicRegenByWorld.get(world.getUID()))) {
            return;
        }
        if (crystalLocation == null || crystalLocation.getWorld() == null) {
            return;
        }
        String key = blockKey(crystalLocation);
        pillarRegenSlotsByWorld
                .computeIfAbsent(world.getUID(), w -> new ConcurrentHashMap<>())
                .put(key, crystalLocation.clone());
    }

    private static String blockKey(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private void tickPeriodicPillarCrystalRegen() {
        for (Map.Entry<UUID, Boolean> e : enhancedPillarPeriodicRegenByWorld.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) {
                continue;
            }
            World world = Bukkit.getWorld(e.getKey());
            if (world == null || !isEndWorld(world)) {
                continue;
            }
            Long last = lastPillarRegenCheckWallMs.get(world.getUID());
            if (last == null) {
                continue;
            }
            long now = System.currentTimeMillis();
            long intervalMs = enhancedDragonBossPhaseTracker.isPhase2(world.getUID())
                    ? PILLAR_CRYSTAL_REGEN_INTERVAL_PHASE2_MS
                    : PILLAR_CRYSTAL_REGEN_INTERVAL_MS;
            if (now - last < intervalMs) {
                continue;
            }
            lastPillarRegenCheckWallMs.put(world.getUID(), now);
            tryRegenOneMissingPillarCrystal(world);
        }
    }

    private void tryRegenOneMissingPillarCrystal(World world) {
        Map<String, Location> slots = pillarRegenSlotsByWorld.get(world.getUID());
        if (slots == null || slots.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(slots.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            Location spawnAt = slots.get(key);
            if (spawnAt == null || spawnAt.getWorld() == null || !spawnAt.getWorld().equals(world)) {
                continue;
            }
            if (hasNonPlayerPlacedCrystalNear(spawnAt)) {
                continue;
            }
            EnderCrystal spawned = world.spawn(spawnAt, EnderCrystal.class);
            spawned.setShowingBottom(true);
            PersistentDataFlags.setByteFlag(spawned, heartPillarCrystalKey, true);
            ensureCrystalTracked(spawned);
            return;
        }
    }

    private boolean hasNonPlayerPlacedCrystalNear(Location center) {
        return findClosestNonPlayerPlacedCrystalNear(center) != null;
    }

    private static boolean isEndWorld(World world) {
        return world.getEnvironment() == World.Environment.THE_END;
    }

    private void tickLowHealthEndermanAggro() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (World world : Bukkit.getWorlds()) {
            if (!isEndWorld(world) || !isEnhancedDragonFightWorld(world.getUID())) {
                continue;
            }
            DragonBattle battle = world.getEnderDragonBattle();
            if (battle == null) {
                continue;
            }
            EnderDragon dragon = battle.getEnderDragon();
            if (dragon == null || !dragon.isValid() || dragon.isDead()) {
                continue;
            }
            AttributeInstance maxAttr = dragon.getAttribute(Attribute.MAX_HEALTH);
            if (maxAttr == null) {
                continue;
            }
            double max = maxAttr.getValue();
            if (max <= 0) {
                continue;
            }
            double originalMax = getEnhancedDragonBossOriginalMaxHealth(world.getUID());
            double halfThreshold = originalMax > 0 ? originalMax * 0.5 : max * 0.5;
            if (dragon.getHealth() > halfThreshold) {
                continue;
            }
            if (rng.nextDouble() >= LOW_HEALTH_ENDERMAN_AGGRO_WAVE_CHANCE) {
                continue;
            }
            List<Player> eligiblePlayers = new ArrayList<>();
            for (Player p : world.getPlayers()) {
                if (!p.isValid()) {
                    continue;
                }
                GameMode gm = p.getGameMode();
                if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) {
                    continue;
                }
                eligiblePlayers.add(p);
            }
            if (eligiblePlayers.isEmpty()) {
                continue;
            }
            List<Enderman> endermen = new ArrayList<>();
            for (Enderman em : world.getEntitiesByClass(Enderman.class)) {
                if (em.isValid() && !em.isDead()) {
                    endermen.add(em);
                }
            }
            if (endermen.isEmpty()) {
                continue;
            }
            int waveSize = rng.nextInt(LOW_HEALTH_ENDERMAN_AGGRO_MIN_PER_WAVE, LOW_HEALTH_ENDERMAN_AGGRO_MAX_PER_WAVE + 1);
            Collections.shuffle(endermen, rng);
            int assigned = 0;
            for (Enderman em : endermen) {
                if (assigned >= waveSize) {
                    break;
                }
                Player target = eligiblePlayers.get(rng.nextInt(eligiblePlayers.size()));
                if (em.getLocation().distanceSquared(target.getLocation()) > LOW_HEALTH_ENDERMAN_NEAR_PLAYER_RADIUS_SQ) {
                    continue;
                }
                em.setTarget(target);
                assigned++;
            }
        }
    }
}

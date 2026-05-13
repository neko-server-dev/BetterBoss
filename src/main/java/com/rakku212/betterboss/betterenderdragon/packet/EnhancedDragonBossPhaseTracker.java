package com.rakku212.betterboss.betterenderdragon.packet;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;

import com.rakku212.betterboss.betterenderdragon.manager.BetterEnderDragonCrystalManager;
import com.rakku212.betterboss.betterenderdragon.persistence.EnhancedDragonFightWorldStore;

import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EnhancedDragonBossPhaseTracker {

    public enum Phase {
        PHASE1,
        TRANSITION,
        PHASE2
    }

    private static final class State {
        volatile Phase phase = Phase.PHASE1;
        int transitionElapsedTicks;
        volatile double originalMaxHealth;
    }

    public static final int TRANSITION_DURATION_TICKS = 200;

    private final BetterEnderDragonCrystalManager crystalManager;
    private final ConcurrentHashMap<UUID, State> byWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> cachedBossBarHealthByWorld = new ConcurrentHashMap<>();
    private @Nullable EnhancedDragonBossBarPacketListener bossBarPacketListener;

    public EnhancedDragonBossPhaseTracker(BetterEnderDragonCrystalManager crystalManager) {
        this.crystalManager = crystalManager;
    }

    /**
     * {@link FeatureBootstrap} でリスナー生成後に結線する（コンストラクタ時点では未登録のため）
     */
    public void setBossBarPacketListener(EnhancedDragonBossBarPacketListener listener) {
        this.bossBarPacketListener = listener;
    }

    public void restorePersistedStates(Map<UUID, EnhancedDragonFightWorldStore.PersistedBossPhaseState> persistedByWorld) {
        if (persistedByWorld == null || persistedByWorld.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, EnhancedDragonFightWorldStore.PersistedBossPhaseState> entry : persistedByWorld.entrySet()) {
            UUID worldId = entry.getKey();
            if (worldId == null || !crystalManager.isEnhancedDragonFightWorld(worldId)) {
                continue;
            }
            EnhancedDragonFightWorldStore.PersistedBossPhaseState persisted = entry.getValue();
            if (persisted == null) {
                continue;
            }
            State state = new State();
            state.phase = parsePhaseOrDefault(persisted.phaseName());
            state.originalMaxHealth = Math.max(0d, persisted.originalMaxHealth());
            byWorld.put(worldId, state);
        }
    }

    public void reset(UUID worldId) {
        if (worldId != null) {
            byWorld.remove(worldId);
            cachedBossBarHealthByWorld.remove(worldId);
            crystalManager.saveEnhancedDragonBossPhase(worldId, Phase.PHASE1.name(), 0d);
            if (bossBarPacketListener != null) {
                bossBarPacketListener.clearEnderDragonBossBarId(worldId);
            }
            World w = Bukkit.getWorld(worldId);
            if (w != null && w.getEnvironment() == World.Environment.THE_END) {
                DragonBattle battle = w.getEnderDragonBattle();
                if (battle != null) {
                    EnderDragon d = battle.getEnderDragon();
                    if (d != null && d.isValid()) {
                        d.setInvulnerable(false);
                    }
                }
            }
        }
    }

    /**
     * スポーン直後に呼ぶ
     * 戦闘全体の「元の最大体力」
     */
    public void seedOriginalMaxHealth(EnderDragon dragon, double originalMax) {
        if (dragon == null || !dragon.isValid() || originalMax <= 0) {
            return;
        }
        World w = dragon.getWorld();
        if (w.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID wid = w.getUID();
        if (!crystalManager.isEnhancedDragonFightWorld(wid)) {
            return;
        }
        State s = byWorld.computeIfAbsent(wid, k -> new State());
        s.originalMaxHealth = originalMax;
        crystalManager.saveEnhancedDragonBossPhase(wid, s.phase.name(), s.originalMaxHealth);
    }

    /** 「全体の半分」閾値に使う
     * 未設定時は 0 
     */
    public double getOriginalMaxHealth(UUID worldId) {
        State s = byWorld.get(worldId);
        if (s == null || s.originalMaxHealth <= 0) {
            return 0;
        }
        return s.originalMaxHealth;
    }

    public float getCachedBossBarHealthFraction(UUID worldId) {
        Float f = cachedBossBarHealthByWorld.get(worldId);
        return f == null ? -1f : f;
    }

    public void refreshBossBarCacheFor(EnderDragon dragon) {
        World w = dragon.getWorld();
        if (w.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID wid = w.getUID();
        if (!crystalManager.isEnhancedDragonFightWorld(wid)) {
            return;
        }
        float v = computeBossBarHealthFraction(dragon);
        if (v < 0f) {
            cachedBossBarHealthByWorld.remove(wid);
        } else {
            cachedBossBarHealthByWorld.put(wid, v);
        }
        if (bossBarPacketListener != null && isTransitioning(wid)) {
            bossBarPacketListener.broadcastTransitionHealth(w);
        }
    }

    public boolean isTransitioning(UUID worldId) {
        State s = byWorld.get(worldId);
        return s != null && s.phase == Phase.TRANSITION;
    }

    public boolean isPhase2(UUID worldId) {
        State s = byWorld.get(worldId);
        return s != null && s.phase == Phase.PHASE2;
    }

    public void onDragonHealthChanged(EnderDragon dragon) {
        if (!dragon.isValid() || dragon.isDead()) {
            return;
        }
        World w = dragon.getWorld();
        if (w.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID wid = w.getUID();
        if (!crystalManager.isEnhancedDragonFightWorld(wid)) {
            return;
        }
        AttributeInstance attr = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return;
        }
        double hp = dragon.getHealth();
        if (hp <= 0) {
            return;
        }
        State s = byWorld.computeIfAbsent(wid, k -> new State());
        if (s.originalMaxHealth <= 0) {
            s.originalMaxHealth = attr.getValue();
        }
        if (s.originalMaxHealth <= 0) {
            return;
        }
        double half = s.originalMaxHealth * 0.5;
        if (s.phase != Phase.PHASE1) {
            return;
        }
        if (hp <= half) {
            s.phase = Phase.TRANSITION;
            s.transitionElapsedTicks = 0;
            crystalManager.saveEnhancedDragonBossPhase(wid, s.phase.name(), s.originalMaxHealth);
            dragon.setHealth(half);
            dragon.setInvulnerable(true);
            crystalManager.applyEnhancedDragonPhaseTransitionEffects(w);
            if (bossBarPacketListener != null) {
                bossBarPacketListener.broadcastTransitionBossBarStyleEnter(w);
            }
        }
    }

    public void tickEveryWorld() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) {
                continue;
            }
            UUID wid = world.getUID();
            DragonBattle battle = world.getEnderDragonBattle();
            if (battle == null) {
                if (crystalManager.isEnhancedDragonFightWorld(wid)) {
                    byWorld.remove(wid);
                    cachedBossBarHealthByWorld.remove(wid);
                }
                continue;
            }
            DragonBattle.RespawnPhase respawnPhase = battle.getRespawnPhase();
            if (respawnPhase != DragonBattle.RespawnPhase.NONE) {
                crystalManager.syncEnhancedRitualFlagsDuringRespawn(world, battle);
            }
            if (!crystalManager.isEnhancedDragonFightWorld(wid)) {
                byWorld.remove(wid);
                cachedBossBarHealthByWorld.remove(wid);
                continue;
            }
            if (respawnPhase != DragonBattle.RespawnPhase.NONE) {
                cachedBossBarHealthByWorld.remove(wid);
                continue;
            }

            EnderDragon dragon = battle.getEnderDragon();
            if (dragon == null || !dragon.isValid()) {
                cachedBossBarHealthByWorld.remove(wid);
                continue;
            }
            if (dragon.isDead() || dragon.getHealth() <= 0) {
                byWorld.remove(wid);
                cachedBossBarHealthByWorld.remove(wid);
                continue;
            }
            State s = byWorld.get(wid);
            if (s != null && s.phase == Phase.TRANSITION) {
                dragon.setInvulnerable(true);
                AttributeInstance maxAttr = dragon.getAttribute(Attribute.MAX_HEALTH);
                if (maxAttr != null) {
                    double halfPool = s.originalMaxHealth > 0 ? s.originalMaxHealth * 0.5 : maxAttr.getValue() * 0.5;
                    dragon.setHealth(halfPool);
                }
                s.transitionElapsedTicks++;
                if (s.transitionElapsedTicks > TRANSITION_DURATION_TICKS) {
                    s.phase = Phase.PHASE2;
                    s.transitionElapsedTicks = 0;
                    crystalManager.saveEnhancedDragonBossPhase(wid, s.phase.name(), s.originalMaxHealth);
                    AttributeInstance poolAttr = dragon.getAttribute(Attribute.MAX_HEALTH);
                    if (poolAttr != null) {
                        double pool = s.originalMaxHealth > 0 ? s.originalMaxHealth * 0.5 : poolAttr.getValue() * 0.5;
                        poolAttr.setBaseValue(pool);
                        dragon.setHealth(pool);
                    }
                    dragon.setInvulnerable(false);
                    crystalManager.restorePillarCrystalsForSecondPhase(world);
                    crystalManager.applySecondPhaseEnterEffects(world);
                }
            }
            float v = computeBossBarHealthFraction(dragon);
            if (v < 0f) {
                cachedBossBarHealthByWorld.remove(wid);
            } else {
                cachedBossBarHealthByWorld.put(wid, v);
            }
            if (bossBarPacketListener != null && s != null && s.phase == Phase.TRANSITION) {
                bossBarPacketListener.broadcastTransitionHealth(world);
            }
        }
    }

    /**
     * @return 0..1 でボスバー上書き用
     * 強化戦外のみ -1
     * 負ならバニラのまま
     */
    public float computeBossBarHealthFraction(EnderDragon dragon) {
        World w = dragon.getWorld();
        if (w.getEnvironment() != World.Environment.THE_END) {
            return -1f;
        }
        UUID wid = w.getUID();
        if (!crystalManager.isEnhancedDragonFightWorld(wid)) {
            return -1f;
        }
        AttributeInstance attr = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return -1f;
        }
        double hp = dragon.getHealth();
        if (!dragon.isValid() || dragon.isDead() || hp <= 0) {
            return 0f;
        }
        State s = byWorld.get(wid);
        Phase phase = s == null ? Phase.PHASE1 : s.phase;

        if (phase == Phase.PHASE2) {
            double max = attr.getValue();
            if (max <= 0) {
                return 0f;
            }
            return (float) Math.max(0d, Math.min(1d, hp / max));
        }

        double om = s != null && s.originalMaxHealth > 0 ? s.originalMaxHealth : attr.getValue();
        if (om <= 0) {
            return -1f;
        }
        double half = om * 0.5;

        if (phase == Phase.PHASE1) {
            if (hp <= half) {
                return 0f;
            }
            return (float) Math.max(0d, Math.min(1d, (hp - half) / half));
        }
        if (phase == Phase.TRANSITION) {
            if (s == null) {
                return 0f;
            }
            int e = s.transitionElapsedTicks;
            return Math.min(1f, (e + 1) / (float) (TRANSITION_DURATION_TICKS + 1));
        }
        return -1f;
    }

    private static Phase parsePhaseOrDefault(String phaseName) {
        if (phaseName == null || phaseName.isBlank()) {
            return Phase.PHASE1;
        }
        try {
            return Phase.valueOf(phaseName.trim());
        } catch (IllegalArgumentException ignored) {
            return Phase.PHASE1;
        }
    }
}

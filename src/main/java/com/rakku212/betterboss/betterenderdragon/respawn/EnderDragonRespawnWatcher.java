package com.rakku212.betterboss.betterenderdragon.respawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class EnderDragonRespawnWatcher {

    private static final double PORTAL_RITUAL_HORIZONTAL_RADIUS = 24.0;
    private static final double PORTAL_RITUAL_VERTICAL_HALF_HEIGHT = 32.0;

    private final Runnable clearAllCrystalTracking;
    private final Runnable rescanEndCrystalTracking;
    private final Predicate<EnderCrystal> isPlayerPlaced;
    private final BiConsumer<World, DragonBattle> onDragonRespawnEntering;
    private final Consumer<World> onDragonRespawnIdle;
    private final Map<UUID, DragonBattle.RespawnPhase> lastRespawnPhaseByWorld = new ConcurrentHashMap<>();

    public EnderDragonRespawnWatcher(
            Runnable clearAllCrystalTracking,
            Runnable rescanEndCrystalTracking,
            Predicate<EnderCrystal> isPlayerPlaced,
            BiConsumer<World, DragonBattle> onDragonRespawnEntering,
            Consumer<World> onDragonRespawnIdle
    ) {
        this.clearAllCrystalTracking = clearAllCrystalTracking;
        this.rescanEndCrystalTracking = rescanEndCrystalTracking;
        this.isPlayerPlaced = isPlayerPlaced;
        this.onDragonRespawnEntering = onDragonRespawnEntering;
        this.onDragonRespawnIdle = onDragonRespawnIdle;
    }

    public void tick() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) {
                continue;
            }
            DragonBattle battle = world.getEnderDragonBattle();
            if (battle == null) {
                continue;
            }
            DragonBattle.RespawnPhase current = battle.getRespawnPhase();
            DragonBattle.RespawnPhase last = lastRespawnPhaseByWorld.getOrDefault(world.getUID(), DragonBattle.RespawnPhase.NONE);
            if (current == last) {
                continue;
            }
            if (last == DragonBattle.RespawnPhase.NONE && current != DragonBattle.RespawnPhase.NONE) {
                prepareEndForDragonRespawn(world, battle);
                onDragonRespawnEntering.accept(world, battle);
            } else if (last != DragonBattle.RespawnPhase.NONE && current == DragonBattle.RespawnPhase.NONE) {
                if (isAbortBeforeSummonDragon(last)) {
                    prepareEndForDragonRespawn(world, battle);
                } else {
                    clearAllCrystalTracking.run();
                    battle.resetCrystals();
                    rescanEndCrystalTracking.run();
                }
            }
            lastRespawnPhaseByWorld.put(world.getUID(), current);
            if (last != current && current == DragonBattle.RespawnPhase.NONE) {
                onDragonRespawnIdle.accept(world);
            }
        }
    }

    private static boolean isAbortBeforeSummonDragon(DragonBattle.RespawnPhase last) {
        return last == DragonBattle.RespawnPhase.START
                || last == DragonBattle.RespawnPhase.PREPARING_TO_SUMMON_PILLARS
                || last == DragonBattle.RespawnPhase.SUMMONING_PILLARS;
    }

    private void prepareEndForDragonRespawn(World world, DragonBattle battle) {
        clearAllCrystalTracking.run();
        battle.resetCrystals();
        Location portal = battle.getEndPortalLocation();
        double hr = PORTAL_RITUAL_HORIZONTAL_RADIUS;
        double hrSq = hr * hr;
        double vHalf = PORTAL_RITUAL_VERTICAL_HALF_HEIGHT;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof EnderCrystal crystal)) {
                continue;
            }
            if (isPlayerPlaced.test(crystal) || isNearEndPortalRitual(crystal, portal, hrSq, vHalf)) {
                continue;
            }
            crystal.remove();
        }
        rescanEndCrystalTracking.run();
    }

    private static boolean isNearEndPortalRitual(
            EnderCrystal crystal,
            Location portalCenter,
            double horizontalRadiusSq,
            double verticalHalfHeight
    ) {
        if (portalCenter == null || portalCenter.getWorld() == null) {
            return false;
        }
        if (!crystal.getWorld().equals(portalCenter.getWorld())) {
            return false;
        }
        Location loc = crystal.getLocation();
        double dx = loc.getX() - portalCenter.getX();
        double dz = loc.getZ() - portalCenter.getZ();
        if (dx * dx + dz * dz > horizontalRadiusSq) {
            return false;
        }
        double dy = loc.getY() - portalCenter.getY();
        return Math.abs(dy) <= verticalHalfHeight;
    }
}

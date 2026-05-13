package com.rakku212.betterboss.betterenderdragon.ritual;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;

import com.rakku212.util.persistentdata.PersistentDataFlags;

import java.util.ArrayList;
import java.util.List;

public final class EnhancedDragonRitualEvaluator {

    private static final double PORTAL_RITUAL_HORIZONTAL_RADIUS = 24.0;
    private static final double PORTAL_RITUAL_VERTICAL_HALF_HEIGHT = 32.0;

    public static boolean isInRitualZone(EnderCrystal crystal, Location portalCenter) {
        if (portalCenter == null || portalCenter.getWorld() == null) {
            return false;
        }
        if (!crystal.getWorld().equals(portalCenter.getWorld())) {
            return false;
        }
        Location loc = crystal.getLocation();
        double hr = PORTAL_RITUAL_HORIZONTAL_RADIUS;
        double hrSq = hr * hr;
        double dx = loc.getX() - portalCenter.getX();
        double dz = loc.getZ() - portalCenter.getZ();
        if (dx * dx + dz * dz > hrSq) {
            return false;
        }
        double dy = loc.getY() - portalCenter.getY();
        return Math.abs(dy) <= PORTAL_RITUAL_VERTICAL_HALF_HEIGHT;
    }

    /**
     * 復活ゾーン内のエンダークリスタルがちょうど4体かつ、すべて強化アイテムから設置されたものなら true
     */
    public static boolean areAllFourRitualCrystalsEnhanced(
            World world,
            DragonBattle battle,
            NamespacedKey enhancedRitualCrystalEntityKey
    ) {
        Location portal = battle.getEndPortalLocation();
        if (portal == null) {
            return false;
        }
        List<EnderCrystal> inZone = new ArrayList<>();
        for (EnderCrystal crystal : world.getEntitiesByClass(EnderCrystal.class)) {
            if (crystal.isValid() && isInRitualZone(crystal, portal)) {
                inZone.add(crystal);
            }
        }
        if (inZone.size() != 4) {
            return false;
        }
        for (EnderCrystal crystal : inZone) {
            if (!PersistentDataFlags.hasByteFlag(crystal, enhancedRitualCrystalEntityKey)) {
                return false;
            }
        }
        return true;
    }
}

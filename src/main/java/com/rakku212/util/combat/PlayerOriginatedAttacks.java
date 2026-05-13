package com.rakku212.util.combat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

public final class PlayerOriginatedAttacks {

    public static boolean isPlayerOriginatedAttack(Entity damager) {
        if (damager instanceof Player) {
            return true;
        }
        if (damager instanceof Projectile projectile) {
            return projectile.getShooter() instanceof Player;
        }
        return false;
    }
}

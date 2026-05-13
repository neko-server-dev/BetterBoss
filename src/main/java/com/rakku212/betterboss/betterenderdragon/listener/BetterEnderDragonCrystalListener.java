package com.rakku212.betterboss.betterenderdragon.listener;

import org.bukkit.World;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;

import com.rakku212.betterboss.betterenderdragon.manager.BetterEnderDragonCrystalManager;

import java.util.UUID;

public final class BetterEnderDragonCrystalListener implements Listener {

    private final BetterEnderDragonCrystalManager crystalManager;

    public BetterEnderDragonCrystalListener(BetterEnderDragonCrystalManager crystalManager) {
        this.crystalManager = crystalManager;
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof EnderCrystal crystal) {
            crystalManager.onEndCrystalSpawned(crystal);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        crystalManager.onPlayerInteractEndCrystalPlacement(event.getPlayer().getUniqueId(), event.getItem());
    }

    @EventHandler
    public void onEntityPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player != null ? player.getUniqueId() : null;
        Boolean pending = crystalManager.takePendingEnhancedPlacement(id);
        crystalManager.onPlayerPlacedEndCrystal(crystal, pending);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        crystalManager.onEndChunkLoaded(world, event.getChunk().getEntities());
        crystalManager.scheduleDelayedEndChunkRescan(world, event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler
    public void onCrystalDamage(EntityDamageEvent event) {
        crystalManager.handleCrystalDamage(event);
    }

    @EventHandler
    public void onCrystalDeath(EntityDeathEvent event) {
        crystalManager.cleanupCrystal(event.getEntity());
    }

    @EventHandler
    public void onCrystalRemoved(EntityRemoveFromWorldEvent event) {
        crystalManager.cleanupCrystal(event.getEntity());
    }
}

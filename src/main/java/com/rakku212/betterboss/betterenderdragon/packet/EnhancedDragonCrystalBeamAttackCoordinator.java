package com.rakku212.betterboss.betterenderdragon.packet;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.plugin.java.JavaPlugin;

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;

import com.rakku212.betterboss.betterenderdragon.manager.BetterEnderDragonCrystalManager;
import com.rakku212.util.packet.CraftPlayerPackets;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EnhancedDragonCrystalBeamAttackCoordinator {

    private static final long TICK_INTERVAL = 2L;
    private static final long ATTACK_COOLDOWN_TICKS = 10L;
    private static final double TARGET_RANGE_SQ = 24.0 * 24.0;
    private static final double ATTACK_RANGE_SQ = 18.0 * 18.0;
    private static final double PLAYER_DAMAGE = 1.0;
    private static final byte ENTITY_STATUS_HURT = 2;

    private final BetterEnderDragonCrystalManager crystalManager;
    private final Map<UUID, Long> lastAttackTickByCrystal = new ConcurrentHashMap<>();

    private EnhancedDragonCrystalBeamAttackCoordinator(
            BetterEnderDragonCrystalManager crystalManager
    ) {
        this.crystalManager = crystalManager;
    }

    public static void register(JavaPlugin plugin, BetterEnderDragonCrystalManager crystalManager) {
        EnhancedDragonCrystalBeamAttackCoordinator coordinator =
                new EnhancedDragonCrystalBeamAttackCoordinator(crystalManager);
        Bukkit.getScheduler().runTaskTimer(plugin, coordinator::tick, 1L, TICK_INTERVAL);
    }

    private void tick() {
        long nowTick = Bukkit.getCurrentTick();
        for (World world : Bukkit.getWorlds()) {
            boolean active = world.getEnvironment() == World.Environment.THE_END
                    && crystalManager.isEnhancedDragonFightWorld(world.getUID());
            if (active) {
                DragonBattle battle = world.getEnderDragonBattle();
                EnderDragon dragon = battle == null ? null : battle.getEnderDragon();
                active = battle != null
                        && battle.getRespawnPhase() == DragonBattle.RespawnPhase.NONE
                        && dragon != null
                        && dragon.isValid()
                        && !dragon.isDead()
                        && dragon.getHealth() > 0.0;
            }
            if (!active) {
                if (crystalManager.isEnhancedEnderDragonRespawnActive(world)) {
                    continue;
                }
                for (EnderCrystal crystal : world.getEntitiesByClass(EnderCrystal.class)) {
                    if (!crystalManager.isEnhancedPillarCombatCrystal(crystal)) {
                        continue;
                    }
                    if (crystal.getBeamTarget() != null) {
                        crystal.setBeamTarget(null);
                    }
                    lastAttackTickByCrystal.remove(crystal.getUniqueId());
                }
                continue;
            }
            tickWorld(world, nowTick);
        }
    }

    private void tickWorld(World world, long nowTick) {
        for (EnderCrystal crystal : world.getEntitiesByClass(EnderCrystal.class)) {
            if (!crystalManager.isEnhancedPillarCombatCrystal(crystal)) {
                continue;
            }
            Player target = null;
            double bestSq = Double.MAX_VALUE;
            Location from = crystal.getLocation();
            for (Player player : world.getPlayers()) {
                if (player == null || !player.isOnline() || !player.isValid()) {
                    continue;
                }
                GameMode gameMode = player.getGameMode();
                if (gameMode != GameMode.SURVIVAL && gameMode != GameMode.ADVENTURE) {
                    continue;
                }
                double distSq = player.getLocation().distanceSquared(from);
                if (distSq > TARGET_RANGE_SQ || distSq >= bestSq) {
                    continue;
                }
                bestSq = distSq;
                target = player;
            }
            if (target == null) {
                crystal.setBeamTarget(null);
                continue;
            }
            crystal.setBeamTarget(target.getLocation());
            if (crystal.getLocation().distanceSquared(target.getLocation()) > ATTACK_RANGE_SQ) {
                continue;
            }
            if (nowTick - lastAttackTickByCrystal.getOrDefault(crystal.getUniqueId(), 0L) < ATTACK_COOLDOWN_TICKS) {
                continue;
            }
            lastAttackTickByCrystal.put(crystal.getUniqueId(), nowTick);
            target.damage(PLAYER_DAMAGE, crystal);
            ClientboundEntityEventPacket hurtPacket =
                    new ClientboundEntityEventPacket(((CraftPlayer) target).getHandle(), ENTITY_STATUS_HURT);
            for (Player viewer : world.getPlayers()) {
                CraftPlayerPackets.send(viewer, hurtPacket);
            }
        }
    }
}

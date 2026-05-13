package com.rakku212.betterboss.betterenderdragon.packet;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;

import com.rakku212.betterboss.betterenderdragon.manager.BetterEnderDragonCrystalManager;
import com.rakku212.util.packet.ClientboundEntityEventPackets;
import com.rakku212.util.packet.CraftPlayerPackets;
import com.rakku212.util.packet.PlayerChannelHooks;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class EnhancedDragonFakeEndermiteCoordinator implements Listener {

    private static final String INTERACT_CHANNEL = "betterboss_fake_endermite_in";
    private static final int MAX_MITES_PER_WORLD = 12;
    private static final int SPAWN_COUNT_PER_WAVE = 3;
    private static final long PHASE1_SPAWN_INTERVAL_TICKS = 3L * 60L * 20L;
    private static final long PHASE2_SPAWN_INTERVAL_TICKS = 60L * 20L;
    private static final double SPAWN_MIN_RADIUS = 6.0;
    private static final double SPAWN_MAX_RADIUS = 16.0;
    private static final double DESPAWN_DISTANCE_SQ = 72.0 * 72.0;
    private static final double MOVE_SPEED_PER_TICK = 0.29;
    private static final double ATTACK_RANGE_SQ = 1.35 * 1.35;
    private static final long ATTACK_COOLDOWN_TICKS = 20L;
    private static final long TARGET_ABSENCE_DESPAWN_TICKS = 10L * 60L * 20L;
    private static final double ATTACK_DAMAGE = 2.0;
    private static final double ATTACK_KNOCKBACK = 0.22;
    private static final double DEFAULT_ENDERMITE_MAX_HEALTH = 8.0;
    private static final long MAX_LIFETIME_TICKS = 20L * 25L;
    private static final int SPAWN_TRIES = 12;
    private static final double HEIGHT_EPSILON = 0.05;
    private static final byte ENTITY_STATUS_HURT = 2;
    private static final byte ENTITY_STATUS_DEATH = 3;
    private static final long AMBIENT_MIN_INTERVAL_TICKS = 30L;
    private static final long AMBIENT_MAX_INTERVAL_TICKS = 90L;

    private final JavaPlugin plugin;
    private final BetterEnderDragonCrystalManager crystalManager;
    private final Map<UUID, WorldState> statesByWorld = new ConcurrentHashMap<>();
    private final AtomicInteger entityIdSequence = new AtomicInteger(2_000_000);

    private static final class FakeEndermite {
        final int entityId;
        final UUID uuid;
        Vector position;
        UUID targetPlayerId;
        long nextAttackTick;
        long despawnAtTick;
        float yaw;
        double health;
        long nextAmbientTick;
        long noTargetSinceTick;

        FakeEndermite(int entityId, UUID uuid, Vector position, UUID targetPlayerId, long despawnAtTick) {
            this.entityId = entityId;
            this.uuid = uuid;
            this.position = position;
            this.targetPlayerId = targetPlayerId;
            this.despawnAtTick = despawnAtTick;
            this.yaw = 0f;
            this.health = DEFAULT_ENDERMITE_MAX_HEALTH;
            this.nextAmbientTick = 0L;
            this.noTargetSinceTick = -1L;
        }
    }

    private static final class WorldState {
        long lastSpawnTick;
        final Map<Integer, FakeEndermite> mitesByEntityId = new ConcurrentHashMap<>();
    }

    private EnhancedDragonFakeEndermiteCoordinator(JavaPlugin plugin, BetterEnderDragonCrystalManager crystalManager) {
        this.plugin = plugin;
        this.crystalManager = crystalManager;
    }

    public static void register(JavaPlugin plugin, BetterEnderDragonCrystalManager crystalManager) {
        EnhancedDragonFakeEndermiteCoordinator coordinator =
                new EnhancedDragonFakeEndermiteCoordinator(plugin, crystalManager);
        plugin.getServer().getPluginManager().registerEvents(coordinator, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            coordinator.injectInteractChannel(p);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, coordinator::tick, 1L, 1L);
    }

    private void injectInteractChannel(Player player) {
        if (!(player instanceof CraftPlayer)) {
            return;
        }
        PlayerChannelHooks.addBeforePacketHandler(player, INTERACT_CHANNEL, new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof ServerboundInteractPacket packet) {
                    OptionalInt attacked = extractAttackEntityId(packet);
                    if (attacked.isPresent()) {
                        int attackedEntityId = attacked.getAsInt();
                        Bukkit.getScheduler().runTask(plugin, () -> handlePlayerAttack(player, attackedEntityId));
                    }
                }
                ctx.fireChannelRead(msg);
            }
        });
    }

    private static OptionalInt extractAttackEntityId(ServerboundInteractPacket packet) {
        AtomicBoolean attack = new AtomicBoolean(false);
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onAttack() {
                attack.set(true);
            }

            @Override
            public void onInteraction(InteractionHand hand) {
            }

            @Override
            public void onInteraction(InteractionHand hand, Vec3 interactionLocation) {
            }
        });
        if (!attack.get()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(packet.getEntityId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        injectInteractChannel(player);
        if (player.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendAllToPlayer(player), 20L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendAllToPlayer(player), 5L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerChannelHooks.remove(event.getPlayer(), INTERACT_CHANNEL);
        UUID playerId = event.getPlayer().getUniqueId();
        for (WorldState state : statesByWorld.values()) {
            for (FakeEndermite mite : state.mitesByEntityId.values()) {
                if (playerId.equals(mite.targetPlayerId)) {
                    mite.targetPlayerId = null;
                }
            }
        }
    }

    @EventHandler
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        clearWorld(dragon.getWorld());
    }

    @EventHandler
    public void onDragonSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        UUID worldId = world.getUID();
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawnGuaranteedMiteOnDragonSpawn(world, worldId), 1L);
    }

    private void tick() {
        long nowTick = Bukkit.getCurrentTick();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) {
                continue;
            }
            UUID worldId = world.getUID();
            if (!isMiteSpawnPhaseActive(world, worldId)) {
                clearWorld(world);
                continue;
            }
            WorldState state = statesByWorld.computeIfAbsent(worldId, ignored -> new WorldState());
            tickSpawn(world, state, nowTick);
            tickMites(world, state, nowTick);
        }
    }

    private void tickSpawn(World world, WorldState state, long nowTick) {
        if (state.mitesByEntityId.size() >= MAX_MITES_PER_WORLD) {
            return;
        }
        long intervalTicks = spawnIntervalByPhase(world.getUID());
        if (intervalTicks <= 0L) {
            return;
        }
        if (nowTick - state.lastSpawnTick < intervalTicks) {
            return;
        }
        Player target = pickTargetPlayer(world, null);
        if (target == null) {
            return;
        }
        int spawned = spawnWave(world, state, target, nowTick, SPAWN_COUNT_PER_WAVE);
        if (spawned <= 0) {
            return;
        }
        state.lastSpawnTick = nowTick;
    }

    private void tickMites(World world, WorldState state, long nowTick) {
        List<Integer> removeIds = new ArrayList<>();
        for (FakeEndermite mite : state.mitesByEntityId.values()) {
            if (nowTick >= mite.despawnAtTick) {
                removeIds.add(mite.entityId);
                continue;
            }
            Player target = resolveTarget(world, mite);
            if (target == null) {
                if (mite.noTargetSinceTick < 0L) {
                    mite.noTargetSinceTick = nowTick;
                } else if (nowTick - mite.noTargetSinceTick >= TARGET_ABSENCE_DESPAWN_TICKS) {
                    removeIds.add(mite.entityId);
                    continue;
                }
                tickAmbientSound(world, mite, nowTick);
                continue;
            }
            mite.noTargetSinceTick = -1L;
            if (target.getLocation().toVector().distanceSquared(mite.position) > DESPAWN_DISTANCE_SQ) {
                removeIds.add(mite.entityId);
                continue;
            }
            updateMoveTowardTarget(world, mite, target);
            sendTeleport(world, mite);
            tickAmbientSound(world, mite, nowTick);
            if (nowTick >= mite.nextAttackTick
                    && target.getLocation().toVector().distanceSquared(mite.position) <= ATTACK_RANGE_SQ) {
                mite.nextAttackTick = nowTick + ATTACK_COOLDOWN_TICKS;
                target.damage(ATTACK_DAMAGE);
                Vector knock = target.getLocation().toVector().subtract(mite.position);
                knock.setY(0);
                if (knock.lengthSquared() > 0.0001) {
                    target.setVelocity(target.getVelocity().add(knock.normalize().multiply(ATTACK_KNOCKBACK)));
                }
            }
        }
        for (Integer entityId : removeIds) {
            removeMite(world, state, entityId);
        }
    }

    private Player resolveTarget(World world, FakeEndermite mite) {
        if (mite.targetPlayerId != null) {
            Player current = Bukkit.getPlayer(mite.targetPlayerId);
            if (isValidTarget(world, current)) {
                return current;
            }
        }
        Player next = pickTargetPlayer(world, mite.position);
        mite.targetPlayerId = next == null ? null : next.getUniqueId();
        return next;
    }

    private Player pickTargetPlayer(World world, Vector from) {
        Comparator<Player> byDistance = Comparator.comparingDouble(p -> {
            if (from == null) {
                return 0.0;
            }
            return p.getLocation().toVector().distanceSquared(from);
        });
        return world.getPlayers().stream()
                .filter(p -> isValidTarget(world, p))
                .min(byDistance)
                .orElse(null);
    }

    private boolean isValidTarget(World world, Player p) {
        if (p == null || !p.isOnline() || !p.isValid() || !p.getWorld().equals(world)) {
            return false;
        }
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    private Vector chooseSpawnPositionNear(World world, Player player) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vector base = player.getLocation().toVector();
        for (int i = 0; i < SPAWN_TRIES; i++) {
            double angle = r.nextDouble() * Math.PI * 2.0;
            double radius = SPAWN_MIN_RADIUS + r.nextDouble() * (SPAWN_MAX_RADIUS - SPAWN_MIN_RADIUS);
            double x = base.getX() + Math.cos(angle) * radius;
            double z = base.getZ() + Math.sin(angle) * radius;
            Vector pos = resolveWalkableGround(world, x, z);
            if (pos != null) {
                return pos;
            }
        }
        return resolveWalkableGround(world, base.getX(), base.getZ());
    }

    private FakeEndermite spawnMite(World world, Player target, Vector pos, long nowTick) {
        int entityId = entityIdSequence.incrementAndGet();
        UUID uuid = UUID.randomUUID();
        ClientboundAddEntityPacket spawnPacket = createSpawnPacket(entityId, uuid, pos, 0f);
        for (Player viewer : world.getPlayers()) {
            CraftPlayerPackets.send(viewer, spawnPacket);
        }
        FakeEndermite mite = new FakeEndermite(entityId, uuid, pos, target.getUniqueId(), nowTick + MAX_LIFETIME_TICKS);
        playEntitySound(world, mite.position, Sound.ENTITY_ENDERMITE_AMBIENT, 0.8f, randomPitch(0.9f, 1.1f));
        mite.nextAmbientTick = nowTick + randomAmbientInterval();
        return mite;
    }

    private static ClientboundAddEntityPacket createSpawnPacket(int entityId, UUID uuid, Vector pos, float yaw) {
        return new ClientboundAddEntityPacket(
                entityId,
                uuid,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                0f,
                yaw,
                EntityType.ENDERMITE,
                0,
                Vec3.ZERO,
                yaw
        );
    }

    private void sendTeleport(World world, FakeEndermite mite) {
        PositionMoveRotation pmr = new PositionMoveRotation(
                new Vec3(mite.position.getX(), mite.position.getY(), mite.position.getZ()),
                Vec3.ZERO,
                mite.yaw,
                0f
        );
        ClientboundTeleportEntityPacket packet = new ClientboundTeleportEntityPacket(
                mite.entityId,
                pmr,
                Collections.emptySet(),
                false
        );
        for (Player viewer : world.getPlayers()) {
            CraftPlayerPackets.send(viewer, packet);
        }
    }

    private void removeMite(World world, WorldState state, int entityId) {
        FakeEndermite removed = state.mitesByEntityId.remove(entityId);
        if (removed == null) {
            return;
        }
        ClientboundRemoveEntitiesPacket destroy = new ClientboundRemoveEntitiesPacket(entityId);
        for (Player viewer : world.getPlayers()) {
            CraftPlayerPackets.send(viewer, destroy);
        }
    }

    private void handlePlayerAttack(Player player, int attackedEntityId) {
        if (!player.isOnline() || player.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        World world = player.getWorld();
        WorldState state = statesByWorld.get(world.getUID());
        if (state == null) {
            return;
        }
        FakeEndermite mite = state.mitesByEntityId.get(attackedEntityId);
        if (mite == null) {
            return;
        }
        if (!isMiteSpawnPhaseActive(world, world.getUID())) {
            removeMite(world, state, attackedEntityId);
            return;
        }
        playEntitySound(world, mite.position, Sound.ENTITY_ENDERMITE_HURT, 0.9f, randomPitch(0.9f, 1.1f));
        double damage = computePlayerDamage(player);
        mite.health -= damage;
        if (mite.health <= 0) {
            playEntitySound(world, mite.position, Sound.ENTITY_ENDERMITE_DEATH, 0.9f, randomPitch(0.95f, 1.05f));
            broadcastEntityStatus(world, attackedEntityId, ENTITY_STATUS_DEATH);
            Bukkit.getScheduler().runTaskLater(plugin, () -> removeMite(world, state, attackedEntityId), 8L);
        } else {
            broadcastEntityStatus(world, attackedEntityId, ENTITY_STATUS_HURT);
        }
    }

    private static double computePlayerDamage(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return DEFAULT_ENDERMITE_MAX_HEALTH;
        }
        Attribute attackDamage = Attribute.ATTACK_DAMAGE;
        if (player.getAttribute(attackDamage) != null) {
            return Math.max(0.5, player.getAttribute(attackDamage).getValue());
        }
        return 1.0;
    }

    private void sendAllToPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        WorldState state = statesByWorld.get(world.getUID());
        if (state == null || state.mitesByEntityId.isEmpty()) {
            return;
        }
        for (FakeEndermite mite : state.mitesByEntityId.values()) {
            ClientboundAddEntityPacket spawnPacket = createSpawnPacket(mite.entityId, mite.uuid, mite.position, mite.yaw);
            CraftPlayerPackets.send(player, spawnPacket);
        }
    }

    private boolean isMiteSpawnPhaseActive(World world, UUID worldId) {
        if (!crystalManager.isEnhancedDragonFightWorld(worldId)) {
            return false;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null || battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) {
            return false;
        }
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon == null || !dragon.isValid() || dragon.isDead() || dragon.getHealth() <= 0) {
            return false;
        }
        return !crystalManager.isEnhancedDragonPhaseTransition(worldId);
    }

    private void clearWorld(World world) {
        if (world == null) {
            return;
        }
        WorldState removed = statesByWorld.remove(world.getUID());
        if (removed == null || removed.mitesByEntityId.isEmpty()) {
            return;
        }
        int[] ids = removed.mitesByEntityId.keySet().stream().mapToInt(Integer::intValue).toArray();
        ClientboundRemoveEntitiesPacket destroy = new ClientboundRemoveEntitiesPacket(ids);
        for (Player p : world.getPlayers()) {
            CraftPlayerPackets.send(p, destroy);
        }
    }

    private void updateMoveTowardTarget(World world, FakeEndermite mite, Player target) {
        Vector targetPos = target.getLocation().toVector();
        Vector delta = targetPos.clone().subtract(mite.position);
        delta.setY(0);
        double len = delta.length();
        if (len < 0.01) {
            return;
        }
        double step = Math.min(MOVE_SPEED_PER_TICK, len);
        Vector move = delta.normalize().multiply(step);
        Vector next = mite.position.clone().add(move);
        Vector grounded = resolveWalkableGroundNear(world, next, mite.position.getY());
        if (grounded != null) {
            next.setY(grounded.getY());
        }
        mite.position = next;
        mite.yaw = yawFromDirection(move);
    }

    private static float yawFromDirection(Vector direction) {
        if (direction.lengthSquared() < 0.0001) {
            return 0f;
        }
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private Vector resolveWalkableGround(World world, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return null;
        }
        int y = world.getHighestBlockYAt(blockX, blockZ);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        y = Math.max(minY + 1, Math.min(maxY - 1, y + 1));
        if (!isWalkableAt(world, blockX, y, blockZ)) {
            for (int dy = 1; dy <= 4; dy++) {
                if (isWalkableAt(world, blockX, y + dy, blockZ)) {
                    y = y + dy;
                    break;
                }
                if (isWalkableAt(world, blockX, y - dy, blockZ)) {
                    y = y - dy;
                    break;
                }
            }
        }
        if (!isWalkableAt(world, blockX, y, blockZ)) {
            return null;
        }
        return new Vector(x, y + HEIGHT_EPSILON, z);
    }

    private Vector resolveWalkableGroundNear(World world, Vector next, double currentY) {
        Vector snap = resolveWalkableGround(world, next.getX(), next.getZ());
        if (snap == null) {
            return null;
        }
        if (Math.abs(snap.getY() - currentY) > 1.25) {
            return null;
        }
        return snap;
    }

    private static boolean isWalkableAt(World world, int x, int y, int z) {
        Material feet = world.getBlockAt(x, y, z).getType();
        Material head = world.getBlockAt(x, y + 1, z).getType();
        Material ground = world.getBlockAt(x, y - 1, z).getType();
        return isPassable(feet) && isPassable(head) && isSolidGround(ground);
    }

    private static boolean isPassable(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private static boolean isSolidGround(Material material) {
        return material.isSolid();
    }

    private void broadcastEntityStatus(World world, int entityId, byte status) {
        ClientboundEntityEventPacket packet = ClientboundEntityEventPackets.forEntityId(entityId, status);
        for (Player viewer : world.getPlayers()) {
            CraftPlayerPackets.send(viewer, packet);
        }
    }

    private void tickAmbientSound(World world, FakeEndermite mite, long nowTick) {
        if (nowTick < mite.nextAmbientTick) {
            return;
        }
        playEntitySound(world, mite.position, Sound.ENTITY_ENDERMITE_AMBIENT, 0.75f, randomPitch(0.9f, 1.1f));
        mite.nextAmbientTick = nowTick + randomAmbientInterval();
    }

    private void playEntitySound(World world, Vector pos, Sound sound, float volume, float pitch) {
        org.bukkit.Location location = new org.bukkit.Location(world, pos.getX(), pos.getY(), pos.getZ());
        world.playSound(location, sound, SoundCategory.HOSTILE, volume, pitch);
    }

    private static long randomAmbientInterval() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return random.nextLong(AMBIENT_MIN_INTERVAL_TICKS, AMBIENT_MAX_INTERVAL_TICKS + 1);
    }

    private static float randomPitch(float min, float max) {
        return (float) (min + ThreadLocalRandom.current().nextDouble() * (max - min));
    }

    private long spawnIntervalByPhase(UUID worldId) {
        if (crystalManager.isEnhancedDragonPhaseTransition(worldId)) {
            return -1L;
        }
        if (crystalManager.isEnhancedDragonPhase2(worldId)) {
            return PHASE2_SPAWN_INTERVAL_TICKS;
        }
        return PHASE1_SPAWN_INTERVAL_TICKS;
    }

    private void spawnGuaranteedMiteOnDragonSpawn(World world, UUID worldId) {
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!isMiteSpawnPhaseActive(world, worldId)) {
            return;
        }
        WorldState state = statesByWorld.computeIfAbsent(worldId, ignored -> new WorldState());
        if (state.mitesByEntityId.size() >= MAX_MITES_PER_WORLD) {
            return;
        }
        Player target = pickTargetPlayer(world, null);
        if (target == null) {
            return;
        }
        long nowTick = Bukkit.getCurrentTick();
        int spawned = spawnWave(world, state, target, nowTick, SPAWN_COUNT_PER_WAVE);
        if (spawned > 0) {
            state.lastSpawnTick = nowTick;
        }
    }

    private int spawnWave(World world, WorldState state, Player primaryTarget, long nowTick, int count) {
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            if (state.mitesByEntityId.size() >= MAX_MITES_PER_WORLD) {
                break;
            }
            Player target = primaryTarget;
            if (target == null || !isValidTarget(world, target)) {
                target = pickTargetPlayer(world, null);
                if (target == null) {
                    break;
                }
            }
            Vector spawnPos = chooseSpawnPositionNear(world, target);
            if (spawnPos == null) {
                continue;
            }
            FakeEndermite mite = spawnMite(world, target, spawnPos, nowTick);
            if (mite == null) {
                continue;
            }
            state.mitesByEntityId.put(mite.entityId, mite);
            spawned++;
        }
        return spawned;
    }
}

package com.rakku212.betterboss.betterenderdragon.packet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;

import com.rakku212.betterboss.betterenderdragon.ritual.EnhancedDragonRitualEvaluator;
import com.rakku212.util.packet.CraftPlayerPackets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public final class EnhancedDragonCryingObsidianPillarCoordinator implements Listener {

    private static final long CLEANUP_PERIOD_TICKS = 40L;
    private static final long AFTER_CHUNK_PACKET_DELAY_TICKS = 2L;
    private static final long JOIN_FALLBACK_RESEND_DELAY_TICKS = 40L;
    private static final double MIN_END_HORIZONTAL_SEND_RADIUS = 320.0;
    private static final double CRYING_FRACTION_MIN = 0.22;
    private static final double CRYING_FRACTION_MAX = 0.55;

    private final JavaPlugin plugin;
    private final Predicate<UUID> isEnhancedDragonFightWorld;
    private final Map<UUID, Set<BlockPos>> fakeCryingPositionsByWorld = new ConcurrentHashMap<>();

    private EnhancedDragonCryingObsidianPillarCoordinator(
            JavaPlugin plugin,
            Predicate<UUID> isEnhancedDragonFightWorld
    ) {
        this.plugin = plugin;
        this.isEnhancedDragonFightWorld = isEnhancedDragonFightWorld;
    }

    public static void register(JavaPlugin plugin, Predicate<UUID> isEnhancedDragonFightWorld) {
        EnhancedDragonCryingObsidianPillarCoordinator coordinator =
                new EnhancedDragonCryingObsidianPillarCoordinator(plugin, isEnhancedDragonFightWorld);
        plugin.getServer().getPluginManager().registerEvents(coordinator, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, coordinator::tickCleanup, CLEANUP_PERIOD_TICKS, CLEANUP_PERIOD_TICKS);
    }

    private void tickCleanup() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) {
                continue;
            }
            UUID worldId = world.getUID();
            if (!isEnhancedDragonFightWorld.test(worldId)) {
                clearOverlay(world, worldId);
                continue;
            }
            DragonBattle battle = world.getEnderDragonBattle();
            if (!isEndFightActiveForCryingPackets(battle)) {
                clearOverlay(world, worldId);
            }
        }
    }

    private static boolean isEndFightActiveForCryingPackets(DragonBattle battle) {
        if (battle == null) {
            return false;
        }
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon != null && dragon.isValid()) {
            return true;
        }
        return battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID joinPlayerId = player.getUniqueId();
        UUID joinWorldId = world.getUID();
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    Player p = Bukkit.getPlayer(joinPlayerId);
                    if (p == null || !p.isOnline()) {
                        return;
                    }
                    if (!p.getWorld().getUID().equals(joinWorldId)) {
                        return;
                    }
                    resendFakeCryingOverlayToPlayerIfFightActive(p);
                },
                JOIN_FALLBACK_RESEND_DELAY_TICKS
        );
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World to = player.getWorld();
        if (to.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID wcPlayerId = player.getUniqueId();
        UUID wcWorldId = to.getUID();
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    Player p = Bukkit.getPlayer(wcPlayerId);
                    if (p == null || !p.isOnline()) {
                        return;
                    }
                    if (!p.getWorld().getUID().equals(wcWorldId)) {
                        return;
                    }
                    resendFakeCryingOverlayToPlayerIfFightActive(p);
                },
                JOIN_FALLBACK_RESEND_DELAY_TICKS
        );
    }

    @EventHandler
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID worldId = world.getUID();
        Player chunkPlayer = event.getPlayer();
        if (!isEnhancedDragonFightWorld.test(worldId)) {
            return;
        }
        Set<BlockPos> positions = fakeCryingPositionsByWorld.get(worldId);
        if (positions == null || positions.isEmpty()) {
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (!isEndFightActiveForCryingPackets(battle)) {
            return;
        }
        Chunk chunk = event.getChunk();
        List<BlockPos> inChunk = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (blockInChunk(pos, chunk)) {
                inChunk.add(pos);
            }
        }
        if (inChunk.isEmpty()) {
            return;
        }
        List<BlockPos> toSend = new ArrayList<>(inChunk);
        UUID playerId = chunkPlayer.getUniqueId();
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p == null || !p.isOnline()) {
                        return;
                    }
                    if (!p.getWorld().getUID().equals(worldId)) {
                        return;
                    }
                    World w = p.getWorld();
                    if (!isEnhancedDragonFightWorld.test(worldId)) {
                        return;
                    }
                    DragonBattle b = w.getEnderDragonBattle();
                    if (!isEndFightActiveForCryingPackets(b)) {
                        return;
                    }
                    sendCryingToPlayer(w, p, toSend);
                },
                AFTER_CHUNK_PACKET_DELAY_TICKS
        );
    }

    private void resendFakeCryingOverlayToPlayerIfFightActive(Player player) {
        if (!player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID worldId = world.getUID();
        if (!isEnhancedDragonFightWorld.test(worldId)) {
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (!isEndFightActiveForCryingPackets(battle)) {
            return;
        }
        Set<BlockPos> positions = fakeCryingPositionsByWorld.get(worldId);
        if (positions == null || positions.isEmpty()) {
            rebuildOverlayFromCurrentCrystals(world, worldId, battle);
            positions = fakeCryingPositionsByWorld.get(worldId);
            if (positions == null || positions.isEmpty()) {
                return;
            }
        }
        sendCryingToPlayer(world, player, new ArrayList<>(positions));
    }

    private void rebuildOverlayFromCurrentCrystals(World world, UUID worldId, DragonBattle battle) {
        Location portal = battle.getEndPortalLocation();
        Set<BlockPos> rebuilt = ConcurrentHashMap.newKeySet();
        for (EnderCrystal crystal : world.getEntitiesByClass(EnderCrystal.class)) {
            if (!crystal.isValid()) {
                continue;
            }
            if (portal != null && EnhancedDragonRitualEvaluator.isInRitualZone(crystal, portal)) {
                continue;
            }
            Location cl = crystal.getLocation();
            List<BlockPos> column = collectObsidianColumnFromCrystal(world, cl.getBlockX(), cl.getBlockZ(), cl.getBlockY());
            if (column.isEmpty()) {
                continue;
            }
            List<BlockPos> fullPillar = expandCenterColumnToFullPillarObsidian(world, column);
            List<BlockPos> sideFacingAir = filterObsidianPillarSideBlocksAdjacentHorizontalAir(world, fullPillar);
            if (sideFacingAir.isEmpty()) {
                continue;
            }
            rebuilt.addAll(randomSubsetForColumn(sideFacingAir));
        }
        if (!rebuilt.isEmpty()) {
            fakeCryingPositionsByWorld.put(worldId, rebuilt);
        }
    }

    private static boolean blockInChunk(BlockPos pos, Chunk chunk) {
        return Math.floorDiv(pos.getX(), 16) == chunk.getX()
                && Math.floorDiv(pos.getZ(), 16) == chunk.getZ();
    }

    private static final int[][] FACE_NEIGHBOR_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @EventHandler
    public void onBlockPlaceNearFakeCrying(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        World world = placed.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID worldId = world.getUID();
        if (!isEnhancedDragonFightWorld.test(worldId)) {
            return;
        }
        Set<BlockPos> tracked = fakeCryingPositionsByWorld.get(worldId);
        if (tracked == null || tracked.isEmpty()) {
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (!isEndFightActiveForCryingPackets(battle)) {
            return;
        }
        int px = placed.getX();
        int py = placed.getY();
        int pz = placed.getZ();
        List<BlockPos> toRefresh = new ArrayList<>(6);
        for (int[] d : FACE_NEIGHBOR_OFFSETS) {
            BlockPos n = new BlockPos(px + d[0], py + d[1], pz + d[2]);
            if (!tracked.contains(n)) {
                continue;
            }
            if (!world.isChunkLoaded(n.getX() >> 4, n.getZ() >> 4)) {
                continue;
            }
            if (world.getBlockAt(n.getX(), n.getY(), n.getZ()).getType() == Material.OBSIDIAN) {
                toRefresh.add(n);
            }
        }
        if (toRefresh.isEmpty()) {
            return;
        }
        List<BlockPos> copy = new ArrayList<>(toRefresh);
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (!isEnhancedDragonFightWorld.test(worldId)) {
                        return;
                    }
                    World w = Bukkit.getWorld(worldId);
                    if (w == null) {
                        return;
                    }
                    DragonBattle b = w.getEnderDragonBattle();
                    if (!isEndFightActiveForCryingPackets(b)) {
                        return;
                    }
                    sendCryingToPlayersInEnd(w, copy);
                },
                AFTER_CHUNK_PACKET_DELAY_TICKS
        );
    }

    @EventHandler
    public void onPlayerInteractFakeCrying(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.OBSIDIAN) {
            return;
        }
        World world = block.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID worldId = world.getUID();
        if (!isEnhancedDragonFightWorld.test(worldId)) {
            return;
        }
        Set<BlockPos> tracked = fakeCryingPositionsByWorld.get(worldId);
        if (tracked == null || tracked.isEmpty()) {
            return;
        }
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        if (!tracked.contains(pos)) {
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (!isEndFightActiveForCryingPackets(battle)) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p == null || !p.isOnline()) {
                        return;
                    }
                    if (!p.getWorld().getUID().equals(worldId)) {
                        return;
                    }
                    World w = p.getWorld();
                    if (!isEnhancedDragonFightWorld.test(worldId)) {
                        return;
                    }
                    DragonBattle b = w.getEnderDragonBattle();
                    if (!isEndFightActiveForCryingPackets(b)) {
                        return;
                    }
                    Set<BlockPos> still = fakeCryingPositionsByWorld.get(worldId);
                    if (still == null || !still.contains(pos)) {
                        return;
                    }
                    if (w.getBlockAt(pos.getX(), pos.getY(), pos.getZ()).getType() != Material.OBSIDIAN) {
                        return;
                    }
                    sendCryingToPlayer(w, p, List.of(pos));
                },
                1L
        );
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        World world = crystal.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID worldId = world.getUID();
        if (!isEnhancedDragonFightWorld.test(worldId)) {
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (!isEndFightActiveForCryingPackets(battle)) {
            return;
        }
        Location portal = battle.getEndPortalLocation();
        if (portal != null && EnhancedDragonRitualEvaluator.isInRitualZone(crystal, portal)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> applyCryingForSpawnedCrystal(world, worldId, crystal, battle));
    }

    private void applyCryingForSpawnedCrystal(
            World world,
            UUID worldId,
            EnderCrystal crystal,
            DragonBattle battle
    ) {
        if (!crystal.isValid()) {
            return;
        }
        if (!isEnhancedDragonFightWorld.test(worldId)) {
            return;
        }
        if (!isEndFightActiveForCryingPackets(battle)) {
            return;
        }
        Location portal = battle.getEndPortalLocation();
        if (portal != null && EnhancedDragonRitualEvaluator.isInRitualZone(crystal, portal)) {
            return;
        }
        Location cl = crystal.getLocation();
        int bx = cl.getBlockX();
        int bz = cl.getBlockZ();
        int crystalY = cl.getBlockY();
        List<BlockPos> column = collectObsidianColumnFromCrystal(world, bx, bz, crystalY);
        if (column.isEmpty()) {
            return;
        }
        List<BlockPos> fullPillar = expandCenterColumnToFullPillarObsidian(world, column);
        List<BlockPos> sideFacingAir = filterObsidianPillarSideBlocksAdjacentHorizontalAir(world, fullPillar);
        if (sideFacingAir.isEmpty()) {
            return;
        }
        List<BlockPos> pick = randomSubsetForColumn(sideFacingAir);
        if (pick.isEmpty()) {
            return;
        }
        Set<BlockPos> acc = fakeCryingPositionsByWorld.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet());
        acc.addAll(pick);
        List<BlockPos> pickCopy = new ArrayList<>(pick);
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (!isEnhancedDragonFightWorld.test(worldId)) {
                        return;
                    }
                    DragonBattle b = world.getEnderDragonBattle();
                    if (!isEndFightActiveForCryingPackets(b)) {
                        return;
                    }
                    sendCryingToPlayersInEnd(world, pickCopy);
                },
                AFTER_CHUNK_PACKET_DELAY_TICKS
        );
    }

    @EventHandler
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon)) {
            return;
        }
        World world = event.getEntity().getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        clearOverlay(world, world.getUID());
    }

    private void clearOverlay(World world, UUID worldId) {
        Set<BlockPos> positions = fakeCryingPositionsByWorld.remove(worldId);
        if (positions != null && !positions.isEmpty()) {
            sendObsidianToPlayersInEnd(world, new ArrayList<>(positions));
        }
    }

    private static List<BlockPos> randomSubsetForColumn(List<BlockPos> column) {
        if (column.isEmpty()) {
            return List.of();
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<BlockPos> shuffled = new ArrayList<>(column);
        Collections.shuffle(shuffled, rng);
        double frac = CRYING_FRACTION_MIN + rng.nextDouble() * (CRYING_FRACTION_MAX - CRYING_FRACTION_MIN);
        int count = Math.max(1, (int) Math.ceil(shuffled.size() * frac));
        count = Math.min(count, shuffled.size());
        return new ArrayList<>(shuffled.subList(0, count));
    }

    /** 中心列から各Yで水平4近傍につながる黒曜石を列挙 */
    private static final int PILLAR_HORIZONTAL_FILL_MAX_RADIUS = 8;

    private static List<BlockPos> expandCenterColumnToFullPillarObsidian(World world, List<BlockPos> centerColumn) {
        if (centerColumn.isEmpty()) {
            return List.of();
        }
        int ox = centerColumn.get(0).getX();
        int oz = centerColumn.get(0).getZ();
        Set<BlockPos> merged = new HashSet<>();
        for (BlockPos v : centerColumn) {
            floodFillObsidianHorizontalSlice(world, ox, v.getY(), oz, merged);
        }
        return new ArrayList<>(merged);
    }

    private static void floodFillObsidianHorizontalSlice(
            World world,
            int sx,
            int y,
            int sz,
            Set<BlockPos> merged
    ) {
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[] {sx, sz});
        seen.add(packXZ(sx, sz));
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int x = p[0];
            int z = p[1];
            if (Math.abs(x - sx) > PILLAR_HORIZONTAL_FILL_MAX_RADIUS
                    || Math.abs(z - sz) > PILLAR_HORIZONTAL_FILL_MAX_RADIUS) {
                continue;
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            if (world.getBlockAt(x, y, z).getType() != Material.OBSIDIAN) {
                continue;
            }
            merged.add(new BlockPos(x, y, z));
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + d[0];
                int nz = z + d[1];
                long key = packXZ(nx, nz);
                if (seen.add(key)) {
                    queue.add(new int[] {nx, nz});
                }
            }
        }
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static List<BlockPos> filterObsidianPillarSideBlocksAdjacentHorizontalAir(
            World world,
            List<BlockPos> pillarObsidian
    ) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos v : pillarObsidian) {
            int x = v.getX();
            int y = v.getY();
            int z = v.getZ();
            if (hasHorizontalNeighborStrictAir(world, x, y, z)) {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean hasHorizontalNeighborStrictAir(World world, int x, int y, int z) {
        return isStrictAir(world, x + 1, y, z)
                || isStrictAir(world, x - 1, y, z)
                || isStrictAir(world, x, y, z + 1)
                || isStrictAir(world, x, y, z - 1);
    }

    private static boolean isStrictAir(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        Material m = world.getBlockAt(x, y, z).getType();
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    /** クリスタル～最初の黒曜石の間 */
    private static final int MAX_PILLAR_CAP_DESCENT = 32;

    private static boolean isMaterialAboveObsidianPillarShaft(Material type) {
        return type == Material.FIRE
                || type == Material.SOUL_FIRE
                || type == Material.BEDROCK
                || type == Material.IRON_BARS
                || type == Material.AIR
                || type == Material.CAVE_AIR
                || type == Material.VOID_AIR
                || type == Material.LIGHT;
    }

    private static List<BlockPos> collectObsidianColumnFromCrystal(World world, int bx, int bz, int crystalBlockY) {
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return List.of();
        }
        int minY = world.getMinHeight();
        int y = crystalBlockY;
        while (y >= minY && crystalBlockY - y <= MAX_PILLAR_CAP_DESCENT) {
            Block block = world.getBlockAt(bx, y, bz);
            if (!block.getChunk().isLoaded()) {
                return List.of();
            }
            Material type = block.getType();
            if (type == Material.OBSIDIAN) {
                break;
            }
            if (isMaterialAboveObsidianPillarShaft(type)) {
                y--;
                continue;
            }
            return List.of();
        }
        if (y < minY || crystalBlockY - y > MAX_PILLAR_CAP_DESCENT) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>();
        for (; y >= minY; y--) {
            Block block = world.getBlockAt(bx, y, bz);
            Material type = block.getType();
            if (type == Material.OBSIDIAN) {
                out.add(new BlockPos(bx, y, bz));
            } else if (type == Material.BEDROCK) {
                break;
            } else {
                break;
            }
        }
        return out;
    }

    private int sendCryingToPlayersInEnd(World world, List<BlockPos> positions) {
        int total = 0;
        for (Player player : world.getPlayers()) {
            total += sendCryingToPlayer(world, player, positions);
        }
        return total;
    }

    private int sendCryingToPlayer(World world, Player player, List<BlockPos> positions) {
        if (!player.isOnline()) {
            return 0;
        }
        BlockState crying = ((CraftBlockData) Material.CRYING_OBSIDIAN.createBlockData()).getState();
        Location eye = player.getLocation();
        double maxSq = horizontalViewRadiusSq(world, player);
        int total = 0;
        for (BlockPos pos : positions) {
            if (horizontalDistSq(eye, pos) > maxSq) {
                continue;
            }
            CraftPlayerPackets.send(player, new ClientboundBlockUpdatePacket(pos, crying));
            total++;
        }
        return total;
    }

    private int sendObsidianToPlayersInEnd(World world, List<BlockPos> positions) {
        int total = 0;
        BlockState obsidian = ((CraftBlockData) Material.OBSIDIAN.createBlockData()).getState();
        for (Player player : world.getPlayers()) {
            Location eye = player.getLocation();
            double maxSq = horizontalViewRadiusSq(world, player);
            for (BlockPos pos : positions) {
                if (horizontalDistSq(eye, pos) > maxSq) {
                    continue;
                }
                CraftPlayerPackets.send(player, new ClientboundBlockUpdatePacket(pos, obsidian));
                total++;
            }
        }
        return total;
    }

    private static double horizontalViewRadiusSq(World world, Player player) {
        int chunks = world.getViewDistance();
        try {
            chunks = Math.min(chunks, player.getClientViewDistance());
        } catch (NoSuchMethodError ignored) {
        }
        double r = Math.max(MIN_END_HORIZONTAL_SEND_RADIUS, chunks * 16.0);
        return r * r;
    }

    private static double horizontalDistSq(Location loc, BlockPos pos) {
        double dx = loc.getX() - (pos.getX() + 0.5);
        double dz = loc.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }
}

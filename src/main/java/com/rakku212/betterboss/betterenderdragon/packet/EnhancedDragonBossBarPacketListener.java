package com.rakku212.betterboss.betterenderdragon.packet;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.adventure.PaperAdventure;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;

import com.rakku212.betterboss.betterenderdragon.manager.BetterEnderDragonCrystalManager;
import com.rakku212.util.i18n.LocaleComponents;
import com.rakku212.util.packet.AdventureTranslatableKeys;
import com.rakku212.util.packet.BossBarPacketNms;
import com.rakku212.util.packet.PlayerChannelHooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

public final class EnhancedDragonBossBarPacketListener implements Listener {

    private static final String CHANNEL = "betterboss_dragon_bossbar";
    private static final String VANILLA_ENDER_DRAGON_NAME_KEY = "entity.minecraft.ender_dragon";
    private static final Component ENHANCED_DRAGON_BOSS_TITLE_JA = Component.text("強化されたエンダードラゴン");
    private static final Component ENHANCED_DRAGON_BOSS_TITLE_EN = Component.text("Enhanced Ender Dragon");

    private static final BossBar.Color SECOND_FORM_BOSS_BAR_COLOR = BossBar.Color.PURPLE;

    private final JavaPlugin plugin;
    private final BetterEnderDragonCrystalManager crystalManager;
    private final Map<UUID, UUID> endDragonBossBarIdByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, BossEvent.BossBarOverlay> endDragonBossBarOverlayByWorld = new ConcurrentHashMap<>();

    public EnhancedDragonBossBarPacketListener(JavaPlugin plugin, BetterEnderDragonCrystalManager crystalManager) {
        this.plugin = plugin;
        this.crystalManager = crystalManager;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            inject(p);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        inject(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerChannelHooks.remove(event.getPlayer(), CHANNEL);
    }

    private void inject(Player player) {
        if (!(player instanceof CraftPlayer)) {
            return;
        }
        PlayerChannelHooks.addBeforePacketHandler(player, CHANNEL, new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (msg instanceof ClientboundBossEventPacket boss) {
                    msg = transformOutgoing(player, boss);
                }
                super.write(ctx, msg, promise);
            }
        });
    }

    public void clearEnderDragonBossBarId(UUID worldId) {
        if (worldId != null) {
            endDragonBossBarIdByWorld.remove(worldId);
            endDragonBossBarOverlayByWorld.remove(worldId);
        }
    }

    public void broadcastTransitionHealth(World world) {
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID worldId = world.getUID();
        UUID barId = endDragonBossBarIdByWorld.get(worldId);
        if (barId == null) {
            return;
        }
        float h = crystalManager.getCachedEnhancedDragonBossBarHealth(worldId);
        if (h < 0f) {
            return;
        }
        ClientboundBossEventPacket packet = BossBarPacketNms.updateProgressPacket(barId, h);
        for (Player p : world.getPlayers()) {
            ((CraftPlayer) p).getHandle().connection.send(packet);
        }
    }

    public void broadcastTransitionBossBarStyleEnter(World world) {
        broadcastBossBarStyle(world, SECOND_FORM_BOSS_BAR_COLOR);
    }

    private void broadcastBossBarStyle(World world, BossBar.Color color) {
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID worldId = world.getUID();
        UUID barId = endDragonBossBarIdByWorld.get(worldId);
        if (barId == null) {
            return;
        }
        BossEvent.BossBarOverlay overlay = endDragonBossBarOverlayByWorld.getOrDefault(
                worldId,
                BossEvent.BossBarOverlay.PROGRESS
        );
        ClientboundBossEventPacket packet = BossBarPacketNms.updateStylePacket(barId, color, overlay);
        for (Player p : world.getPlayers()) {
            ((CraftPlayer) p).getHandle().connection.send(packet);
        }
    }

    private ClientboundBossEventPacket transformOutgoing(Player player, ClientboundBossEventPacket in) {
        AtomicReference<ClientboundBossEventPacket> replaced = new AtomicReference<>();
        in.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(
                    UUID id,
                    net.minecraft.network.chat.Component name,
                    float progress,
                    BossEvent.BossBarColor color,
                    BossEvent.BossBarOverlay overlay,
                    boolean darkenScreen,
                    boolean playMusic,
                    boolean createWorldFog
            ) {
                ClientboundBossEventPacket out = handleAdd(
                        player,
                        id,
                        name,
                        progress,
                        color,
                        overlay,
                        darkenScreen,
                        playMusic,
                        createWorldFog
                );
                if (out != null) {
                    replaced.set(out);
                }
            }

            @Override
            public void updateName(UUID id, net.minecraft.network.chat.Component name) {
                ClientboundBossEventPacket out = handleUpdateName(player, id, name);
                if (out != null) {
                    replaced.set(out);
                }
            }

            @Override
            public void updateProgress(UUID id, float progress) {
                ClientboundBossEventPacket out = handleUpdateProgress(player, id, progress);
                if (out != null) {
                    replaced.set(out);
                }
            }

            @Override
            public void updateStyle(UUID id, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
                ClientboundBossEventPacket out = handleUpdateStyle(player, id, color, overlay);
                if (out != null) {
                    replaced.set(out);
                }
            }
        });
        ClientboundBossEventPacket out = replaced.get();
        return out != null ? out : in;
    }

    private void cacheDragonBarIdIfVanillaTitle(
            Player player,
            UUID barId,
            net.minecraft.network.chat.Component title,
            boolean alsoCacheOverlay,
            BossEvent.BossBarOverlay overlay
    ) {
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        UUID worldId = world.getUID();
        Component adventureTitle = PaperAdventure.asAdventure(title);
        if (!AdventureTranslatableKeys.matchesTranslatableKey(adventureTitle, VANILLA_ENDER_DRAGON_NAME_KEY)) {
            return;
        }
        endDragonBossBarIdByWorld.put(worldId, barId);
        if (alsoCacheOverlay) {
            endDragonBossBarOverlayByWorld.put(worldId, overlay);
        }
    }

    private @Nullable ClientboundBossEventPacket handleAdd(
            Player player,
            UUID barId,
            net.minecraft.network.chat.Component name,
            float progress,
            BossEvent.BossBarColor color,
            BossEvent.BossBarOverlay overlay,
            boolean darkenScreen,
            boolean playMusic,
            boolean createWorldFog
    ) {
        cacheDragonBarIdIfVanillaTitle(player, barId, name, true, overlay);
        if (!shouldRewriteBossBar(player, barId)) {
            return null;
        }
        net.minecraft.network.chat.Component outName = PaperAdventure.asVanilla(LocaleComponents.jaOrDefault(
                player,
                ENHANCED_DRAGON_BOSS_TITLE_JA,
                ENHANCED_DRAGON_BOSS_TITLE_EN
        ));
        float outProgress = progress;
        float display = crystalManager.getCachedEnhancedDragonBossBarHealth(player.getWorld().getUID());
        if (display >= 0f) {
            outProgress = display;
        }
        BossEvent.BossBarColor outColor = color;
        if (useSecondFormBossBarColor(player.getWorld().getUID())) {
            outColor = BossEvent.BossBarColor.PURPLE;
        }
        BossEvent event = new BossEvent(barId, outName, outColor, overlay) {
        };
        event.setProgress(outProgress);
        event.setDarkenScreen(darkenScreen);
        event.setPlayBossMusic(playMusic);
        event.setCreateWorldFog(createWorldFog);
        return ClientboundBossEventPacket.createAddPacket(event);
    }

    private @Nullable ClientboundBossEventPacket handleUpdateName(Player player, UUID barId, net.minecraft.network.chat.Component name) {
        cacheDragonBarIdIfVanillaTitle(player, barId, name, false, BossEvent.BossBarOverlay.PROGRESS);
        if (!shouldRewriteBossBar(player, barId)) {
            return null;
        }
        net.minecraft.network.chat.Component outName = PaperAdventure.asVanilla(LocaleComponents.jaOrDefault(
                player,
                ENHANCED_DRAGON_BOSS_TITLE_JA,
                ENHANCED_DRAGON_BOSS_TITLE_EN
        ));
        BossEvent stub = new BossEvent(
                barId,
                outName,
                BossEvent.BossBarColor.PINK,
                BossEvent.BossBarOverlay.PROGRESS
        ) {
        };
        stub.setName(outName);
        return ClientboundBossEventPacket.createUpdateNamePacket(stub);
    }

    private @Nullable ClientboundBossEventPacket handleUpdateProgress(Player player, UUID barId, float progress) {
        if (!shouldRewriteBossBar(player, barId)) {
            return null;
        }
        float outProgress = progress;
        float display = crystalManager.getCachedEnhancedDragonBossBarHealth(player.getWorld().getUID());
        if (display >= 0f) {
            outProgress = display;
        }
        BossEvent stub = new BossEvent(
                barId,
                net.minecraft.network.chat.Component.empty(),
                BossEvent.BossBarColor.PINK,
                BossEvent.BossBarOverlay.PROGRESS
        ) {
        };
        stub.setProgress(outProgress);
        return ClientboundBossEventPacket.createUpdateProgressPacket(stub);
    }

    private @Nullable ClientboundBossEventPacket handleUpdateStyle(
            Player player,
            UUID barId,
            BossEvent.BossBarColor color,
            BossEvent.BossBarOverlay overlay
    ) {
        if (!shouldRewriteBossBar(player, barId)) {
            return null;
        }
        BossEvent.BossBarColor outColor = color;
        if (useSecondFormBossBarColor(player.getWorld().getUID())) {
            outColor = BossEvent.BossBarColor.PURPLE;
        }
        BossEvent stub = new BossEvent(
                barId,
                net.minecraft.network.chat.Component.empty(),
                outColor,
                overlay
        ) {
        };
        stub.setColor(outColor);
        stub.setOverlay(overlay);
        return ClientboundBossEventPacket.createUpdateStylePacket(stub);
    }

    private boolean shouldRewriteBossBar(Player player, UUID barId) {
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return false;
        }
        UUID worldId = world.getUID();
        if (!crystalManager.isEnhancedDragonFightWorld(worldId)) {
            return false;
        }
        if (crystalManager.isEnhancedEnderDragonRespawnActive(world)) {
            return false;
        }
        UUID knownDragonBar = endDragonBossBarIdByWorld.get(worldId);
        return knownDragonBar != null && knownDragonBar.equals(barId);
    }

    private boolean useSecondFormBossBarColor(UUID worldId) {
        return crystalManager.isEnhancedDragonPhaseTransition(worldId)
                || crystalManager.isEnhancedDragonPhase2(worldId);
    }
}

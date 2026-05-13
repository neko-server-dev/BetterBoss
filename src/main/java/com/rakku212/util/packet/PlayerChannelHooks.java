package com.rakku212.util.packet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public final class PlayerChannelHooks {

    private static final String PACKET_HANDLER = "packet_handler";

    public static void addBeforePacketHandler(Player player, String handlerName, ChannelHandler handler) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        if (channel.pipeline().get(handlerName) != null) {
            channel.pipeline().remove(handlerName);
        }
        if (channel.pipeline().get(PACKET_HANDLER) != null) {
            channel.pipeline().addBefore(PACKET_HANDLER, handlerName, handler);
        } else {
            channel.pipeline().addLast(handlerName, handler);
        }
    }

    public static void remove(Player player, String handlerName) {
        if (player == null) {
            return;
        }
        try {
            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            if (channel.pipeline().get(handlerName) != null) {
                channel.pipeline().remove(handlerName);
            }
        } catch (Throwable ignored) {
        }
    }
}

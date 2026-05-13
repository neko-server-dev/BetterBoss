package com.rakku212.util.packet;

import net.minecraft.network.protocol.Packet;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public final class CraftPlayerPackets {

    public static void send(Player player, Packet<?> packet) {
        if (player == null || !player.isOnline()) {
            return;
        }
        ((CraftPlayer) player).getHandle().connection.send(packet);
    }
}

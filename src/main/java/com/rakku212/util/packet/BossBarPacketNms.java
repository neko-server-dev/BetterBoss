package com.rakku212.util.packet;

import net.kyori.adventure.bossbar.BossBar;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;

public final class BossBarPacketNms {

    public static BossEvent.BossBarColor toNms(BossBar.Color color) {
        if (color == null) {
            return BossEvent.BossBarColor.WHITE;
        }
        return switch (color) {
            case PINK -> BossEvent.BossBarColor.PINK;
            case BLUE -> BossEvent.BossBarColor.BLUE;
            case RED -> BossEvent.BossBarColor.RED;
            case GREEN -> BossEvent.BossBarColor.GREEN;
            case YELLOW -> BossEvent.BossBarColor.YELLOW;
            case PURPLE -> BossEvent.BossBarColor.PURPLE;
            case WHITE -> BossEvent.BossBarColor.WHITE;
        };
    }

    public static BossEvent.BossBarOverlay toNms(BossBar.Overlay overlay) {
        if (overlay == null) {
            return BossEvent.BossBarOverlay.PROGRESS;
        }
        return switch (overlay) {
            case PROGRESS -> BossEvent.BossBarOverlay.PROGRESS;
            case NOTCHED_6 -> BossEvent.BossBarOverlay.NOTCHED_6;
            case NOTCHED_10 -> BossEvent.BossBarOverlay.NOTCHED_10;
            case NOTCHED_12 -> BossEvent.BossBarOverlay.NOTCHED_12;
            case NOTCHED_20 -> BossEvent.BossBarOverlay.NOTCHED_20;
        };
    }

    public static ClientboundBossEventPacket updateProgressPacket(java.util.UUID barId, float health) {
        BossEvent stub = new BossEvent(
                barId,
                net.minecraft.network.chat.Component.empty(),
                BossEvent.BossBarColor.PINK,
                BossEvent.BossBarOverlay.PROGRESS
        ) {
        };
        stub.setProgress(health);
        return ClientboundBossEventPacket.createUpdateProgressPacket(stub);
    }

    public static ClientboundBossEventPacket updateStylePacket(
            java.util.UUID barId,
            BossBar.Color adventureColor,
            BossEvent.BossBarOverlay overlay
    ) {
        BossEvent stub = new BossEvent(
                barId,
                net.minecraft.network.chat.Component.empty(),
                toNms(adventureColor),
                overlay != null ? overlay : BossEvent.BossBarOverlay.PROGRESS
        ) {
        };
        return ClientboundBossEventPacket.createUpdateStylePacket(stub);
    }
}

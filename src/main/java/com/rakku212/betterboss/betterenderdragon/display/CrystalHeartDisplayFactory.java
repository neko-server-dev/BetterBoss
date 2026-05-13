package com.rakku212.betterboss.betterenderdragon.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.rakku212.util.state.BossHeartStackState;

import java.util.UUID;

public final class CrystalHeartDisplayFactory {

    public static final int MAX_HEARTS = 3;
    private static final float DISPLAY_SCALE = 0.75f;
    private static final double DISPLAY_Y_OFFSET = 2.3;

    /**
     * ハート表示エンティティを生成
     * @param crystal 柱クリスタル
     * @param heartsRemaining 残りハート数
     * @return ハート表示エンティティのUUID
     */
    public UUID spawnHeartDisplay(EnderCrystal crystal, int heartsRemaining) {
        World world = crystal.getWorld();
        TextDisplay display = (TextDisplay) world.spawnEntity(
                crystal.getLocation().clone().add(0, DISPLAY_Y_OFFSET, 0),
                EntityType.TEXT_DISPLAY
        );
        display.text(heartsComponent(heartsRemaining));
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(true);
        display.setShadowed(true);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setInterpolationDuration(0);
        display.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                new Quaternionf(),
                new Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE),
                new Quaternionf()
        ));
        return display.getUniqueId();
    }

    /**
     * ハート表示エンティティのテキストを更新
     * @param displayId ハート表示エンティティのUUID
     * @param heartsRemaining 残りハート数
     */
    public void updateHeartDisplayText(UUID displayId, int heartsRemaining) {
        if (displayId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(displayId);
        if (entity instanceof TextDisplay display) {
            display.text(heartsComponent(heartsRemaining));
        }
    }

    /**
     * ハート表示エンティティを解決または再生成
     * @param crystal 柱クリスタル
     * @param state 柱クリスタルの状態
     * @return ハート表示エンティティ
     */
    public TextDisplay resolveOrRespawnDisplay(JavaPlugin plugin, EnderCrystal crystal, BossHeartStackState state) {
        if (state.displayId != null) {
            Entity entity = Bukkit.getEntity(state.displayId);
            if (entity instanceof TextDisplay display && display.isValid()) {
                return display;
            }
            if (entity != null) {
                removeDisplayEntity(plugin, entity.getUniqueId());
            }
        }
        state.displayId = spawnHeartDisplay(crystal, state.heartsRemaining);
        Entity spawned = Bukkit.getEntity(state.displayId);
        return spawned instanceof TextDisplay display ? display : null;
    }

    /**
     * ハート表示エンティティを柱クリスタルの上にテレポート
     * @param display ハート表示エンティティ
     * @param crystal 柱クリスタル
     */
    public void teleportDisplayAboveCrystal(TextDisplay display, EnderCrystal crystal) {
        display.teleport(crystal.getLocation().clone().add(0, DISPLAY_Y_OFFSET, 0));
    }

    /**
     * ハート表示エンティティを削除
     * @param displayId ハート表示エンティティのUUID
     */
    public static void removeDisplayEntity(JavaPlugin plugin, UUID displayId) {
        if (displayId == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity entity = Bukkit.getEntity(displayId);
            if (entity != null) {
                entity.remove();
            }
        });
    }

    /**
     * ハート表示エンティティのテキストを生成
     * @param remaining 残りハート数
     * @return ハート表示エンティティのテキスト
     */
    public static Component heartsComponent(int remaining) {
        Component result = Component.empty();
        for (int i = 0; i < MAX_HEARTS; i++) {
            if (i > 0) {
                result = result.append(Component.text(" "));
            }
            boolean filled = i < remaining;
            result = result.append(Component.text(
                    "♥",
                    filled ? NamedTextColor.RED : NamedTextColor.DARK_GRAY
            ));
        }
        return result;
    }
}

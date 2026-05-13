package com.rakku212.betterboss.betterenderdragon.persistence;

import org.bukkit.plugin.Plugin;

import com.rakku212.util.persistence.JsonBossPhasePersistence;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class EnhancedDragonFightWorldStore {

    private final JsonBossPhasePersistence fightJson;

    public record PersistedBossPhaseState(String phaseName, double originalMaxHealth) {
    }

    public EnhancedDragonFightWorldStore(Plugin plugin) {
        if (!plugin.getDataFolder().isDirectory() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("プラグインデータフォルダの作成に失敗しました: " + plugin.getDataFolder().getAbsolutePath());
        }
        this.fightJson = new JsonBossPhasePersistence(plugin, JsonBossPhasePersistence.defaultJsonPath(plugin));
    }

    public void loadAllPersistedAsync(
            Map<UUID, Boolean> enhancedPillarPeriodicRegenByWorld,
            Map<UUID, Long> lastPillarRegenCheckWallMs,
            Consumer<Map<UUID, PersistedBossPhaseState>> onBossPhases
    ) {
        fightJson.loadAllAsync((activeWorlds, bossDiskMap) -> {
            long now = System.currentTimeMillis();
            for (UUID wid : activeWorlds) {
                enhancedPillarPeriodicRegenByWorld.put(wid, true);
                lastPillarRegenCheckWallMs.put(wid, now);
            }
            Map<UUID, PersistedBossPhaseState> out = new HashMap<>();
            for (Map.Entry<UUID, JsonBossPhasePersistence.BossPhaseDiskState> e : bossDiskMap.entrySet()) {
                JsonBossPhasePersistence.BossPhaseDiskState v = e.getValue();
                if (v == null) {
                    continue;
                }
                out.put(e.getKey(), new PersistedBossPhaseState(v.phaseName(), v.originalMaxHealth()));
            }
            onBossPhases.accept(out);
        });
    }

    public void setActive(UUID worldId, boolean active) {
        fightJson.setActiveWorldAsync(worldId, active);
    }

    public void saveBossPhase(UUID worldId, String phaseName, double originalMaxHealth) {
        fightJson.saveBossPhaseAsync(worldId, phaseName, originalMaxHealth);
    }

    public void shutdown() {
        fightJson.shutdown();
    }
}

package com.rakku212.util.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public final class JsonBossPhasePersistence {

    public record BossPhaseDiskState(String phaseName, double originalMaxHealth) {
    }

    private static final class FileRoot {
        List<String> activeWorldUuids = new ArrayList<>();
        Map<String, BossPhaseDiskState> worlds = new LinkedHashMap<>();
    }

    private final Plugin plugin;
    private final Path jsonPath;
    private final Path legacyBossOnlyPath;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "betterboss-dragon-fight-json-io");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<UUID, Boolean> mergedActiveWorlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BossPhaseDiskState> mergedByWorld = new ConcurrentHashMap<>();
    private final AtomicBoolean initialLoadFinished = new AtomicBoolean(false);
    private final AtomicInteger flushNeededAfterLoad = new AtomicInteger(0);
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    public JsonBossPhasePersistence(Plugin plugin, Path jsonPath) {
        this.plugin = plugin;
        this.jsonPath = jsonPath;
        this.legacyBossOnlyPath = plugin.getDataFolder().toPath().resolve("enhanced_dragon_boss_phases.json");
    }

    public static Path defaultJsonPath(Plugin plugin) {
        return plugin.getDataFolder().toPath().resolve("enhanced_dragon_fight.json");
    }

    public void loadAllAsync(BiConsumer<Set<UUID>, Map<UUID, BossPhaseDiskState>> onMainThread) {
        ioExecutor.execute(() -> {
            loadFromDiskIntoMerged();
            Bukkit.getScheduler().runTask(plugin, () -> {
                initialLoadFinished.set(true);
                if (flushNeededAfterLoad.getAndSet(0) > 0) {
                    scheduleFlush();
                }
                onMainThread.accept(
                        Set.copyOf(new HashSet<>(mergedActiveWorlds.keySet())),
                        Map.copyOf(mergedByWorld)
                );
            });
        });
    }

    public void saveBossPhaseAsync(UUID worldId, String phaseName, double originalMaxHealth) {
        if (worldId == null || phaseName == null || phaseName.isBlank()) {
            return;
        }
        BossPhaseDiskState state = new BossPhaseDiskState(phaseName, Math.max(0d, originalMaxHealth));
        mergedByWorld.put(worldId, state);
        if (!initialLoadFinished.get()) {
            flushNeededAfterLoad.incrementAndGet();
            return;
        }
        scheduleFlush();
    }

    public void setActiveWorldAsync(UUID worldId, boolean active) {
        if (worldId == null) {
            return;
        }
        if (active) {
            mergedActiveWorlds.put(worldId, Boolean.TRUE);
        } else {
            mergedActiveWorlds.remove(worldId);
            mergedByWorld.remove(worldId);
        }
        if (!initialLoadFinished.get()) {
            flushNeededAfterLoad.incrementAndGet();
            return;
        }
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                writeMergedSnapshot();
            } catch (IOException e) {
                plugin.getLogger().warning("強化エンド戦 JSON の保存に失敗しました: " + e.getMessage());
            } finally {
                flushScheduled.set(false);
            }
        });
    }

    private void loadFromDiskIntoMerged() {
        Path readPath = null;
        if (Files.isRegularFile(jsonPath)) {
            readPath = jsonPath;
        } else if (Files.isRegularFile(legacyBossOnlyPath)) {
            readPath = legacyBossOnlyPath;
        }
        if (readPath == null) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(readPath, StandardCharsets.UTF_8)) {
            FileRoot root = gson.fromJson(reader, FileRoot.class);
            if (root == null) {
                return;
            }
            if (root.activeWorldUuids != null) {
                for (String s : root.activeWorldUuids) {
                    UUID id = parseUuid(s);
                    if (id != null) {
                        mergedActiveWorlds.putIfAbsent(id, Boolean.TRUE);
                    }
                }
            }
            if (root.worlds != null) {
                for (Map.Entry<String, BossPhaseDiskState> e : root.worlds.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                        continue;
                    }
                    UUID id = parseUuid(e.getKey());
                    if (id != null) {
                        mergedByWorld.putIfAbsent(id, e.getValue());
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("強化エンド戦 JSON の読み込みに失敗しました: " + e.getMessage());
        }
    }

    private @Nullable UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("強化エンド戦 JSON に無効な UUID があります: " + raw);
            return null;
        }
    }

    private void writeMergedSnapshot() throws IOException {
        Path dir = jsonPath.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        FileRoot root = new FileRoot();
        List<String> actives = new ArrayList<>();
        for (UUID id : mergedActiveWorlds.keySet()) {
            actives.add(id.toString());
        }
        Collections.sort(actives);
        root.activeWorldUuids = actives;
        root.worlds = new LinkedHashMap<>();
        for (Map.Entry<UUID, BossPhaseDiskState> e : mergedByWorld.entrySet()) {
            root.worlds.put(e.getKey().toString(), e.getValue());
        }
        Path parent = dir != null ? dir : jsonPath.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("JSON path has no parent directory: " + jsonPath);
        }
        Path tmp = Files.createTempFile(parent, "dragon-fight-", ".json.tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                gson.toJson(root, w);
            }
            try {
                Files.move(tmp, jsonPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, jsonPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    public void shutdown() {
        ioExecutor.shutdown();
    }
}

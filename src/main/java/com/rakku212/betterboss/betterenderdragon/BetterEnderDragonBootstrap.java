package com.rakku212.betterboss.betterenderdragon;

import org.bukkit.plugin.java.JavaPlugin;

import com.rakku212.betterboss.betterenderdragon.listener.BetterEnderDragonCrystalListener;
import com.rakku212.betterboss.betterenderdragon.listener.EnhancedEndCrystalCraftListener;
import com.rakku212.betterboss.betterenderdragon.listener.EnhancedEnderDragonCombatListener;
import com.rakku212.betterboss.betterenderdragon.manager.BetterEnderDragonCrystalManager;
import com.rakku212.betterboss.betterenderdragon.packet.EnhancedDragonBossBarPacketListener;
import com.rakku212.betterboss.betterenderdragon.packet.EnhancedDragonCrystalBeamAttackCoordinator;
import com.rakku212.betterboss.betterenderdragon.packet.EnhancedDragonCryingObsidianPillarCoordinator;
import com.rakku212.betterboss.betterenderdragon.packet.EnhancedDragonFakeEndermiteCoordinator;
import com.rakku212.betterboss.betterenderdragon.persistence.EnhancedDragonFightWorldStore;
import com.rakku212.betterboss.betterenderdragon.recipe.EnhancedEndCrystalRecipeRegistrar;

public final class BetterEnderDragonBootstrap {

    public static Runnable register(JavaPlugin plugin) {
        EnhancedDragonFightWorldStore worldStore = new EnhancedDragonFightWorldStore(plugin);
        BetterEnderDragonCrystalManager crystalManager = new BetterEnderDragonCrystalManager(plugin, worldStore);

        var pm = plugin.getServer().getPluginManager();
        pm.registerEvents(new BetterEnderDragonCrystalListener(crystalManager), plugin);
        pm.registerEvents(new EnhancedEnderDragonCombatListener(plugin, crystalManager), plugin);
        pm.registerEvents(new EnhancedEndCrystalCraftListener(plugin), plugin);

        EnhancedEndCrystalRecipeRegistrar.register(plugin);

        EnhancedDragonBossBarPacketListener bossBarListener = new EnhancedDragonBossBarPacketListener(plugin, crystalManager);
        bossBarListener.register();
        crystalManager.attachBossBarPacketListener(bossBarListener);

        EnhancedDragonCrystalBeamAttackCoordinator.register(plugin, crystalManager);
        EnhancedDragonCryingObsidianPillarCoordinator.register(plugin, crystalManager::isEnhancedDragonFightWorld);
        EnhancedDragonFakeEndermiteCoordinator.register(plugin, crystalManager);

        return worldStore::shutdown;
    }
}

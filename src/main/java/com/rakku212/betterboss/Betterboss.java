package com.rakku212.betterboss;

import com.rakku212.betterboss.betterenderdragon.BetterEnderDragonBootstrap;

import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

public final class Betterboss extends JavaPlugin {

    private @Nullable Runnable betterEnderDragonShutdown;

    @Override
    public void onEnable() {
        betterEnderDragonShutdown = BetterEnderDragonBootstrap.register(this);
    }

    @Override
    public void onDisable() {
        if (betterEnderDragonShutdown != null) {
            betterEnderDragonShutdown.run();
            betterEnderDragonShutdown = null;
        }
    }
}

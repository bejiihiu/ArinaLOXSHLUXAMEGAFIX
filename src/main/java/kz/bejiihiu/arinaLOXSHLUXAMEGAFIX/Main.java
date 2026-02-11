package kz.bejiihiu.arinaLOXSHLUXAMEGAFIX;

import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new AttributeGuardListener(this), this);
        getLogger().info("AttributeGuard enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("AttributeGuard disabled");
    }
}

package dev.oakheart.oakrewind;

import dev.oakheart.oakrewind.config.ConfigManager;
import dev.oakheart.oakrewind.listeners.ExplosionListener;
import dev.oakheart.oakrewind.message.MessageManager;
import org.bstats.bukkit.Metrics;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class OakRewind extends JavaPlugin {

    private ExplosionListener explosionListener;
    private WorldRebuildHandler worldRebuildHandler;
    private ConfigManager configManager;
    private MessageManager messageManager;

    @Override
    public void onEnable() {
        try {
            initializeComponents();
            registerListeners();
            registerCommands();
            initializeMetrics();

            getLogger().info("OakRewind has been enabled!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable OakRewind", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (worldRebuildHandler != null) {
            worldRebuildHandler.shutdown();
        }
        getLogger().info("OakRewind has been disabled!");
    }

    private void initializeComponents() {
        configManager = new ConfigManager(this);
        configManager.load();

        messageManager = new MessageManager();
        messageManager.load(configManager.getConfig());
    }

    private void registerListeners() {
        worldRebuildHandler = new WorldRebuildHandler(
                this,
                configManager.getInitialRebuildDelay(),
                configManager.getDelayFalloff(),
                configManager.getMinimumRebuildDelay(),
                configManager.getRebuildPattern(),
                configManager.isParticlesEnabled(),
                configManager.getParticleType(),
                configManager.getParticleCount()
        );
        explosionListener = new ExplosionListener(
                worldRebuildHandler,
                configManager.isEnableRebuild(),
                configManager.getEnabledExplosionTypes()
        );
        getServer().getPluginManager().registerEvents(explosionListener, this);
    }

    private void registerCommands() {
        new dev.oakheart.oakrewind.commands.OakRewindCommand(this).register();
    }

    private void initializeMetrics() {
        new Metrics(this, 27921);
    }

    public void reloadCustomConfig() {
        configManager.reload();
        messageManager.load(configManager.getConfig());

        // Unregister the old listener
        HandlerList.unregisterAll(explosionListener);

        // Finish all ongoing rebuilds immediately before creating new handler
        worldRebuildHandler.shutdown();

        // Reinitialize listeners with new config
        registerListeners();
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }
}

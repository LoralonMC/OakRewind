package dev.oakheart.oakrewind;

import dev.oakheart.message.MessageManager;
import dev.oakheart.oakrewind.config.ConfigManager;
import dev.oakheart.oakrewind.listeners.EntityProtectionListener;
import dev.oakheart.oakrewind.listeners.ExplosionListener;
import dev.oakheart.oakrewind.managers.EntityProtectionManager;
import org.bstats.bukkit.Metrics;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class OakRewind extends JavaPlugin {

    private ExplosionListener explosionListener;
    private EntityProtectionListener entityProtectionListener;
    private EntityProtectionManager entityProtectionManager;
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
        // Finishing the rebuilds reveals their entities through the completion callbacks;
        // revealAll then catches anything that was shielded but never claimed by a rebuild.
        if (worldRebuildHandler != null) {
            worldRebuildHandler.shutdown();
        }
        if (entityProtectionManager != null) {
            entityProtectionManager.revealAll();
        }
        getLogger().info("OakRewind has been disabled!");
    }

    private void initializeComponents() {
        configManager = new ConfigManager(this);
        configManager.load();

        messageManager = new MessageManager(this, getLogger());
        messageManager.load();
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
        entityProtectionManager = new EntityProtectionManager(
                this,
                configManager.isRestoreEntitiesEnabled(),
                configManager.getRestoredEntityTypes()
        );
        explosionListener = new ExplosionListener(
                worldRebuildHandler,
                entityProtectionManager,
                configManager.isEnableRebuild(),
                configManager.getEnabledExplosionTypes()
        );
        entityProtectionListener = new EntityProtectionListener(
                entityProtectionManager,
                configManager.isEnableRebuild(),
                configManager.getEnabledExplosionTypes()
        );
        getServer().getPluginManager().registerEvents(explosionListener, this);
        getServer().getPluginManager().registerEvents(entityProtectionListener, this);
    }

    private void registerCommands() {
        new dev.oakheart.oakrewind.commands.OakRewindCommand(this).register();
    }

    private void initializeMetrics() {
        new Metrics(this, 27921);
    }

    /** @return false when the new config failed validation (old values kept). */
    public boolean reloadCustomConfig() {
        boolean configOk = configManager.reload();
        messageManager.reload();

        // Unregister the old listeners
        HandlerList.unregisterAll(explosionListener);
        HandlerList.unregisterAll(entityProtectionListener);

        // Finish all ongoing rebuilds immediately before creating new handler. This reveals
        // any hidden entities, so no shielded entity is stranded by the manager being replaced.
        worldRebuildHandler.shutdown();
        entityProtectionManager.revealAll();

        // Reinitialize listeners with new config
        registerListeners();
        return configOk;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }
}

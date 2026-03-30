package dev.oakheart.oakrewind.config;

import dev.oakheart.oakrewind.OakRewind;
import org.bukkit.Particle;
import org.bukkit.entity.EntityType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages plugin configuration with OakheartLib ConfigManager, validation, caching, and safe reload.
 */
public class ConfigManager {

    /**
     * Defines the available patterns for block rebuilding.
     */
    public enum RebuildPattern {
        TOP_DOWN,
        BOTTOM_UP,
        CENTER_OUT,
        RANDOM
    }

    private final OakRewind plugin;
    private final Logger logger;
    private final Path configPath;
    private dev.oakheart.config.ConfigManager config;

    // Cached config values
    private boolean enableRebuild;
    private List<EntityType> enabledExplosionTypes;
    private long initialRebuildDelay;
    private double delayFalloff;
    private long minimumRebuildDelay;
    private RebuildPattern rebuildPattern;
    private boolean particlesEnabled;
    private Particle particleType;
    private int particleCount;

    public ConfigManager(OakRewind plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configPath = plugin.getDataFolder().toPath().resolve("config.yml");
    }

    /**
     * Initial load of configuration. Called once during onEnable.
     */
    public void load() {
        try {
            if (!Files.exists(configPath)) {
                plugin.saveResource("config.yml", false);
            }

            config = dev.oakheart.config.ConfigManager.load(configPath);
            mergeDefaults();
            validate(config);
            cacheValues();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

    /**
     * Reloads configuration from disk. Validates before applying.
     *
     * @return true if reload was successful
     */
    public boolean reload() {
        try {
            config.reload();

            if (!validate(config)) {
                logger.warning("Configuration reload failed validation. Keeping previous configuration.");
                return false;
            }

            cacheValues();
            logger.info("Configuration reloaded successfully.");
            return true;
        } catch (IOException e) {
            logger.warning("Failed to reload configuration: " + e.getMessage());
            return false;
        }
    }

    /**
     * Merges default config values from the JAR resource into the user's config
     * without overwriting existing values.
     */
    private void mergeDefaults() throws IOException {
        try (var stream = plugin.getResource("config.yml")) {
            if (stream != null) {
                var defaults = dev.oakheart.config.ConfigManager.fromStream(stream);
                if (config.mergeDefaults(defaults)) {
                    config.save();
                }
            }
        }
    }

    /**
     * Validates configuration values and logs warnings for issues.
     *
     * @param configToValidate the configuration to validate
     * @return true if no fatal errors were found
     */
    private boolean validate(dev.oakheart.config.ConfigManager configToValidate) {
        List<String> warnings = new ArrayList<>();

        long initDelay = configToValidate.getLong("rebuild.initial-delay", 2000);
        if (initDelay < 0) {
            warnings.add("rebuild.initial-delay must be >= 0, got: " + initDelay + ". Using default: 2000");
        }

        double falloff = configToValidate.getDouble("rebuild.delay-falloff", 0.175);
        if (falloff < 0.0 || falloff > 1.0) {
            warnings.add("rebuild.delay-falloff must be between 0.0 and 1.0, got: " + falloff + ". Using default: 0.175");
        }

        long minDelay = configToValidate.getLong("rebuild.minimum-delay", 50);
        if (minDelay < 0) {
            warnings.add("rebuild.minimum-delay must be >= 0, got: " + minDelay + ". Using default: 50");
        }

        int pCount = configToValidate.getInt("rebuild.particles.count", 5);
        if (pCount < 0 || pCount > 100) {
            warnings.add("rebuild.particles.count must be between 0 and 100, got: " + pCount + ". Using default: 5");
        }

        String patternName = configToValidate.getString("rebuild.pattern", "TOP_DOWN");
        try {
            RebuildPattern.valueOf(patternName.toUpperCase());
        } catch (IllegalArgumentException e) {
            warnings.add("Invalid rebuild.pattern: " + patternName + ". Using default: TOP_DOWN");
        }

        String particleTypeName = configToValidate.getString("rebuild.particles.type", "CLOUD");
        try {
            Particle.valueOf(particleTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            warnings.add("Invalid rebuild.particles.type: " + particleTypeName + ". Using default: CLOUD");
        }

        List<String> explosionTypeNames = configToValidate.getStringList("enabled-explosion-types");
        for (String typeName : explosionTypeNames) {
            try {
                EntityType.valueOf(typeName.toUpperCase());
            } catch (IllegalArgumentException e) {
                warnings.add("Invalid explosion type: " + typeName);
            }
        }

        if (!warnings.isEmpty()) {
            logger.warning("=== Configuration Warnings ===");
            warnings.forEach(w -> logger.warning("  - " + w));
            logger.warning("==============================");
        }

        return true;
    }

    /**
     * Caches frequently accessed config values as typed fields.
     */
    private void cacheValues() {
        enableRebuild = config.getBoolean("enable-rebuild", true);

        // Load enabled explosion types
        enabledExplosionTypes = new ArrayList<>();
        List<String> explosionTypeNames = config.getStringList("enabled-explosion-types");
        if (explosionTypeNames.isEmpty()) {
            enabledExplosionTypes.add(EntityType.CREEPER);
        } else {
            for (String typeName : explosionTypeNames) {
                try {
                    enabledExplosionTypes.add(EntityType.valueOf(typeName.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    // Already warned in validate()
                }
            }
            if (enabledExplosionTypes.isEmpty()) {
                enabledExplosionTypes.add(EntityType.CREEPER);
            }
        }

        // Rebuild settings
        initialRebuildDelay = config.getLong("rebuild.initial-delay", 2000);
        if (initialRebuildDelay < 0) initialRebuildDelay = 2000;

        delayFalloff = config.getDouble("rebuild.delay-falloff", 0.175);
        if (delayFalloff < 0.0 || delayFalloff > 1.0) delayFalloff = 0.175;

        minimumRebuildDelay = config.getLong("rebuild.minimum-delay", 50);
        if (minimumRebuildDelay < 0) minimumRebuildDelay = 50;

        // Rebuild pattern
        String patternName = config.getString("rebuild.pattern", "TOP_DOWN");
        try {
            rebuildPattern = RebuildPattern.valueOf(patternName.toUpperCase());
        } catch (IllegalArgumentException e) {
            rebuildPattern = RebuildPattern.TOP_DOWN;
        }

        // Particle settings
        particlesEnabled = config.getBoolean("rebuild.particles.enabled", true);

        String particleTypeName = config.getString("rebuild.particles.type", "CLOUD");
        try {
            particleType = Particle.valueOf(particleTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            particleType = Particle.CLOUD;
        }

        particleCount = config.getInt("rebuild.particles.count", 5);
        if (particleCount < 0 || particleCount > 100) particleCount = 5;
    }

    /**
     * Gets the raw config for direct access.
     */
    public dev.oakheart.config.ConfigManager getConfig() {
        return config;
    }

    // ===== Type-safe cached getters =====

    public boolean isEnableRebuild() {
        return enableRebuild;
    }

    public List<EntityType> getEnabledExplosionTypes() {
        return enabledExplosionTypes;
    }

    public long getInitialRebuildDelay() {
        return initialRebuildDelay;
    }

    public double getDelayFalloff() {
        return delayFalloff;
    }

    public long getMinimumRebuildDelay() {
        return minimumRebuildDelay;
    }

    public RebuildPattern getRebuildPattern() {
        return rebuildPattern;
    }

    public boolean isParticlesEnabled() {
        return particlesEnabled;
    }

    public Particle getParticleType() {
        return particleType;
    }

    public int getParticleCount() {
        return particleCount;
    }
}

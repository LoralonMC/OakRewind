package dev.oakheart.oakrewind.config;

import dev.oakheart.oakrewind.OakRewind;
import org.bukkit.Particle;
import org.bukkit.entity.EntityType;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages plugin configuration with Configurate, validation, caching, and safe reload.
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
    private final YamlConfigurationLoader loader;
    private ConfigurationNode config;

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
        Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
        this.loader = YamlConfigurationLoader.builder()
                .path(configPath)
                .build();
    }

    /**
     * Initial load of configuration. Called once during onEnable.
     */
    public void load() {
        try {
            Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
            if (!Files.exists(configPath)) {
                plugin.saveResource("config.yml", false);
            }

            config = loader.load();
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
            ConfigurationNode newConfig = loader.load();

            if (!validate(newConfig)) {
                logger.warning("Configuration reload failed validation. Keeping previous configuration.");
                return false;
            }

            this.config = newConfig;
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
        YamlConfigurationLoader defaultLoader = YamlConfigurationLoader.builder()
                .url(getClass().getResource("/config.yml"))
                .build();
        ConfigurationNode defaults = defaultLoader.load();
        config.mergeFrom(defaults);
        loader.save(config);
    }

    /**
     * Validates configuration values and logs warnings for issues.
     *
     * @param configToValidate the configuration to validate
     * @return true if no fatal errors were found
     */
    private boolean validate(ConfigurationNode configToValidate) {
        List<String> warnings = new ArrayList<>();

        long initDelay = configToValidate.node("rebuild", "initial-delay").getLong(2000);
        if (initDelay < 0) {
            warnings.add("rebuild.initial-delay must be >= 0, got: " + initDelay + ". Using default: 2000");
        }

        double falloff = configToValidate.node("rebuild", "delay-falloff").getDouble(0.175);
        if (falloff < 0.0 || falloff > 1.0) {
            warnings.add("rebuild.delay-falloff must be between 0.0 and 1.0, got: " + falloff + ". Using default: 0.175");
        }

        long minDelay = configToValidate.node("rebuild", "minimum-delay").getLong(50);
        if (minDelay < 0) {
            warnings.add("rebuild.minimum-delay must be >= 0, got: " + minDelay + ". Using default: 50");
        }

        int pCount = configToValidate.node("rebuild", "particles", "count").getInt(5);
        if (pCount < 0 || pCount > 100) {
            warnings.add("rebuild.particles.count must be between 0 and 100, got: " + pCount + ". Using default: 5");
        }

        String patternName = configToValidate.node("rebuild", "pattern").getString("TOP_DOWN");
        try {
            RebuildPattern.valueOf(patternName.toUpperCase());
        } catch (IllegalArgumentException e) {
            warnings.add("Invalid rebuild.pattern: " + patternName + ". Using default: TOP_DOWN");
        }

        String particleTypeName = configToValidate.node("rebuild", "particles", "type").getString("CLOUD");
        try {
            Particle.valueOf(particleTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            warnings.add("Invalid rebuild.particles.type: " + particleTypeName + ". Using default: CLOUD");
        }

        try {
            List<String> explosionTypeNames = configToValidate.node("enabled-explosion-types")
                    .getList(String.class, List.of());
            for (String typeName : explosionTypeNames) {
                try {
                    EntityType.valueOf(typeName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    warnings.add("Invalid explosion type: " + typeName);
                }
            }
        } catch (SerializationException e) {
            warnings.add("Failed to read enabled-explosion-types: " + e.getMessage());
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
        enableRebuild = config.node("enable-rebuild").getBoolean(true);

        // Load enabled explosion types
        enabledExplosionTypes = new ArrayList<>();
        try {
            List<String> explosionTypeNames = config.node("enabled-explosion-types")
                    .getList(String.class, List.of());
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
        } catch (SerializationException e) {
            enabledExplosionTypes.add(EntityType.CREEPER);
        }

        // Rebuild settings
        initialRebuildDelay = config.node("rebuild", "initial-delay").getLong(2000);
        if (initialRebuildDelay < 0) initialRebuildDelay = 2000;

        delayFalloff = config.node("rebuild", "delay-falloff").getDouble(0.175);
        if (delayFalloff < 0.0 || delayFalloff > 1.0) delayFalloff = 0.175;

        minimumRebuildDelay = config.node("rebuild", "minimum-delay").getLong(50);
        if (minimumRebuildDelay < 0) minimumRebuildDelay = 50;

        // Rebuild pattern
        String patternName = config.node("rebuild", "pattern").getString("TOP_DOWN");
        try {
            rebuildPattern = RebuildPattern.valueOf(patternName.toUpperCase());
        } catch (IllegalArgumentException e) {
            rebuildPattern = RebuildPattern.TOP_DOWN;
        }

        // Particle settings
        particlesEnabled = config.node("rebuild", "particles", "enabled").getBoolean(true);

        String particleTypeName = config.node("rebuild", "particles", "type").getString("CLOUD");
        try {
            particleType = Particle.valueOf(particleTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            particleType = Particle.CLOUD;
        }

        particleCount = config.node("rebuild", "particles", "count").getInt(5);
        if (particleCount < 0 || particleCount > 100) particleCount = 5;
    }

    /**
     * Gets the raw ConfigurationNode for direct access.
     */
    public ConfigurationNode getConfig() {
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

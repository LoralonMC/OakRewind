package dev.oakheart.oakrewind.config;

import dev.oakheart.oakrewind.OakRewind;
import org.bukkit.Particle;
import org.bukkit.entity.EntityType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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

    /**
     * Entity types OakRewind knows how to shield from an explosion.
     *
     * <p>Armor stands are protected by cancelling their explosion damage; the hanging
     * types are protected by cancelling their break event. Anything outside this set
     * would silently do nothing, so it is rejected during validation instead.</p>
     */
    private static final Set<EntityType> RESTORABLE_TYPES = EnumSet.of(
            EntityType.ARMOR_STAND,
            EntityType.ITEM_FRAME,
            EntityType.GLOW_ITEM_FRAME,
            EntityType.PAINTING
    );

    private static final String SUPPORTED_TYPE_NAMES = RESTORABLE_TYPES.stream()
            .map(EntityType::name)
            .collect(Collectors.joining(", "));

    private final OakRewind plugin;
    private final Logger logger;
    private final Path configPath;
    private dev.oakheart.config.ConfigManager config;

    // Cached config values
    private boolean enableRebuild;
    private List<EntityType> enabledExplosionTypes;
    private boolean restoreEntitiesEnabled;
    private Set<EntityType> restoredEntityTypes;
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
            evolveConfig();
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
     * Evolves an existing config file toward the JAR defaults without disturbing
     * the admin's own values or formatting.
     *
     * <p>Runs version-gated migrations for values that changed meaning, adds any missing
     * keys at their correct position with their comments, then syncs changed comment blocks
     * onto existing keys. The comment sync uses a persisted baseline of the previously
     * shipped defaults, so comments the admin wrote themselves are never overwritten.
     * Saves once if anything changed.</p>
     *
     * <p>The migration must run before {@code mergeDefaults}: merging first would stamp the
     * config with the defaults' {@code config-version}, so the migration would look already
     * applied and be skipped.</p>
     */
    private void evolveConfig() throws IOException {
        try (var stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return;
            }
            var defaults = dev.oakheart.config.ConfigManager.fromStream(stream);
            Path baseline = plugin.getDataFolder().toPath().resolve(".oakheart/config-baseline.yml");

            boolean migrated = dev.oakheart.config.ConfigMigrator.forConfig(config)
                    .step(1, ConfigManager::fixTntMinecartType)
                    .run();
            boolean merged = config.mergeDefaults(defaults);
            boolean synced = config.syncComments(defaults, baseline);

            if (migrated || merged || synced) {
                config.save();
            }
        }
    }

    /**
     * Corrects the explosion type that never existed.
     *
     * <p>Earlier defaults shipped {@code MINECART_TNT}, but the real constant is
     * {@code TNT_MINECART}. The bad name simply failed to parse, so TNT minecart explosions
     * were never rebuilt and the only sign was a startup warning.</p>
     */
    private static void fixTntMinecartType(dev.oakheart.config.ConfigManager cfg) {
        List<String> types = new ArrayList<>(cfg.getStringList("enabled-explosion-types"));
        boolean changed = false;

        for (int i = 0; i < types.size(); i++) {
            if ("MINECART_TNT".equalsIgnoreCase(types.get(i))) {
                types.set(i, "TNT_MINECART");
                changed = true;
            }
        }
        if (!changed) {
            return;
        }

        // Guard against ending up with both spellings if the admin had already added the
        // correct one alongside the broken default.
        List<String> deduped = new ArrayList<>();
        for (String type : types) {
            if (deduped.stream().noneMatch(existing -> existing.equalsIgnoreCase(type))) {
                deduped.add(type);
            }
        }
        cfg.set("enabled-explosion-types", deduped);
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

        for (String typeName : configToValidate.getStringList("restore-entities.types")) {
            EntityType type;
            try {
                type = EntityType.valueOf(typeName.toUpperCase());
            } catch (IllegalArgumentException e) {
                warnings.add("Invalid restore-entities type: " + typeName);
                continue;
            }
            if (!RESTORABLE_TYPES.contains(type)) {
                warnings.add("restore-entities type " + type.name() + " is not supported and will be ignored. "
                        + "Supported types: " + SUPPORTED_TYPE_NAMES);
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

        // Entity restoration
        restoreEntitiesEnabled = config.getBoolean("restore-entities.enabled", true);
        restoredEntityTypes = EnumSet.noneOf(EntityType.class);
        for (String typeName : config.getStringList("restore-entities.types")) {
            try {
                EntityType type = EntityType.valueOf(typeName.toUpperCase());
                if (RESTORABLE_TYPES.contains(type)) {
                    restoredEntityTypes.add(type);
                }
            } catch (IllegalArgumentException e) {
                // Already warned in validate()
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

    public boolean isRestoreEntitiesEnabled() {
        return restoreEntitiesEnabled;
    }

    public Set<EntityType> getRestoredEntityTypes() {
        return restoredEntityTypes;
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

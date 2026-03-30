package dev.oakheart.oakrewind.listeners;

import dev.oakheart.oakrewind.WorldRebuildHandler;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Listens for explosion events and triggers block rebuilding based on configuration.
 *
 * <p>This listener intercepts entity explosion events and passes the affected blocks
 * to the {@link WorldRebuildHandler} for restoration, but only for configured explosion types
 * when rebuilding is enabled.</p>
 *
 * @author Loralon
 */
public class ExplosionListener implements Listener {
    private final WorldRebuildHandler worldRebuildHandler;
    private final boolean configEnableRebuild;
    private final List<EntityType> configEnabledExplosionTypes;

    /**
     * Creates a new ExplosionListener with the specified configuration.
     *
     * @param worldRebuildHandler the handler responsible for rebuilding blocks
     * @param enableRebuild whether rebuilding is enabled
     * @param enabledExplosionTypes list of entity types whose explosions should trigger rebuilding
     */
    public ExplosionListener(WorldRebuildHandler worldRebuildHandler, boolean enableRebuild, List<EntityType> enabledExplosionTypes) {
        this.worldRebuildHandler = worldRebuildHandler;
        this.configEnableRebuild = enableRebuild;
        this.configEnabledExplosionTypes = enabledExplosionTypes;
    }

    /**
     * Handles entity explosion events and triggers block rebuilding if configured.
     *
     * <p>This method checks if the exploding entity's type is in the configured list of
     * enabled explosion types. If so, it passes the affected blocks to the WorldRebuildHandler.</p>
     *
     * @param event the explosion event
     */
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        // Check if entity is null (shouldn't happen but be safe)
        if (event.getEntity() == null) {
            return;
        }

        EntityType entityType = event.getEntityType();

        // Only handle configured explosion types when rebuild is enabled
        if (configEnableRebuild && configEnabledExplosionTypes.contains(entityType)) {
            // Let the explosion happen normally, then rebuild the blocks
            worldRebuildHandler.rebuild(new ArrayList<>(event.blockList()));
        }
    }
}

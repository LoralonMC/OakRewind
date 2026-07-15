package dev.oakheart.oakrewind.listeners;

import dev.oakheart.oakrewind.WorldRebuildHandler;
import dev.oakheart.oakrewind.managers.EntityProtectionManager;
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
    private final EntityProtectionManager protectionManager;
    private final boolean configEnableRebuild;
    private final List<EntityType> configEnabledExplosionTypes;

    /**
     * Creates a new ExplosionListener with the specified configuration.
     *
     * @param worldRebuildHandler the handler responsible for rebuilding blocks
     * @param protectionManager the manager holding entities shielded from this explosion
     * @param enableRebuild whether rebuilding is enabled
     * @param enabledExplosionTypes list of entity types whose explosions should trigger rebuilding
     */
    public ExplosionListener(WorldRebuildHandler worldRebuildHandler, EntityProtectionManager protectionManager,
                             boolean enableRebuild, List<EntityType> enabledExplosionTypes) {
        this.worldRebuildHandler = worldRebuildHandler;
        this.protectionManager = protectionManager;
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
    // HIGHEST + ignoreCancelled: without these, an explosion cancelled by a
    // protection plugin (Lands claims) was still rebuilt, meaning this plugin
    // itself removed and restored blocks inside protected land, and any plugin
    // pruning blockList() at a lower priority was silently overridden.
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        // Check if entity is null (shouldn't happen but be safe)
        if (event.getEntity() == null) {
            return;
        }

        EntityType entityType = event.getEntityType();

        // Only handle configured explosion types when rebuild is enabled
        if (configEnableRebuild && configEnabledExplosionTypes.contains(entityType)) {
            // Also take custody of hanging entities the blast never touched but whose support
            // block is about to be cleared. Must happen before rebuild() turns them to air.
            protectionManager.markHangingLosingSupport(event.blockList());

            // Hide the entities this blast shielded during its damage phase, which ran earlier
            // in this same tick, and reveal them once the blocks are back.
            Runnable revealEntities = protectionManager.claimPending();

            // Let the explosion happen normally, then rebuild the blocks
            worldRebuildHandler.rebuild(new ArrayList<>(event.blockList()), revealEntities);
        }
    }
}

package dev.oakheart.oakrewind.listeners;

import dev.oakheart.oakrewind.managers.EntityProtectionManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;

/**
 * Intercepts the ways an explosion destroys a decoration entity, so it can rewind instead.
 *
 * <p>Armor stands and hanging entities die down two different paths, and both have to be
 * caught: armor stands take explosion damage, while item frames and paintings break through
 * their own hanging-break event. Hanging entities also have a second, later death vector —
 * see {@link #onHangingBreak}.</p>
 */
public class EntityProtectionListener implements Listener {

    private final EntityProtectionManager protectionManager;
    private final boolean rebuildEnabled;
    private final List<EntityType> enabledExplosionTypes;

    public EntityProtectionListener(EntityProtectionManager protectionManager,
                                    boolean rebuildEnabled,
                                    List<EntityType> enabledExplosionTypes) {
        this.protectionManager = protectionManager;
        this.rebuildEnabled = rebuildEnabled;
        this.enabledExplosionTypes = enabledExplosionTypes;
    }

    /**
     * Shields armor stands from explosion damage.
     *
     * <p>Cancelling here is what makes duplication impossible. An armor stand's explosion
     * branch fires this event before {@code brokenByAnything()}, which is what drops the
     * stand and everything equipped on it, so a cancel means no item ever enters the world.
     * Marker stands never reach this path — vanilla already treats them as immune.</p>
     *
     * <p>Deliberately not {@code ignoreCancelled}: see {@link #onHangingBreak}.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return;
        }

        Entity entity = event.getEntity();
        if (!protectionManager.isProtectedType(entity.getType())) {
            return;
        }
        if (!isRewoundExplosion(event.getDamageSource().getDirectEntity())) {
            return;
        }

        event.setCancelled(true);
        protectionManager.markPending(entity);
    }

    /**
     * Shields hanging entities from both of the ways a rebuild would destroy them.
     *
     * <p>The {@code EXPLOSION} cause is the blast itself. The {@code PHYSICS} and
     * {@code OBSTRUCTION} causes are the subtler problem: hanging entities periodically
     * re-check that they are still attached to something via {@code survives()}, and a
     * rebuild leaves their support block as air for seconds. Without this they would notice
     * the hole and drop themselves partway through the very rebuild meant to save them.</p>
     *
     * <p>Breaks by an entity are deliberately left alone, so a player can still take down a
     * shielded item frame by hand while the rebuild runs.</p>
     *
     * <p>Deliberately not {@code ignoreCancelled}. Another plugin may already protect item
     * frames from explosions and cancel this first — several invisible-item-frame plugins do
     * exactly that. Skipping those events would leave the frame alive but unknown to us: never
     * hidden, and never guarded against the {@code survives()} check below, so it would sit
     * visibly in mid-air and then pop once its support block was gone. We still need to take
     * custody of a frame someone else saved, so cancelling it again is harmless and required.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingBreak(HangingBreakEvent event) {
        Entity entity = event.getEntity();
        if (!protectionManager.isProtectedType(entity.getType())) {
            return;
        }

        switch (event.getCause()) {
            case EXPLOSION -> {
                if (isRewoundExplosion(explosionSourceOf(event))) {
                    event.setCancelled(true);
                    protectionManager.markPending(entity);
                }
            }
            case PHYSICS, OBSTRUCTION -> {
                if (protectionManager.isHidden(entity)) {
                    event.setCancelled(true);
                }
            }
            default -> {
                // ENTITY / DEFAULT: a player or another plugin broke it. Not ours to block.
            }
        }
    }

    /**
     * Hides in-flight entities from a player who joined mid-rebuild.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        protectionManager.hideInFlightFor(event.getPlayer());
    }

    /**
     * Resolves which entity actually exploded.
     *
     * <p>The direct entity is the exploding entity itself rather than whoever is blamed for
     * it — primed TNT rather than the player who lit it — which is the same entity
     * {@code EntityExplodeEvent} reports, so the two events agree on the source type.</p>
     */
    private Entity explosionSourceOf(HangingBreakEvent event) {
        if (event instanceof org.bukkit.event.hanging.HangingBreakByEntityEvent byEntity) {
            org.bukkit.damage.DamageSource source = byEntity.getDamageSource();
            return source != null ? source.getDirectEntity() : byEntity.getRemover();
        }
        return null;
    }

    /**
     * @return true if this explosion is one OakRewind is going to rebuild
     */
    private boolean isRewoundExplosion(Entity source) {
        // Only shield what we are actually rewinding. If an explosion type is left out of
        // enabled-explosion-types, its blocks stay blown up, and its armor stands should too.
        return rebuildEnabled
                && source != null
                && enabledExplosionTypes.contains(source.getType());
    }
}

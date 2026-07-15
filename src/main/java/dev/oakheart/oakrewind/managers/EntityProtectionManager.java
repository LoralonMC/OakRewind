package dev.oakheart.oakrewind.managers;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shields decoration entities from explosions so they can rewind along with the blocks.
 *
 * <p>The server damages entities before it breaks blocks: {@code ServerExplosion.explode()}
 * runs {@code hurtEntities()} and only then {@code interactWithBlocks()}, which is what
 * fires {@code EntityExplodeEvent}. By the time a rebuild is scheduled, any armor stand in
 * the blast is already dead and its equipment already dropped. There is nothing left to
 * restore, so restoring is the wrong model entirely.</p>
 *
 * <p>Instead the explosion damage is cancelled outright, which means the entity is never
 * broken and nothing is ever dropped — duplication is impossible because no item ever
 * enters the world. The entity is then hidden from players for the duration of the rebuild
 * and revealed at the end, which looks identical to it having been destroyed and rebuilt.
 * Because it never actually leaves the world it keeps its UUID, its data, and its identity
 * to any other plugin holding a reference to it.</p>
 *
 * <p>Entities are collected during the damage phase into a pending list, then claimed by the
 * explosion that caused them. Anything left unclaimed is dropped on the next tick: it was
 * only ever shielded, so there is no state to unwind.</p>
 *
 * <p>Main thread only.</p>
 */
public class EntityProtectionManager {

    private final Plugin plugin;
    private final boolean enabled;
    private final Set<EntityType> protectedTypes;

    /** Entities shielded during the current damage phase, awaiting an explosion to claim them. */
    private final List<Entity> pending = new ArrayList<>();
    private boolean purgeScheduled = false;

    /** Entities currently hidden for an in-flight rebuild, keyed by UUID. */
    private final Map<UUID, HiddenEntity> hidden = new LinkedHashMap<>();

    /**
     * State captured when an entity is hidden, so it can be put back exactly as it was.
     *
     * @param entity  the entity itself, which stays live in the world throughout
     * @param location where it stood when the explosion hit
     * @param gravity whether it had gravity before we froze it
     */
    private record HiddenEntity(Entity entity, Location location, boolean gravity) {
    }

    public EntityProtectionManager(Plugin plugin, boolean enabled, Set<EntityType> protectedTypes) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.protectedTypes = protectedTypes;
    }

    /**
     * @return true if the given type should be shielded from rebuilt explosions
     */
    public boolean isProtectedType(EntityType type) {
        return enabled && protectedTypes.contains(type);
    }

    /**
     * @return true if the entity is currently hidden for an in-flight rebuild
     */
    public boolean isHidden(Entity entity) {
        return hidden.containsKey(entity.getUniqueId());
    }

    /**
     * Records an entity whose explosion damage was just cancelled.
     *
     * <p>Nothing is hidden yet. The explosion that caused this damage fires its
     * {@code EntityExplodeEvent} later in the same tick and claims the entity then. If it
     * never does — because another plugin cancelled the explosion, or the blast broke no
     * blocks — the entry is simply discarded next tick with nothing to undo.</p>
     */
    public void markPending(Entity entity) {
        pending.add(entity);

        if (!purgeScheduled) {
            purgeScheduled = true;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                pending.clear();
                purgeScheduled = false;
            });
        }
    }

    /**
     * Hides everything shielded during the current explosion and hands back the reveal step.
     *
     * <p>Called from the explosion's {@code EntityExplodeEvent}, which runs in the same tick
     * as the damage that filled the pending list, so the list can only hold entities from
     * this blast.</p>
     *
     * @return a callback that reveals the claimed entities, or null if there were none
     */
    public Runnable claimPending() {
        if (pending.isEmpty()) {
            return null;
        }

        List<HiddenEntity> claimed = new ArrayList<>(pending.size());
        for (Entity entity : pending) {
            if (entity.isValid() && !isHidden(entity)) {
                claimed.add(hide(entity));
            }
        }
        pending.clear();

        if (claimed.isEmpty()) {
            return null;
        }
        return () -> claimed.forEach(this::reveal);
    }

    /**
     * Hides an entity and freezes it in place for the rebuild.
     *
     * <p>Gravity is frozen because the entity is still physically present while the blocks
     * beneath it are air, and would otherwise fall into the crater and be left somewhere
     * else once the ground came back. Velocity is zeroed for the same reason: explosion
     * knockback is applied separately from the damage that was cancelled.</p>
     */
    private HiddenEntity hide(Entity entity) {
        HiddenEntity state = new HiddenEntity(entity, entity.getLocation(), entity.hasGravity());
        hidden.put(entity.getUniqueId(), state);

        entity.setGravity(false);
        entity.setVelocity(new Vector(0, 0, 0));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.hideEntity(plugin, entity);
        }
        return state;
    }

    /**
     * Reveals an entity once its rebuild has finished, restoring what {@link #hide} changed.
     *
     * <p>The entity is shown to players unconditionally, even if it is no longer valid.
     * Hidden state lives on each player keyed by UUID, so skipping the reveal for an entity
     * that was removed or unloaded mid-rebuild would leave it invisible to everyone who was
     * online — long after it came back.</p>
     */
    private void reveal(HiddenEntity state) {
        Entity entity = state.entity();
        hidden.remove(entity.getUniqueId());

        if (entity.isValid()) {
            entity.setGravity(state.gravity());
            Location origin = state.location();
            if (entity.getWorld().equals(origin.getWorld())
                    && entity.getLocation().distanceSquared(origin) > 1.0E-6) {
                entity.teleport(origin);
            }
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.showEntity(plugin, entity);
        }
    }

    /**
     * Hides in-flight entities from a player who joined mid-rebuild.
     *
     * <p>Hidden state is per-player, so a joiner would otherwise see the one armor stand
     * that survived a crater everyone else watched vanish.</p>
     */
    public void hideInFlightFor(Player player) {
        for (HiddenEntity state : hidden.values()) {
            if (state.entity().isValid()) {
                player.hideEntity(plugin, state.entity());
            }
        }
    }

    /**
     * Reveals everything still hidden, undoing all freezes.
     *
     * <p>A safety net for shutdown and reload: rebuilds finishing normally reveal their own
     * entities, but nothing may be left hidden if this manager is about to be replaced.</p>
     */
    public void revealAll() {
        for (HiddenEntity state : new ArrayList<>(hidden.values())) {
            reveal(state);
        }
        hidden.clear();
        pending.clear();
    }
}

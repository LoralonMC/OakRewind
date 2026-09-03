package dev.oakheart.oakrewind.listeners;

import dev.oakheart.oakrewind.WorldRebuildHandler;
import dev.oakheart.oakrewind.managers.EntityProtectionManager;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewinds the blocks a wither smashes by hand, which never reach {@link ExplosionListener}.
 *
 * <p>A wither breaks blocks two different ways and only one of them is an explosion. Its spawn
 * blast and its skull projectiles fire {@code EntityExplodeEvent} and are handled elsewhere. Its
 * mob-griefing melee smash — the box it clears when damaged or when it charges — never explodes
 * at all: {@code WitherBoss} fires {@code EntityChangeBlockEvent} once per block and then calls
 * {@code destroyBlock(pos, true, this)}. Without this listener those blocks were simply never
 * rebuilt, so a wither fight left permanent holes that read as griefing while the skull craters
 * around them healed normally.</p>
 *
 * <p>The event is cancelled rather than observed. It fires <em>before</em> the block is broken,
 * and the break drops items, so letting it run and rebuilding afterwards would hand the player a
 * free copy of every block the wither touched. Cancelling means nothing is ever dropped, and the
 * block is then cleared by the rebuild itself — the same custody model the explosion path uses,
 * where the blast list is aired here before the server gets to it.</p>
 *
 * <p>The smash reports one event per block but clears its whole box in a single tick, so blocks
 * are accumulated and handed over as one rebuild at the end of the tick. Rebuilding each block
 * on its own would start a separate animation, each with its own initial delay, instead of one
 * crater healing bottom-up.</p>
 */
public class WitherGriefListener implements Listener {

    private final Plugin plugin;
    private final WorldRebuildHandler worldRebuildHandler;
    private final EntityProtectionManager protectionManager;
    private final boolean configEnableRebuild;
    private final boolean configWitherEnabled;

    /**
     * Blocks smashed so far this tick, per world. A set because two withers with overlapping
     * boxes would otherwise report the same block twice, and a rebuild that places a block it
     * has already placed calls {@code breakNaturally()} on it — dropping the very item this
     * listener cancels the event to avoid.
     */
    private final Map<World, Set<Block>> pending = new LinkedHashMap<>();
    private boolean flushScheduled = false;

    public WitherGriefListener(Plugin plugin, WorldRebuildHandler worldRebuildHandler,
                               EntityProtectionManager protectionManager,
                               boolean enableRebuild, Set<EntityType> enabledExplosionTypes) {
        this.plugin = plugin;
        this.worldRebuildHandler = worldRebuildHandler;
        this.protectionManager = protectionManager;
        this.configEnableRebuild = enableRebuild;
        // Reuses the WITHER entry in enabled-explosion-types: from an admin's side "rebuild what
        // withers destroy" is one decision, not two, and splitting it into a second key would let
        // the two halves of the same fight disagree.
        this.configWitherEnabled = enabledExplosionTypes.contains(EntityType.WITHER);
    }

    /**
     * Takes custody of a block the wither is about to smash.
     *
     * <p>{@code HIGHEST} + {@code ignoreCancelled} for the same reason as
     * {@link ExplosionListener}: a claim plugin that already denied the grief has the final say,
     * and rebuilding a block nothing was allowed to break would mean this plugin was the one
     * that removed it.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!configEnableRebuild || !configWitherEnabled) {
            return;
        }
        if (event.getEntityType() != EntityType.WITHER || !event.getTo().isAir()) {
            return;
        }

        event.setCancelled(true);

        Block block = event.getBlock();
        Set<Block> blocks = pending.computeIfAbsent(block.getWorld(), world -> new LinkedHashSet<>());
        if (blocks.isEmpty()) {
            // Vanilla only plays the smash sound if at least one block actually broke, and
            // cancelling means none did. Without this the wither deletes a wall in silence.
            block.getWorld().playSound(event.getEntity().getLocation(),
                    Sound.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.HOSTILE, 1.0f, 1.0f);
        }
        blocks.add(block);

        if (!flushScheduled) {
            flushScheduled = true;
            plugin.getServer().getScheduler().runTask(plugin, this::flush);
        }
    }

    /**
     * Hands everything smashed this tick to the rebuild handler, one rebuild per world.
     */
    private void flush() {
        flushScheduled = false;
        if (pending.isEmpty()) {
            return;
        }

        Map<World, Set<Block>> batches = new LinkedHashMap<>(pending);
        pending.clear();

        for (Set<Block> blocks : batches.values()) {
            List<Block> list = new ArrayList<>(blocks);
            protectionManager.markHangingLosingSupport(list);
            worldRebuildHandler.rebuild(list, protectionManager.claimPending());
        }
    }

    /**
     * Drops a batch that has not been flushed yet, for shutdown and reload.
     *
     * <p>Nothing is lost by dropping it. The smash was cancelled, so these blocks are still
     * standing and never needed rebuilding — the batch exists only to animate them away and
     * back. Flushing instead would be actively worse: it would air the blocks against a rebuild
     * handler that is being torn down, and on shutdown the scheduler no longer accepts the
     * follow-up task that would put them back.</p>
     *
     * <p>The already-scheduled flush still fires on this instance if the server is only
     * reloading, and finds nothing to do.</p>
     */
    public void discard() {
        pending.clear();
        flushScheduled = false;
    }
}

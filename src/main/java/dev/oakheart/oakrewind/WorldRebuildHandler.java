package dev.oakheart.oakrewind;

import dev.oakheart.oakrewind.config.ConfigManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the rebuilding of blocks after explosions with configurable patterns and animations.
 *
 * <p>This class manages the delayed reconstruction of destroyed blocks, supporting multiple
 * rebuild patterns (TOP_DOWN, BOTTOM_UP, CENTER_OUT, RANDOM) with particle effects and sounds.</p>
 *
 * <p>The rebuild process captures block states before destruction, then gradually restores them
 * over time with an exponentially decreasing delay between each block placement.</p>
 *
 * @author Loralon
 */
public class WorldRebuildHandler {
    private static final long MS_PER_TICK = 50L;

    private final JavaPlugin plugin;

    // Configuration values
    private final long configDelay;
    private final double configDelayFalloff;
    private final long configMinDelay;
    private final ConfigManager.RebuildPattern configPattern;
    private final boolean configParticlesEnabled;
    private final Particle configParticleType;
    private final int configParticleCount;

    private final Set<BlockRebuilder> blockRebuilders = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new WorldRebuildHandler with the specified configuration.
     *
     * @param plugin the plugin instance
     * @param configDelay initial delay in milliseconds before starting rebuild
     * @param configDelayFalloff rate at which delay decreases (exponential decay factor)
     * @param configMinDelay minimum delay in milliseconds between block placements
     * @param pattern the rebuild pattern to use (TOP_DOWN, BOTTOM_UP, CENTER_OUT, or RANDOM)
     * @param particlesEnabled whether to spawn particles during rebuild
     * @param particleType the type of particle to spawn
     * @param particleCount number of particles to spawn per block
     */
    public WorldRebuildHandler(JavaPlugin plugin, long configDelay, double configDelayFalloff, long configMinDelay,
                               ConfigManager.RebuildPattern pattern, boolean particlesEnabled, Particle particleType, int particleCount) {
        this.plugin = plugin;
        this.configDelay = configDelay;
        this.configDelayFalloff = configDelayFalloff;
        this.configMinDelay = configMinDelay;
        this.configPattern = pattern;
        this.configParticlesEnabled = particlesEnabled;
        this.configParticleType = particleType;
        this.configParticleCount = particleCount;
    }

    /**
     * Schedules the rebuilding of destroyed blocks with animated effects.
     *
     * <p>This method captures the current state of all non-air blocks, removes them immediately,
     * then schedules their gradual restoration using the configured rebuild pattern.</p>
     *
     * @param blocks the list of blocks to rebuild
     */
    public void rebuild(final List<Block> blocks) {
        rebuild(blocks, null);
    }

    /**
     * Schedules the rebuilding of destroyed blocks, then runs a callback once they are back.
     *
     * @param blocks the list of blocks to rebuild
     * @param onComplete run on the main thread when the last block is placed, or null for none.
     *                   Always runs exactly once, including when the rebuild is finished early
     *                   by {@link #shutdown()} or when there was nothing to rebuild at all.
     */
    public void rebuild(final List<Block> blocks, final Runnable onComplete) {
        // Store a snapshot of all block states. TNT is deliberately left alone: the server's
        // post-event block pass primes any TNT block it still finds into a live entity,
        // preserving chain reactions. Snapshotting it here would air it before that pass
        // runs, so it would never prime and would come back as an inert block instead.
        final List<BlockState> states = new ArrayList<>();
        for (Block block : blocks) {
            if (!block.getType().isAir() && block.getType() != Material.TNT) {
                states.add(block.getState());
            }
        }

        // Set everything to air without triggering physics
        for (Block block : blocks) {
            if (block.getType() != Material.TNT) {
                setAirNoDrops(block);
            }
        }

        // Nothing to rebuild: an empty rebuilder would never be scheduled and so would never
        // complete, stranding anything waiting on the callback.
        if (states.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        // Schedule rebuild
        blockRebuilders.add(new BlockRebuilder(states, onComplete));
    }

    private long msToTicks(long ms) {
        return ms / MS_PER_TICK;
    }

    private void setAirNoDrops(Block block) {
        if (!block.getType().isAir()) {
            block.setType(Material.AIR, false);
        }
    }

    /**
     * Lifts living entities out of a block that was just restored into them.
     *
     * <p>Restoring a block pays no attention to entities, so anything standing in the
     * crater would be sealed inside and suffocate — in a plugin whose whole premise is
     * that explosions leave no lasting harm. Anyone caught inside is moved to the
     * nearest open spot, preferring the same level and solid footing, so a player in a
     * cave steps aside rather than surfacing (an upward-only scan sent cave players to
     * ground level: the column above them is solid rock, so the first opening was the
     * surface). Normal rebuilds rarely get here — occupied blocks are deferred first
     * (see {@link BlockRebuilder}); this handles the forced paths (deferral cap,
     * shutdown, reload).</p>
     *
     * <p>Armor stands are left alone: a shielded stand is frozen exactly where it
     * belongs, and moving it would undo the entity rewind. Spectators can't suffocate.</p>
     */
    private void ejectEntities(Block block) {
        if (block.isPassable()) {
            return;
        }
        for (Entity entity : block.getWorld().getNearbyEntities(BoundingBox.of(block), WorldRebuildHandler::isEjectable)) {
            entity.teleport(findNearestOpenSpot(entity, block));
        }
    }

    // Nearest-open-spot search bounds. Horizontal is generous (a crater is at most a
    // few blocks wide); vertical is tight so "nearby" never means another cave layer.
    private static final int EJECT_SEARCH_RADIUS = 8;
    private static final int EJECT_SEARCH_VERTICAL = 4;

    /**
     * Finds the best open spot near the entity's current position: the closest
     * position with headroom, scored to prefer staying on the same level, moving up
     * over down, and having solid ground underfoot over hovering into a drop.
     * Falls back to the legacy straight-up scan if the search box is entirely solid
     * (entity sealed deep inside rock with no cavity in range).
     */
    private Location findNearestOpenSpot(Entity entity, Block sealedBlock) {
        World world = entity.getWorld();
        Location location = entity.getLocation();
        int height = Math.max(1, (int) Math.ceil(entity.getHeight()));
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();

        int bestScore = Integer.MAX_VALUE;
        int bestX = 0, bestY = 0, bestZ = 0;
        for (int dx = -EJECT_SEARCH_RADIUS; dx <= EJECT_SEARCH_RADIUS; dx++) {
            for (int dz = -EJECT_SEARCH_RADIUS; dz <= EJECT_SEARCH_RADIUS; dz++) {
                for (int dy = -EJECT_SEARCH_VERTICAL; dy <= EJECT_SEARCH_VERTICAL; dy++) {
                    int x = baseX + dx, y = baseY + dy, z = baseZ + dz;
                    if (y < world.getMinHeight() || y > world.getMaxHeight() - height) {
                        continue;
                    }
                    if (!isOpen(world, x, y, z, height)) {
                        continue;
                    }
                    boolean hasFloor = y > world.getMinHeight() && !world.getBlockAt(x, y - 1, z).isPassable();
                    // Weights: vertical distance counts 4x (stay on your level), downward
                    // adds a nudge (prefer up on ties), floorless spots cost as much as
                    // ~8 blocks of horizontal distance (step aside beats dangling).
                    int score = dx * dx + dz * dz + dy * dy * 4 + (dy < 0 ? 2 : 0) + (hasFloor ? 0 : 64);
                    if (score < bestScore) {
                        bestScore = score;
                        bestX = x;
                        bestY = y;
                        bestZ = z;
                    }
                }
            }
        }

        Location destination = location.clone();
        if (bestScore != Integer.MAX_VALUE) {
            destination.set(bestX + 0.5, bestY, bestZ + 0.5);
        } else {
            destination.setY(firstOpenY(entity, sealedBlock.getY() + 1));
        }
        return destination;
    }

    private static boolean isEjectable(Entity entity) {
        if (!(entity instanceof LivingEntity) || entity instanceof ArmorStand || !entity.isValid()) {
            return false;
        }
        return !(entity instanceof Player player && player.getGameMode() == GameMode.SPECTATOR);
    }

    /**
     * @return the lowest Y at or above {@code startY} where the entity has headroom
     */
    private int firstOpenY(Entity entity, int startY) {
        World world = entity.getWorld();
        Location location = entity.getLocation();
        int height = Math.max(1, (int) Math.ceil(entity.getHeight()));
        int highestY = world.getMaxHeight() - height;
        for (int y = startY; y <= highestY; y++) {
            if (isOpen(world, location.getBlockX(), y, location.getBlockZ(), height)) {
                return y;
            }
        }
        return highestY;
    }

    private boolean isOpen(World world, int x, int y, int z, int height) {
        for (int i = 0; i < height; i++) {
            if (!world.getBlockAt(x, y + i, z).isPassable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Shuts down all active rebuilders, immediately finishing all pending rebuilds.
     *
     * <p>This method should be called when the plugin is disabled to ensure all blocks
     * are restored before shutdown. It cancels scheduled tasks and rebuilds all remaining
     * blocks immediately.</p>
     */
    public void shutdown() {
        // Keep clearing until all rebuilders are finished, handling any that are added during shutdown
        while (!blockRebuilders.isEmpty()) {
            Set<BlockRebuilder> rebuildersCopy = new HashSet<>(blockRebuilders);
            blockRebuilders.clear();
            for (final BlockRebuilder r : rebuildersCopy) {
                r.finishNow();
            }
        }
    }

    public class BlockRebuilder implements Runnable {
        // Retries before an occupied block is placed anyway and the entity ejected.
        // Retries only start once the rest of the queue is exhausted, one per
        // scheduled tick at the configured min delay — roughly a few seconds of
        // standing in the last open spot of the crater.
        private static final int MAX_DEFERRALS = 100;

        private final List<BlockState> states;
        private final Runnable onComplete;
        private final Map<BlockState, Integer> deferrals = new IdentityHashMap<>();
        private BukkitTask task = null;
        private long blocksRebuilt = 0;
        private boolean completed = false;

        public BlockRebuilder(final List<BlockState> states, final Runnable onComplete) {
            this.states = states;
            this.onComplete = onComplete;
            if (this.states.isEmpty()) {
                return;
            }

            // Sort blocks based on configured pattern
            sortBlocksByPattern(this.states);

            // Initialize delay
            task = plugin.getServer().getScheduler().runTaskLater(plugin, this, msToTicks(configDelay));
        }

        private void sortBlocksByPattern(List<BlockState> states) {
            switch (configPattern) {
                case TOP_DOWN:
                    sortTopDown(states);
                    break;
                case BOTTOM_UP:
                    sortBottomUp(states);
                    break;
                case CENTER_OUT:
                    sortCenterOut(states);
                    break;
                case RANDOM:
                    Collections.shuffle(states);
                    break;
            }
        }

        private void sortTopDown(List<BlockState> states) {
            // Find top center point for rebuild order reference
            Vector center = new Vector(0, 0, 0);
            int maxY = 0;
            for (final BlockState state : states) {
                maxY = Math.max(maxY, state.getY());
                center.add(state.getLocation().toVector());
            }
            center.multiply(1.0 / states.size());
            center.setY(maxY + 1);

            // Sort blocks to rebuild them from top to bottom
            states.sort(new BlockDistanceComparator(center));
        }

        private void sortBottomUp(List<BlockState> states) {
            // Find bottom center point
            Vector center = new Vector(0, 0, 0);
            int minY = Integer.MAX_VALUE;
            for (final BlockState state : states) {
                minY = Math.min(minY, state.getY());
                center.add(state.getLocation().toVector());
            }
            center.multiply(1.0 / states.size());
            center.setY(minY - 1);

            // Sort blocks to rebuild them from bottom to top
            states.sort(new BlockDistanceComparator(center));
        }

        private void sortCenterOut(List<BlockState> states) {
            // Find true center point
            Vector center = new Vector(0, 0, 0);
            for (final BlockState state : states) {
                center.add(state.getLocation().toVector());
            }
            center.multiply(1.0 / states.size());

            // Sort blocks to rebuild them from center outward
            states.sort(new BlockDistanceComparator(center));
        }

        private void finish() {
            task = null;
            WorldRebuildHandler.this.blockRebuilders.remove(this);

            // Guarded: finish() is reachable both from the normal run() path and from
            // finishNow(), and the callback must not fire twice.
            if (!completed) {
                completed = true;
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }

        /**
         * Restores the next block, unless a living entity is standing where it goes.
         * An occupied block is deferred — moved to the far end of the queue (blocks
         * are consumed from the end, so index 0 rebuilds last) — and the animation
         * continues around the entity. This is the expected behavior from the player's
         * side: the wall doesn't materialize inside you while you stand in the crater.
         * Each state gets {@code MAX_DEFERRALS} retries; after that it is placed
         * anyway and {@link #ejectEntities} relocates the entity, so a parked player
         * or mob can't hold the rebuild open forever.
         *
         * @return true if a block was placed, false if it was deferred
         */
        private boolean rebuildNextBlock() {
            BlockState state = states.remove(states.size() - 1);
            if (shouldDefer(state)) {
                states.add(0, state);
                return false;
            }
            deferrals.remove(state);
            rebuildBlock(state, true);
            return true;
        }

        private boolean shouldDefer(BlockState state) {
            // isSolid() approximates "would seal an entity in" without placing the
            // block first; over-deferring an occupied slab or stair is harmless.
            if (!state.getType().isSolid()) {
                return false;
            }
            Block block = state.getBlock();
            if (block.getWorld().getNearbyEntities(BoundingBox.of(block), WorldRebuildHandler::isEjectable).isEmpty()) {
                return false;
            }
            return deferrals.merge(state, 1, Integer::sum) <= MAX_DEFERRALS;
        }

        /**
         * @param effects whether to play the placement sound and particles. Bulk placement
         *                on shutdown or reload passes false — finishing a large rebuild in
         *                one tick would otherwise fire every sound and particle at once.
         */
        private void rebuildBlock(final BlockState state, final boolean effects) {
            final Block block = state.getBlock();
            ++blocksRebuilt;

            // Break any block that isn't air first
            if (!block.getType().isAir()) {
                block.breakNaturally();
            }

            // Force update without physics; also applies block-entity data (containers, signs)
            state.update(true, false);

            // Anything alive standing here would be sealed inside the restored block
            ejectEntities(block);

            if (!effects) {
                return;
            }

            // Play sound
            block.getWorld().playSound(block.getLocation(), block.getBlockSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 0.8f);

            // Spawn particles if enabled
            if (configParticlesEnabled) {
                Location particleLocation = block.getLocation().add(0.5, 0.5, 0.5);
                block.getWorld().spawnParticle(
                        configParticleType,
                        particleLocation,
                        configParticleCount,
                        0.3, 0.3, 0.3, // Spread in x, y, z
                        0.0 // Extra data (speed for some particles)
                );
            }
        }

        public void finishNow() {
            if (task != null) {
                task.cancel();
            }
            for (final BlockState state : states) {
                rebuildBlock(state, false);
            }
            finish();
        }

        @Override
        public void run() {
            if (states.isEmpty()) {
                finish();
            } else {
                // Rebuild next block
                boolean placed = rebuildNextBlock();

                // Adjust delay. A deferred (occupied) block retries at the min delay:
                // waiting the decayed animation delay per retry would let a player
                // standing in a small crater stall the rebuild for minutes.
                long delay = placed
                        ? msToTicks(Math.max(configMinDelay, (long) (configDelay * Math.exp(-blocksRebuilt * configDelayFalloff))))
                        : msToTicks(configMinDelay);
                task = plugin.getServer().getScheduler().runTaskLater(plugin, this, delay);
            }
        }
    }

    public static class BlockDistanceComparator implements Comparator<BlockState> {
        private final Vector referencePoint;

        public BlockDistanceComparator(final Vector referencePoint) {
            this.referencePoint = referencePoint;
        }

        @Override
        public int compare(final BlockState a, final BlockState b) {
            // Sort DESCENDING by distance to the reference point: blocks are
            // consumed from the END of the list, so the block NEAREST the
            // reference rebuilds first. Ascending order inverted every pattern
            // (TOP_DOWN built bottom-up, CENTER_OUT built edge-in).
            final double da = a.getLocation().toVector().subtract(referencePoint).lengthSquared();
            final double db = b.getLocation().toVector().subtract(referencePoint).lengthSquared();
            return Double.compare(db, da);
        }
    }
}

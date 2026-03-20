package dev.oakheart.oakrewind;

import dev.oakheart.oakrewind.config.ConfigManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
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
        // Store a snapshot of all block states
        final List<BlockState> states = new ArrayList<>();
        for (Block block : blocks) {
            if (block.getType() != Material.AIR) {
                states.add(block.getState());
            }
        }

        // Set everything to air without triggering physics
        for (Block block : blocks) {
            setAirNoDrops(block);
        }

        // Schedule rebuild
        blockRebuilders.add(new BlockRebuilder(states));
    }

    private long msToTicks(long ms) {
        return ms / MS_PER_TICK;
    }

    private void setAirNoDrops(Block block) {
        if (block.getType() != Material.AIR) {
            block.setType(Material.AIR, false);
        }
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
        private final List<BlockState> states;
        private BukkitTask task = null;
        private long blocksRebuilt = 0;

        public BlockRebuilder(final List<BlockState> states) {
            this.states = states;
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
        }

        private void rebuildNextBlock() {
            rebuildBlock(states.remove(states.size() - 1));
        }

        private void rebuildBlock(final BlockState state) {
            final Block block = state.getBlock();
            ++blocksRebuilt;

            // Break any block that isn't air first
            if (block.getType() != Material.AIR) {
                block.breakNaturally();
            }

            // Force update without physics to set block type
            state.update(true, false);
            // Second update forces block state specific update
            state.update(true, false);

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
                rebuildBlock(state);
            }
            finish();
        }

        @Override
        public void run() {
            if (states.isEmpty()) {
                finish();
            } else {
                // Rebuild next block
                rebuildNextBlock();

                // Adjust delay
                long delay = msToTicks(
                        Math.max(configMinDelay, (int) (configDelay * Math.exp(-blocksRebuilt * configDelayFalloff)))
                );
                task = plugin.getServer().getScheduler().runTaskLater(plugin, this, delay);
            }
        }
    }

    /**
     * Cancels all active rebuilders without finishing the rebuilds.
     *
     * <p>This method stops all ongoing rebuilds immediately, leaving blocks unrestored.
     * Note: Currently unused - use {@link #shutdown()} instead to finish rebuilds properly.</p>
     */
    public void cancelAllRebuilders() {
        Set<BlockRebuilder> rebuildersCopy = new HashSet<>(blockRebuilders);
        for (BlockRebuilder blockRebuilder : rebuildersCopy) {
            if (blockRebuilder.task != null) {
                blockRebuilder.task.cancel();
            }
        }
        blockRebuilders.clear();
    }

    public static class BlockDistanceComparator implements Comparator<BlockState> {
        private final Vector referencePoint;

        public BlockDistanceComparator(final Vector referencePoint) {
            this.referencePoint = referencePoint;
        }

        @Override
        public int compare(final BlockState a, final BlockState b) {
            // Sort by distance to top-most center. Last block will be rebuilt first.
            final double da = a.getLocation().toVector().subtract(referencePoint).lengthSquared();
            final double db = b.getLocation().toVector().subtract(referencePoint).lengthSquared();
            return Double.compare(da, db);
        }
    }
}

package net.minestom.server.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.chunksystem.ChunkAndClaim;
import net.minestom.server.instance.chunksystem.ChunkClaim;
import net.minestom.server.instance.chunksystem.ChunkManager;
import net.minestom.server.instance.chunksystem.ClaimCallbacks;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Responsible for loading chunks around and sending chunks to the player.
 * <p>
 * This class interacts with the chunk-system and tells the send-queue which chunks to send.
 * The send-queue then is responsible for sending the chunks in the correct order, with rate limits, etc.
 */
public class PlayerChunkTracker {
    private static final ChunkClaim.Shape CLAIM_SHAPE = ServerFlag.INSIDE_TEST ? ChunkClaim.Shape.SQUARE : ChunkClaim.Shape.CIRCLE;
    private final Player player;
    // Even though adding/removing claims is done on the player thread, we need this lock for the callbacks and updating visibleChunks
    private final ReentrantLock lock = new ReentrantLock();
    private final LongSet chunksSentOrInQueue = new LongOpenHashSet();
    private @Nullable Tracked tracked;
    private boolean spawningPlayer;
    private volatile int priority = 0;
    // TODO remove, was used for debugging purposes. Maybe this can also be modified to an assert statement?
    private final AtomicInteger staleCallbackCount = new AtomicInteger();

    public PlayerChunkTracker(Player player) {
        this.player = player;
    }

    public void changePosition() {
        changePosition(true);
    }

    public void changePosition(boolean sendUnloads) {
        var instance = player.getInstance();
        if (instance == null) throw new IllegalStateException("No instance set");
        var position = player.getPosition();
        var tracked = addClaim(instance, position);
        changeTracked(tracked, sendUnloads);
    }

    /**
     * Changes the tracked chunk. (Basically moves a claim).
     * Only called if old and new claims are on the same chunk manager.
     * <p>
     * Always called from player tick thread.
     *
     * @param tracked     the new chunk to track
     * @param sendUnloads whether unload packets should be sent
     */
    private void changeTracked(Tracked tracked, boolean sendUnloads) {
        lock.lock();
        try {
            if (this.tracked == null) {
                throw new IllegalStateException("Tried to change tracking when nothing was being tracked. This is most likely a logic error.");
            }
            if (tracked.chunkManager() != this.tracked.chunkManager()) {
                throw new IllegalArgumentException("Tried to change tracking to a tracked object from another instance. This is not allowed!");
            }

            this.tracked.untrack();

            // The simplest approach is to iterate through all chunks. Let's use that and see how well it does
            for (var it = chunksSentOrInQueue.longIterator(); it.hasNext(); ) {
                long chunkIndex = it.nextLong();
                int chunkX = CoordConversion.chunkIndexGetX(chunkIndex);
                int chunkZ = CoordConversion.chunkIndexGetZ(chunkIndex);
                if (tracked.chunkAndClaim().claim().contains(chunkX, chunkZ)) {
                    // Chunk is visible in the new claim
                    continue;
                }
                it.remove();
                player.getChunkQueue().cancelSend(chunkX, chunkZ);
                if (sendUnloads) {
                    // TODO there may be an argument to have an "unload queue"
                    player.sendPacket(new UnloadChunkPacket(chunkX, chunkZ));
                }
            }

            this.tracked = tracked;
            sendStale();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Special method to update the instance of the tracked object, but don't send any chunk updates.
     * Used for shared instances
     */
    @ApiStatus.Internal
    public void updateInstanceSameChunks(Tracked tracked) {
        lock.lock();
        try {
            if (this.tracked == null) {
                throw new IllegalStateException("Tried to change tracking when nothing was being tracked. This is most likely a logic error.");
            }
            this.tracked.untrack();
            this.tracked = tracked;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Special method to update the instance of the tracked object.
     */
    @ApiStatus.Internal
    public void updateInstanceDifferentChunks(Tracked tracked, boolean dimensionChange) {
        lock.lock();
        try {
            if (this.tracked == null) {
                throw new IllegalStateException("Tried to change tracking when nothing was being tracked. This is most likely a logic error.");
            }

            this.tracked.untrack();
            var claim = tracked.chunkAndClaim.claim();

            // Clear all chunks
            var it = chunksSentOrInQueue.longIterator();
            while (it.hasNext()) {
                long chunkIndex = it.nextLong();
                int chunkX = CoordConversion.chunkIndexGetX(chunkIndex);
                int chunkZ = CoordConversion.chunkIndexGetZ(chunkIndex);
                player.getChunkQueue().cancelSend(chunkX, chunkZ);

                if (!dimensionChange) {
                    // Only send UnloadChunkPacket for chunks no longer in the new view.
                    // This alleviates a 26.2 client bug where, if it processes an UnloadChunkPacket
                    // and a ChunkDataPacket for the same chunk in the same frame, the chunk disappears.
                    // https://bugs.mojang.com/browse/MC/issues/MC-310041
                    // TODO(26.3): Revert this change; the client bug is fixed in 26.3-snapshot5
                    assert MinecraftServer.DATA_PACK_VERSION.major() < 112 : "Fixed in 26.3-snapshot5. Revert to always send chunk unload packet";

                    if (claim.contains(chunkX, chunkZ)) {
                        continue; // Skip unload packet
                    }
                }

                player.sendPacket(new UnloadChunkPacket(chunkX, chunkZ));
            }
            chunksSentOrInQueue.clear();

            this.tracked = tracked;
            sendStale();
        } finally {
            lock.unlock();
        }
    }

    public void beginRespawn() {
        lock.lock();
        try {
            player.getChunkQueue().resetState();
            chunksSentOrInQueue.clear();
        } finally {
            lock.unlock();
        }
    }

    public void finishRespawn() {

    }

    @ApiStatus.Internal
    public void beginSpawnPlayer() {
        lock.lock();
        try {
            if (spawningPlayer) {
                throw new IllegalStateException("Already spawning player????");
            }
            spawningPlayer = true;
        } finally {
            lock.unlock();
        }
    }

    @ApiStatus.Internal
    public void endSpawnPlayer() {
        lock.lock();
        try {
            if (!spawningPlayer) {
                throw new IllegalStateException("Not spawning player????");
            }
            spawningPlayer = false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears all chunks in the queue and resends all tracked chunks.
     * Mainly used after a player respawn
     */
    public void resendAllVisibleChunks() {
        lock.lock();
        try {
            if (this.tracked == null) {
                throw new IllegalStateException("Tried to resend all chunks when tracking is disabled. This is most likely a logic error.");
            }
            if (spawningPlayer) {
                // Only send chunks if we are not spawning the player. (setInstance)
                // If we are spawning, the tracked object will change.
                return;
            }

            for (var it = chunksSentOrInQueue.longIterator(); it.hasNext(); ) {
                long chunkIndex = it.nextLong();
                int chunkX = CoordConversion.chunkIndexGetX(chunkIndex);
                int chunkZ = CoordConversion.chunkIndexGetZ(chunkIndex);
                player.getChunkQueue().cancelSend(chunkX, chunkZ);
            }
            chunksSentOrInQueue.clear();

            sendStale();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Starts tracking the current chunk (adds a claim)
     * <p>
     * Always called from player tick thread
     */
    public void startTracking(Tracked tracked) {
        lock.lock();
        try {
            if (this.tracked != null) {
                throw new IllegalStateException("Tried to start tracking when already tracking. This is most likely a logic error.");
            }
            this.tracked = tracked;
            sendStale();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops tracking the current chunk (removes the claim)
     * <p>
     * Always called from player tick thread
     */
    public void stopTracking() {
        lock.lock();
        try {
            if (tracked == null) {
                throw new IllegalStateException("Tried to stop tracking when nothing was being tracked. This is most likely a logic error.");
            }
            tracked.untrack();
            tracked = null;

            // Clear all chunks
            var it = chunksSentOrInQueue.longIterator();
            while (it.hasNext()) {
                long chunkIndex = it.nextLong();
                int chunkX = CoordConversion.chunkIndexGetX(chunkIndex);
                int chunkZ = CoordConversion.chunkIndexGetZ(chunkIndex);
                player.getChunkQueue().cancelSend(chunkX, chunkZ);
                player.sendPacket(new UnloadChunkPacket(chunkX, chunkZ));
            }
            chunksSentOrInQueue.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tries to send all visible chunks. This is useful after instance changes/changing claims.
     * <p>
     * After a claim change, the new claim may already have some chunks loaded immediately, and the chunkLoaded callback won't work for those chunks.
     * This is able to resend all those missed chunks.
     */
    private void sendStale() {
        assert tracked != null;
        for (var chunk : tracked.visibleChunks().values()) {
            staleCallbackCount.decrementAndGet();
            sendChunk(chunk);
        }
    }

    private ClaimCallbacks callbacks(Long2ObjectMap<Chunk> trackedVisibleChunks) {
        return new ClaimCallbacks() {
            @Override
            public void chunkLoaded(ChunkClaim claim, Chunk chunk) {
                long index = CoordConversion.chunkIndex(chunk.getChunkX(), chunk.getChunkZ());
                lock.lock();
                try {
                    trackedVisibleChunks.put(index, chunk);
                    if (tracked == null || tracked.chunkAndClaim().claim() != claim) {
                        // Stale callback for old claim
                        staleCallbackCount.incrementAndGet();
                        return;
                    }
                    sendChunk(chunk);
                } finally {
                    lock.unlock();
                }
            }
        };
    }

    /**
     * Ensures the chunk is enqueued in the chunk queue. Does nothing if previously sent or enqueued.
     *
     * @param chunk the chunk to send
     */
    private void sendChunk(Chunk chunk) {
        int x = chunk.getChunkX();
        int z = chunk.getChunkZ();
        long index = CoordConversion.chunkIndex(x, z);
        boolean sendChunk = chunksSentOrInQueue.add(index);
        if (sendChunk) {
            player.sendChunk(chunk);
        }
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    public Tracked addClaim(Instance instance, Point pos) {
        return addClaim(instance, pos.chunkX(), pos.chunkZ());
    }

    public Tracked addClaim(Instance instance, int chunkX, int chunkZ) {
        var chunkManager = instance.getChunkManager();
        var trackedVisibleChunks = new Long2ObjectOpenHashMap<Chunk>();
        var chunkAndClaim = chunkManager.addClaim(chunkX, chunkZ, this.player.effectiveViewDistance(instance), this.priority, CLAIM_SHAPE, callbacks(trackedVisibleChunks));
        return new Tracked(chunkManager, chunkAndClaim, trackedVisibleChunks);
    }

    /**
     * Represents the tracked area
     *
     * @param visibleChunks the chunks that should be visible to the player. These chunks may still be in the chunk (send) queue.
     */
    public record Tracked(ChunkManager chunkManager, ChunkAndClaim chunkAndClaim, Long2ObjectMap<Chunk> visibleChunks) {
        private void untrack() {
            chunkManager.removeClaim(chunkAndClaim.claim()).exceptionally(t -> {
                MinecraftServer.getExceptionManager().handleException(t);
                return null;
            });
        }
    }
}

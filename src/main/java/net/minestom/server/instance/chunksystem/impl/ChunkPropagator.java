package net.minestom.server.instance.chunksystem.impl;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectRBTreeSet;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkAccess;
import net.minestom.server.instance.chunksystem.ChunkClaim;
import net.minestom.server.utils.async.AsyncUtils;
import net.minestom.server.utils.chunk.ChunkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class ChunkPropagator {
    private static final AtomicInteger ID = new AtomicInteger();
    /**
     * Max value priority can never be reached by propagation, because each step priority is decreased by 1
     */
    private static final int PROPAGATED_INVALID_PRIORITY = Integer.MAX_VALUE;
    private final int id = ID.getAndIncrement();
    private static final int POS_X = 0b0001;
    private static final int NEG_X = 0b0010;
    private static final int POS_Z = 0b0100;
    private static final int NEG_Z = 0b1000;
    private static final int ALL = POS_X | NEG_X | POS_Z | NEG_Z;
    private final ChunkManagerImpl chunkManager;
    // The cache is responsible for keeping chunks in memory.
    // This is required, because ChunkManagerImpl's loader uses weak values
    private final Long2ObjectOpenHashMap<ChunkEntry> cache = new Long2ObjectOpenHashMap<>();
    private final PropagatorTask propagatorTask;
    private final Thread propagatorThread;
    /**
     * This is a lock to support non-parallel loading.
     * We can't use the instance thread, because of possible deadlocks when waiting for a claim.
     */
    private final ReentrantLock loaderLock = new ReentrantLock();
    /**
     * Limit the queue to 10000. If someone exceeds this limit, they are fundamentally using the API wrong, like really wrong.
     * There should be no need to have more than, say around 10, tasks in the queue at a time.
     * Consider a big spawn area being loaded by a single task (claim).
     * That spawn area ticket is in this queue for a very short time.
     * Someone reaching 10000 means they load thousands of chunks manually, which is really bad
     * Even in case they have a good reason, if the queue fills, we just make them wait. At that point it doesn't make a difference in performance
     */
    private final ArrayBlockingQueue<Task> tasks = new ArrayBlockingQueue<>(10000);
    private final ObjectRBTreeSet<Task> workingTasks = new ObjectRBTreeSet<>();
    private final ConcurrentLinkedQueue<Task> propagatedTasks = new ConcurrentLinkedQueue<>();
    private final ChunkGenerator chunkGenerator;


    public ChunkPropagator(ChunkManagerImpl chunkManager) {
        this.chunkManager = chunkManager;
        this.propagatorTask = new PropagatorTask();
        // Dedicate a thread to propagation for an instance.
        // Could later be changed to a thread pool, though I don't see the benefit
        // Because generation should always take longer than managing generation (chunks), the thread SHOULD always be able to keep up
        // The logic could also be moved to the instance tick thread, but that could increase tick times significantly, no harm in keeping it separate
        this.propagatorThread = Thread.ofPlatform().name("ChunkPropagator-" + this.id).start(this.propagatorTask);
        this.chunkGenerator = new ChunkGenerator(chunkManager);
    }

    private void handleAddClaim(long chunkKey, @NotNull ChunkEntry entry, @NotNull ChunkClaim claim, CompletableFuture<ChunkClaim> future) {
        entry.addClaim(claim);

        handlePropagateAddClaim(chunkKey, entry, ALL, new PropagatedClaim(chunkKey, claim.radius(), claim.priority(), claim.shape()));

        future.complete(claim);
    }

    private void handleRemoveClaim(long chunkKey, @Nullable ChunkEntry entry, @NotNull ChunkClaim claim) {
        ChunkEntry e;
        if (entry == null) {
            var cachedEntry = this.cache.get(chunkKey);
            if (cachedEntry == null) {
                throw new IllegalStateException("Tried to remove a claim that wasn't present");
            }
            e = cachedEntry;
        } else e = entry;
        e.removeClaim(claim);
        if (e.claims.isEmpty()) {
            if (e.propagatedClaims.isEmpty()) {
                if (this.cache.remove(chunkKey) != e) {
                    throw new IllegalStateException("Tried to remove a claim that wasn't present");
                }
            }
        }
        // TODO unload/save chunk

    }

    private void handleStatusGenerated(@NotNull ChunkEntry entry, @NotNull PropagatedClaim claim, int chunkX, int chunkZ, int direction) {
        entry.state = ChunkEntry.STATE_GENERATED;
        propagateDirectionAddClaim(entry, claim, chunkX, chunkZ, direction);
    }

    private void handlePropagateAddClaim(long chunkKey, @NotNull ChunkEntry entry, int direction, @NotNull PropagatedClaim claim) {
        var isOrigin = chunkKey == claim.origin();
        if (!isOrigin) {
            // Could later change this to binary search, but there was no reason to do so yet
            var alreadyAdded = entry.propagatedClaims.contains(claim);
            if (alreadyAdded) {
                // The claim was already handled for this entry
                // Propagation should also have already been handled, we can return here
                return;
            }
        }
        var originX = ChunkUtils.getChunkCoordX(claim.origin());
        var originZ = ChunkUtils.getChunkCoordZ(claim.origin());
        var entryX = ChunkUtils.getChunkCoordX(chunkKey);
        var entryZ = ChunkUtils.getChunkCoordZ(chunkKey);
        var radius = claim.radius();
        var radiusSq = claim.radiusSq();
        if (!claim.shape().isInRadius(radius, radiusSq, entryX, entryZ, originX, originZ)) {
            // the chunk is not in radius. Return here
            return;
        }

        if (!entry.loaded) {
            // Keep chunk loaded
            entry.loaded = true;
            entry.propagatedPriority = PROPAGATED_INVALID_PRIORITY;
            this.cache.put(chunkKey, entry);
        }

        var state = entry.state;

        if (!isOrigin) {
            entry.addPropagatedClaim(claim);
        }

        // TODO load/generate chunk
        // TODO load neighbours
        if (state == ChunkEntry.STATE_UNKNOWN) {
            generateChunk(entry, claim, entryX, entryZ, direction);
        } else if (state == ChunkEntry.STATE_GENERATED) {
            propagateDirectionAddClaim(entry, claim, entryX, entryZ, direction);
        }
    }

    private void propagateDirectionAddClaim(@NotNull ChunkEntry entry, @NotNull PropagatedClaim claim, int chunkX, int chunkZ, int direction) {
        if (direction == 0) return; // No propagation
        var originX = ChunkUtils.getChunkCoordX(claim.origin());
        var originZ = ChunkUtils.getChunkCoordZ(claim.origin());
        var priorityDrop = this.priorityDrop(chunkX, chunkZ, originX, originZ);
        if ((direction & POS_X) == POS_X) {
            propagateTo(new PropagatedClaim(claim.origin(), claim.radius(), claim.priority(), claim.shape(), claim.radiusSq()), chunkX, chunkZ);
        }
    }

    private void propagateTo(@NotNull PropagatedClaim claim, int chunkX, int chunkZ) {
        var chunkKey = ChunkUtils.getChunkIndex(chunkX, chunkZ);
        var entry = this.cache.get(chunkKey);
        if (entry == null) {

        }
    }

    private void generateChunk(@NotNull ChunkEntry entry, @NotNull PropagatedClaim claim, int chunkX, int chunkZ, int direction) {
        entry.state = ChunkEntry.STATE_GENERATING;
        var clAccess = chunkManager.chunkLoaderAccess;
        var chunkLoader = clAccess == null ? null : clAccess.getChunkLoader();
        var runAsync = chunkLoader == null || chunkLoader.supportsParallelLoading();
        Runnable runnable = () -> (chunkLoader == null ? AsyncUtils.<Chunk>empty() : chunkLoader.loadChunk(chunkManager.instance, chunkX, chunkZ)).thenCompose(chunk -> {
            if (chunk != null) {
                // Chunk has been loaded from storage
                return CompletableFuture.completedFuture(chunk);
            }
            // Loader couldn't load the chunk, generate it
            return chunkGenerator.generateChunk(entry, chunkX, chunkZ, runAsync).whenComplete((c, t) -> {
                if (c != null) {
                    c.onGenerate();
                }
            });
        }).thenAccept(chunk -> {
            MinecraftServer.process().dispatcher().createPartition(chunk);
            ChunkAccess.onLoad(chunk);

            EventDispatcher.call(new InstanceChunkLoadEvent(chunkManager.instance, chunk));
            entry.chunkFuture.complete(chunk);
            submitPropagatedTask(new Task.StatusGenerated(entry, claim, chunkX, chunkZ, direction));
        }).exceptionally(throwable -> {
            entry.chunkFuture.completeExceptionally(throwable);
            MinecraftServer.getExceptionManager().handleException(throwable);
            return null;
        });

        if (runAsync) {
            CompletableFuture.runAsync(runnable, chunkManager.getGenerationExecutor());
        } else {
            // We still want to run on a thread other than this (managing) thread.
            // To achieve a single-threaded loading, we use a lock. This will significantly slow down everything
            // using the same Executor if multiple chunks are loaded concurrently, because of blocking.
            // Just a trade off that should be acceptable, if this becomes a problem behaviour can be altered
            CompletableFuture.runAsync(() -> {
                // Just simulate single threaded by using a lock
                // Should have same memory visibility guarantees
                this.loaderLock.lock();
                try {
                    runnable.run();
                } finally {
                    this.loaderLock.unlock();
                }
            }, chunkManager.getGenerationExecutor());
        }
    }

    private int priorityDrop(int x, int z, int ox, int oz) {
        // rather drastic priority drop the further we are away from center.
        // this is to allow other priorities to also get handled instead of
        // a single claim with high priority blocking everyone else
        // keep in mind this is applied for each propagation
        var dx = ox - x;
        var dz = oz - z;
        return dx * dx + dz * dz + 1;
    }

    private void handlePropagateRemoveClaim(long chunkKey, @NotNull ChunkEntry entry, int direction, @NotNull PropagatedClaim claim) {

    }

    public CompletableFuture<ChunkClaim> publishAddClaim(long chunkKey, @NotNull ChunkEntry entry, @NotNull ChunkClaim claim) {
        var future = new CompletableFuture<ChunkClaim>();
        this.submitTask(new Task.AddClaim(chunkKey, entry, claim, future));
        return future;
    }

    public void publishRemoveClaim(long chunkKey, @Nullable ChunkEntry entry, @NotNull ChunkClaim claim) {
        this.submitTask(new Task.RemoveClaim(chunkKey, entry, claim));
    }

    public void shutdown() {
        this.propagatorTask.exit = true;
        this.notifyPropagator();
    }

    private long moveX(long chunkKey, int diff) {
        return ChunkUtils.getChunkIndex(ChunkUtils.getChunkCoordX(chunkKey) + diff, ChunkUtils.getChunkCoordZ(chunkKey));
    }

    private long moveZ(long chunkKey, int diff) {
        return ChunkUtils.getChunkIndex(ChunkUtils.getChunkCoordX(chunkKey), ChunkUtils.getChunkCoordZ(chunkKey) + diff);
    }

    private void submitPropagatedTask(Task task) {
        this.submitPropagatedTaskSync(task);
        this.notifyPropagator();
    }

    private void submitPropagatedTaskSync(Task task) {
        this.propagatedTasks.offer(task);
    }

    private void submitTask(Task task) {
        try {
            this.tasks.put(task);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.notifyPropagator();
    }

    private void notifyPropagator() {
        LockSupport.unpark(this.propagatorThread);
    }

    private class PropagatorTask implements Runnable {
        private volatile boolean exit;

        @Override
        public void run() {
            var workingTasks = ChunkPropagator.this.workingTasks;
            while (!this.exit) {
                // This entire process is intentionally designed a little weird.
                // We first collect all waiting tasks into a separate queue, and then
                // execute all tasks in order of their priority.
                // this is to allow lower priority tasks to execute instead of being blocked
                // by a single task with priority 100000
                // Might be a little weird, but the priority is rather a recommendation than a
                // requirement, so this should be fine and even work better in most use cases

                // Collect all tasks into workingTasks queue
                while (true) {
                    var task = ChunkPropagator.this.tasks.poll();
                    if (task == null) break;
                    workingTasks.add(task);
                }
                while (true) {
                    var task = ChunkPropagator.this.propagatedTasks.poll();
                    if (task == null) break;
                    workingTasks.add(task);
                }

                // Then execute all tasks
                while (!workingTasks.isEmpty()) {
                    var task = workingTasks.removeFirst();

                    switch (task) {
                        case Task.AddClaim t -> handleAddClaim(t.chunkKey, t.entry, t.claim, t.future);
                        case Task.RemoveClaim t -> handleRemoveClaim(t.chunkKey, t.entry, t.claim);
                        case Task.PropagateAddClaim t ->
                                handlePropagateAddClaim(t.chunkKey, t.entry, t.direction, t.claim);
                        case Task.PropagateRemoveClaim t ->
                                handlePropagateRemoveClaim(t.chunkKey, t.entry, t.direction, t.claim);
                        case Task.StatusGenerated t ->
                                handleStatusGenerated(t.entry, t.claim, t.chunkX, t.chunkZ, t.direction);
                    }
                }
                if (ChunkPropagator.this.propagatedTasks.isEmpty()) {
                    // Only park if tasks are empty. Removes 1 unpark perk sync propagated task
                    LockSupport.park();
                }
            }
        }
    }

    private sealed interface Task extends Comparable<Task> {
        int sort();

        @Override
        default int compareTo(@NotNull ChunkPropagator.Task o) {
            return Integer.compare(o.sort(), this.sort()); // Reverse order
        }

        record AddClaim(long chunkKey, @NotNull ChunkEntry entry, @NotNull ChunkClaim claim,
                        CompletableFuture<ChunkClaim> future) implements Task {
            @Override
            public int sort() {
                return this.claim.priority();
            }
        }

        record RemoveClaim(long chunkKey, @Nullable ChunkEntry entry, @NotNull ChunkClaim claim) implements Task {
            @Override
            public int sort() {
                return this.claim.priority();
            }
        }

        record StatusGenerated(@NotNull ChunkEntry entry, @NotNull PropagatedClaim claim, int chunkX, int chunkZ,
                               int direction) implements Task {
            @Override
            public int sort() {
                // Max priority, status update is extremely cheap and important to do as soon as possible
                return Integer.MAX_VALUE;
            }
        }

        record PropagateAddClaim(long chunkKey, @NotNull ChunkEntry entry, int direction,
                                 @NotNull PropagatedClaim claim) implements Task {
            @Override
            public int sort() {
                return this.claim.priority();
            }
        }

        record PropagateRemoveClaim(long chunkKey, @NotNull ChunkEntry entry, int direction,
                                    @NotNull PropagatedClaim claim) implements Task {
            // TODO RemoveClaim should always be executed before AddClaim. This is to allow cancelling claims with a very large radius
            @Override
            public int sort() {
                return this.claim.priority();
            }
        }
    }
}

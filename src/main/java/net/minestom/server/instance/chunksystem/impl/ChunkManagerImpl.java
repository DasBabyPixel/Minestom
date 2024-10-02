package net.minestom.server.instance.chunksystem.impl;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import net.minestom.server.instance.IChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.chunksystem.ChunkAndClaim;
import net.minestom.server.instance.chunksystem.ChunkClaim;
import net.minestom.server.instance.chunksystem.ChunkClaim.Shape;
import net.minestom.server.instance.chunksystem.ChunkManager;
import net.minestom.server.utils.chunk.ChunkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * This is an implementation detail intentionally kept public as it is the only way to use this with custom instances.
 * Can change at any time, any users of this will just have to update.
 */
public final class ChunkManagerImpl implements ChunkManager {
    final Instance instance;
    final @Nullable IChunkLoader.Container chunkLoaderAccess;
    // The loader is responsible for loading all ChunkEntries. The loader will always return already
    // loaded ones first and allows unloads, because it has weak values
    final LoadingCache<Long, ChunkEntry> loader;
    private final ChunkPropagator chunkPropagator;
    private @NotNull Executor generationExecutor = ForkJoinPool.commonPool();
    // No reason yet to set this to volatile. Only supposed to be changed before any chunks are loaded
    private int defaultPriority = 0;

    public ChunkManagerImpl(@NotNull Instance instance) {
        this.instance = instance;
        this.loader = Caffeine.newBuilder().weakValues().build(key -> new ChunkEntry());
        this.chunkPropagator = new ChunkPropagator(this);
        this.chunkLoaderAccess = instance instanceof IChunkLoader.Container c ? c : null;
    }

    public @NotNull Executor getGenerationExecutor() {
        return generationExecutor;
    }

    public void setGenerationExecutor(@NotNull Executor generationExecutor) {
        this.generationExecutor = generationExecutor;
    }

    public void shutdown() {
        // TODO actually call this method. Otherwise we'll be leaking threads
        this.chunkPropagator.shutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull CompletableFuture<@NotNull ChunkAndClaim> addClaim(int chunkX, int chunkZ, int radius, int priority, @NotNull Shape shape) {
        var claim = new ChunkClaim(radius, priority, shape);
        var chunkKey = ChunkUtils.getChunkIndex(chunkX, chunkZ);
        var entry = this.loader.get(chunkKey);

        // This will also take care of generation
        var future = this.chunkPropagator.publishAddClaim(chunkKey, entry, claim);

        return future.thenCompose(c -> entry.getChunkFuture().thenApply(chunk -> new ChunkAndClaim(chunk, c)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull CompletableFuture<Void> removeClaim(int chunkX, int chunkZ, @NotNull ChunkClaim claim) {
        var chunkKey = ChunkUtils.getChunkIndex(chunkX, chunkZ);

        // This will also take care of saving
        this.chunkPropagator.publishRemoveClaim(chunkKey, null, claim);

        return CompletableFuture.completedFuture(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultPriority() {
        return this.defaultPriority;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultPriority(int priority) {
        this.defaultPriority = priority;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull CompletableFuture<@NotNull ChunkAndClaim> addClaim(int chunkX, int chunkZ) {
        return addClaim(chunkX, chunkZ, 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull CompletableFuture<@NotNull ChunkAndClaim> addClaim(int chunkX, int chunkZ, int radius) {
        return addClaim(chunkX, chunkZ, radius, this.defaultPriority);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull CompletableFuture<@NotNull ChunkAndClaim> addClaim(int chunkX, int chunkZ, int radius, @NotNull Shape shape) {
        return addClaim(chunkX, chunkZ, radius, this.defaultPriority, shape);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull CompletableFuture<@NotNull ChunkAndClaim> addClaim(int chunkX, int chunkZ, int radius, int priority) {
        return addClaim(chunkX, chunkZ, radius, priority, Shape.SQUARE);
    }
}

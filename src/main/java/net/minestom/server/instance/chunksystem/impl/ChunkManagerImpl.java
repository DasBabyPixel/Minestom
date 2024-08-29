package net.minestom.server.instance.chunksystem.impl;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import net.minestom.server.instance.chunksystem.ChunkAndTicket;
import net.minestom.server.instance.chunksystem.ChunkManager;
import net.minestom.server.instance.chunksystem.ChunkTicket;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ChunkManagerImpl implements ChunkManager {
    // The loader is responsible for loading all ChunkEntries. The loader will always return already
    // loaded ones first and allows unloads, because it has weak values
    private final LoadingCache<Long, ChunkEntry> loader = Caffeine.newBuilder().weakValues().build(ChunkEntry::new);
    // The cache is responsible for keeping them in memory.
    private final ConcurrentHashMap<Long, ChunkEntry> cache = new ConcurrentHashMap<>();
    private int defaultPriority = 0;

    public ChunkManagerImpl() {
        var q = new ChunkPriorityQueue<Runnable>();
        var e = new ThreadPoolExecutor(1, 10, 5, TimeUnit.SECONDS, q);
        e.submit(() -> {
            System.out.println("Task 1");
        });
        e.submit(() -> {
            System.out.println("Task 2");
        });
        try {
            Thread.sleep(7000);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
        e.shutdown();
    }

    public static void main(String[] args) {
        new ChunkManagerImpl();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull CompletableFuture<@NotNull ChunkAndTicket> addTicket(int chunkX, int chunkZ, int radius, int priority) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull CompletableFuture<Void> removeTicket(int chunkX, int chunkZ, @NotNull ChunkTicket ticket) {
        return null;
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
    public @NotNull CompletableFuture<@NotNull ChunkAndTicket> addTicket(int chunkX, int chunkZ) {
        return addTicket(chunkX, chunkZ, 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull CompletableFuture<@NotNull ChunkAndTicket> addTicket(int chunkX, int chunkZ, int radius) {
        return addTicket(chunkX, chunkZ, radius, this.defaultPriority);
    }
}

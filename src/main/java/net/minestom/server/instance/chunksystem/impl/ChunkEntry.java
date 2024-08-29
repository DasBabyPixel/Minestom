package net.minestom.server.instance.chunksystem.impl;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.chunksystem.ChunkTicket;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.PriorityBlockingQueue;

public final class ChunkEntry {
    private static final VarHandle COUNT;
    private final CompletableFuture<Chunk> chunkFuture = new CompletableFuture<>();
    private final ConcurrentSkipListMap<ChunkTicket, Boolean> tickets = new ConcurrentSkipListMap<>(ChunkTicket::compareByPriority);
    // Keep track of separate ticket count, tickets#size() is rather expensive iterating through all tickets
    private volatile int count;

    public ChunkEntry(long chunkIndex) {
    }

    /**
     * @return the amount of tickets that this entry had before adding the new ticket
     */
    public int addTicket(ChunkTicket ticket) {
        if (tickets.put(ticket, Boolean.TRUE) == null) {
            return require();
        }
        throw new IllegalStateException("Tried to add same ticket twice");
    }

    /**
     * @return whether the last ticket was removed
     */
    public boolean removeTicket(ChunkTicket ticket) {
        if (tickets.remove(ticket, Boolean.TRUE)) {
            return release();
        }
        throw new IllegalStateException("Ticket does not exist on this chunk: " + ticket);
    }

    private boolean release() {
        var n = (int) COUNT.getAndAdd(this, -1) - 1;
        if (n < 0) throw new Error("Tried to release with count of 0");
        return n == 0;
    }

    private int require() {
        return (int) COUNT.getAndAdd(this, 1);
    }

    static {
        var lookup = MethodHandles.lookup();
        try {
            COUNT = lookup.findVarHandle(ChunkEntry.class, "count", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new Error(e);
        }
    }
}

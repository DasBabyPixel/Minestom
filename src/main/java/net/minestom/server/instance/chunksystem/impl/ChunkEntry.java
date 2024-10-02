package net.minestom.server.instance.chunksystem.impl;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.chunksystem.ChunkClaim;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public final class ChunkEntry {
    static final int STATE_UNKNOWN = 0;
    static final int STATE_GENERATING = 1;
    static final int STATE_GENERATED = 2;
    // This will be created by the ChunkSupplier as soon as the ChunkEntry is created
    // The future completes when the chunk is available, because it has been loaded
    final CompletableFuture<Chunk> chunkFuture = new CompletableFuture<>();
    /**
     * Sorted claim ArrayList. Manually sorted each time it is modified
     */
    final ArrayList<ChunkClaim> claims = new ArrayList<>(1);
    final ArrayList<PropagatedClaim> propagatedClaims = new ArrayList<>(4);
    /**
     * The priority propagated from neighbours
     */
    int propagatedPriority;
    int state = STATE_UNKNOWN;
    boolean loaded = false;

    int actualPriority() {
        if (this.claims.isEmpty()) {
            return this.propagatedPriority;
        }
        return Math.max(this.claims.getFirst().priority(), this.propagatedPriority);
    }

    public CompletableFuture<Chunk> getChunkFuture() {
        return this.chunkFuture;
    }

    public void addClaim(ChunkClaim claim) {
        this.claims.add(claim);
        if (this.claims.size() != 1) {
            this.claims.sort(ChunkClaim::compareTo);
        }
    }

    public void addPropagatedClaim(PropagatedClaim claim) {
        this.propagatedClaims.add(claim);
        if (this.propagatedClaims.size() != 1) {
            this.propagatedClaims.sort(PropagatedClaim::compareTo);
        }
    }

    public void removeClaim(ChunkClaim claim) {
        // Shouldn't be any need to sort on removal
        if (this.claims.remove(claim)) return;
        throw new IllegalStateException("Claim does not exist on this chunk: " + claim);
    }

    public void removePropagatedClaim(PropagatedClaim claim) {
        // Shouldn't be any need to sort on removal
        if (this.propagatedClaims.remove(claim)) return;
        throw new IllegalStateException("Claim does not exist on this chunk: " + claim);
    }
}

package net.minestom.server.instance.chunksystem.impl;

import net.minestom.server.instance.chunksystem.ChunkClaim.Shape;
import org.jetbrains.annotations.NotNull;

public record PropagatedClaim(long origin, int radius, int priority, @NotNull Shape shape,
                              int radiusSq) implements Comparable<PropagatedClaim> {
    public PropagatedClaim(long origin, int radius, int priority, @NotNull Shape shape) {
        this(origin, radius, priority, shape, radius * radius);
    }

    @Override
    public int compareTo(@NotNull PropagatedClaim o) {
        return Integer.compare(radius, o.radius);
    }
}

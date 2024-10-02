package net.minestom.server.instance;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ChunkAccess {
    public static void onLoad(Chunk chunk) {
        chunk.onLoad();
    }
}

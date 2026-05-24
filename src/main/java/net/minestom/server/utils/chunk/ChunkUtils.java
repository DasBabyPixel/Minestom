package net.minestom.server.utils.chunk;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class ChunkUtils {

    private ChunkUtils() {
    }

    public static boolean isLoaded(@Nullable Chunk chunk) {
        return chunk != null && chunk.isLoaded();
    }

    /**
     * Gets if a chunk is loaded.
     *
     * @param instance the instance to check
     * @param x        instance X coordinate
     * @param z        instance Z coordinate
     * @return true if the chunk is loaded, false otherwise
     * @deprecated Should really be phased out since this is usually meaningless.
     * Future threading requirements should be that any actor on a chunk must always have that chunk claimed.
     * If an actor has to check if the chunk is loaded, they are doing something wrong.
     */
    @Deprecated
    public static boolean isLoaded(Instance instance, double x, double z) {
        final Chunk chunk = instance.getChunk(CoordConversion.globalToChunk(x), CoordConversion.globalToChunk(z));
        return isLoaded(chunk);
    }

    /**
     * @deprecated Should really be phased out since this is usually meaningless.
     * Future threading requirements should be that any actor on a chunk must always have that chunk claimed.
     * If an actor has to check if the chunk is loaded, they are doing something wrong.
     */
    @Deprecated
    public static boolean isLoaded(Instance instance, Point point) {
        final Chunk chunk = instance.getChunk(point.chunkX(), point.chunkZ());
        return isLoaded(chunk);
    }

    @SuppressWarnings({"DataFlowIssue", "deprecation"})
    public static Chunk retrieve(Instance instance, @Nullable Chunk originChunk, double x, double z) {
        final int chunkX = CoordConversion.globalToChunk(x);
        final int chunkZ = CoordConversion.globalToChunk(z);
        final boolean sameChunk = originChunk != null && originChunk.getChunkX() == chunkX && originChunk.getChunkZ() == chunkZ;
        return sameChunk ? originChunk : instance.getChunk(chunkX, chunkZ);
    }

    public static Chunk retrieve(Instance instance, @Nullable Chunk originChunk, Point position) {
        return retrieve(instance, originChunk, position.x(), position.z());
    }
}

package net.minestom.server.instance.light.snapshot;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.light.LightCompute;
import net.minestom.server.instance.light.Neighbors;
import net.minestom.server.instance.palette.Palette;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

class SkyLight {
    private static final Palette STONE = Palette.blocks();
    private final UpdatableContent<byte[]> light = new UpdatableContent<>(LightCompute.CONTENT_FULLY_LIT);
    private final SnapshotLightSection section;
    private final ChunkData chunkData;

    static {
        STONE.fill(Block.STONE.stateId());
    }

    public SkyLight(SnapshotLightSection section, ChunkData chunkData) {
        this.section = section;
        this.chunkData = chunkData;
    }

    void relightSync(CalculationContext context) {
        var skyContext = prepareContext(context);

        int maxY = chunkData.chunk.getInstance().getCachedDimensionType().maxY();
        int sectionY = section.sectionY();
        var sources = prepareLight(skyContext, maxY, sectionY);

        var light = switch (sources) {
            case PreparedLight.ComputeQueue computeQueue ->
                    LightCompute.compute3x3(computeQueue.blockPalettes(), computeQueue.queue());
            case PreparedLight.FullyLit _ -> LightCompute.CONTENT_FULLY_LIT;
        };

        boolean changed = this.light.update(light, context.version());
        if (changed) {
            section.scheduleResendSky();
        }
    }

    private sealed interface PreparedLight {
        record FullyLit() implements PreparedLight {
            static final FullyLit INSTANCE = new FullyLit();
        }

        record ComputeQueue(IntArrayFIFOQueue queue, Palette[] blockPalettes) implements PreparedLight {
        }
    }

    /**
     * Checks whether the section is guaranteed to be fully lit.
     * <p>
     * As of writing, this is not required to find *all* fully lit sections, but is meant as an optimization.
     * Because of this, it is allowed to return false, even if the section is in fact fully lit.
     */
    private static boolean isGuaranteedFullyLit(int[] heightmap, int sectionY) {
        int sectionMinY = sectionY * 16;
        for (int i = 0; i < 16 * 16; i++) {
            int height = heightmap[i];
            if (height > sectionMinY) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("NullableProblems")
    private static PreparedLight prepareLight(SkyContext skyContext, int maxY, int sectionY) {
        int selfChunkIdx = chunkIdx(null);
        var selfOcclusionData = Objects.requireNonNull(skyContext.occlusionData()[selfChunkIdx]);
        // Optimization based on heightmap to skip expensive computation with all neighbors

        if (isGuaranteedFullyLit(selfOcclusionData.occlusionMap(), sectionY)) {
            return PreparedLight.FullyLit.INSTANCE;
        }

        var context = skyContext.context();
        @Nullable Palette[] blockPalettes = new Palette[27];
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        fillChunk(queue, blockPalettes, null, selfOcclusionData, sectionY, maxY, context.originChunk());

        for (var neighbor : Neighbors.values()) {
            int idx = chunkIdx(neighbor);
            var occlusionData = skyContext.occlusionData()[idx];
            if (occlusionData == null) {
                // Treat unloaded/unknown chunks as all 0 (no light comes from them)
                continue;
            }
            fillChunk(queue, blockPalettes, neighbor, occlusionData, sectionY, maxY, context.neighbors().get(neighbor));
        }
        for (int i = 0; i < blockPalettes.length; i++) {
            if (blockPalettes[i] == null) {
                // We treat all unknown/invalid chunks/sections as completely opaque.
                blockPalettes[i] = STONE;
            }
        }
        return new PreparedLight.ComputeQueue(queue, blockPalettes);
    }

    private static void fill(@Nullable Palette[] p, int relSectionX, int relSectionZ, int relSectionY, @Nullable SectionSnapshot snapshot) {
        if (snapshot == null) return;
        p[LightCompute.sectionIdx3x3((relSectionX + 1) << 4, (relSectionY + 1) << 4, (relSectionZ + 1) << 4)] = snapshot.blockPalette();
    }

    private static void fillChunk(IntArrayFIFOQueue queue, @Nullable Palette[] blockPalettes, @Nullable Neighbors neighbor, OcclusionData occlusionData, int sectionY, int maxY, CalculationContext.@Nullable ChunkContext context) {
        final int sectionBottomMinY = (sectionY - 1) * 16;
        final int sectionTopMaxY = (sectionY + 1) * 16 + 15;
        int relSectionX = neighbor == null ? 0 : neighbor.x();
        int relSectionZ = neighbor == null ? 0 : neighbor.z();
        int topmostY = Math.min(sectionTopMaxY, maxY);
        var heightmap = occlusionData.occlusionMap();
        if (context != null) {
            fill(blockPalettes, relSectionX, relSectionZ, -1, context.lower());
            fill(blockPalettes, relSectionX, relSectionZ, 0, context.middle());
            fill(blockPalettes, relSectionX, relSectionZ, 1, context.upper());
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int height = heightmap[z << 4 | x];
                int endY = Math.max(height, sectionBottomMinY);
                for (int y = topmostY; y >= endY; y--) {
                    int thisSectionY = y / 16;
                    int relSectionY = thisSectionY - sectionY;

                    int sectionPos = (relSectionY + 1) << 4 | (relSectionZ + 1) << 2 | (relSectionX + 1);
                    int sectionIdx = LightCompute.sectionIdx3x3((relSectionX + 1) << 4, (relSectionY + 1) << 4, (relSectionZ + 1) << 4);
                    final int prefix = sectionPos << 21 | sectionIdx << 16;

                    final int lightEmission = 15;
                    final int index = x | (z << 4) | ((y % 16) << 8);
                    queue.enqueue(prefix | (lightEmission << 12) | index);
                }
            }
        }
    }

    private SkyContext prepareContext(CalculationContext context) {
        OcclusionData[] occlusionData = new OcclusionData[9];
        occlusionData[chunkIdx(null)] = chunkData.occlusionData();
        for (var value : context.neighbors().values()) {
            fill(occlusionData, value.chunkData(), value.neighbor());
        }
        return new SkyContext(occlusionData, context);
    }

    private static void fill(OcclusionData[] occlusionData, ChunkData chunk, @Nullable Neighbors neighbors) {
        int idx = chunkIdx(neighbors);
        occlusionData[idx] = chunk.occlusionData();
    }

    private static int chunkIdx(@Nullable Neighbors neighbors) {
        int x = neighbors == null ? 0 : neighbors.x();
        int z = neighbors == null ? 0 : neighbors.z();
        return ((z + 1) * 3) + (x + 1);
    }

    private record SkyContext(@Nullable OcclusionData[] occlusionData, CalculationContext context) {
    }

    void relightSyncExternal(CalculationContext context) {
        relightSync(context);
    }

    byte[] get() {
        return light.data();
    }

    private static class UpdatableContent<T> {
        private int version = 0;
        private T data;

        UpdatableContent(T data) {
            this.data = data;
        }

        synchronized T data() {
            return data;
        }

        synchronized boolean update(T newData, int version) {
            if (version < this.version) return false;
            this.version = version;
            this.data = newData;
            return true;
        }
    }
}

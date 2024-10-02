package net.minestom.server.instance.chunksystem.impl;

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.*;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GeneratorImpl;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.utils.chunk.ChunkUtils;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static net.minestom.server.utils.chunk.ChunkUtils.getChunkIndex;

public class ChunkGenerator {
    private final Map<Long, List<GeneratorImpl.SectionModifierImpl>> generationForks = new ConcurrentHashMap<>();
    private final Instance instance;
    private final ChunkManagerImpl chunkManager;

    public ChunkGenerator(ChunkManagerImpl chunkManager) {
        this.instance = chunkManager.instance;
        this.chunkManager = chunkManager;
    }

    public @NotNull CompletableFuture<Chunk> generateChunk(@NotNull ChunkEntry entry, int chunkX, int chunkZ, boolean isInExecutor) {
        final var instance = this.instance;
        final var chunk = instance.getChunkSupplier().createChunk(instance, chunkX, chunkZ);
        Check.notNull(chunk, "Chunks supplied by a ChunkSupplier cannot be null.");
        final var generator = instance.generator();
        if (generator != null && chunk.shouldGenerate()) {
            var resultFuture = new CompletableFuture<Chunk>();
            Runnable task = () -> {
                GeneratorImpl.GenSection[] genSections = new GeneratorImpl.GenSection[chunk.getSections().size()];
                Arrays.setAll(genSections, i -> {
                    Section section = chunk.getSections().get(i);
                    return new GeneratorImpl.GenSection(section.blockPalette(), section.biomePalette());
                });
                var chunkUnit = GeneratorImpl.chunk(MinecraftServer.getBiomeRegistry(), genSections,
                        chunk.getChunkX(), chunk.getMinSection(), chunk.getChunkZ());
                try {
                    // Generate block/biome palette
                    generator.generate(chunkUnit);
                    // Apply nbt/handler
                    if (chunkUnit.modifier() instanceof GeneratorImpl.AreaModifierImpl chunkModifier) {
                        for (var section : chunkModifier.sections()) {
                            if (section.modifier() instanceof GeneratorImpl.SectionModifierImpl sectionModifier) {
                                applyGenerationData(chunk, sectionModifier);
                            }
                        }
                    }
                    // Register forks or apply locally
                    for (var fork : chunkUnit.forks()) {
                        var sections = ((GeneratorImpl.AreaModifierImpl) fork.modifier()).sections();
                        for (var section : sections) {
                            if (section.modifier() instanceof GeneratorImpl.SectionModifierImpl sectionModifier) {
                                if (sectionModifier.genSection().blocks().count() == 0)
                                    continue;
                                final Point start = section.absoluteStart();
                                final Chunk forkChunk = start.chunkX() == chunkX && start.chunkZ() == chunkZ ? chunk : instance.getChunkAt(start);
                                if (forkChunk != null) {
                                    applyFork(forkChunk, sectionModifier);
                                    // Update players
                                    forkChunk.invalidate();
                                    forkChunk.sendChunk();
                                } else {
                                    final long index = getChunkIndex(start);
                                    this.generationForks.compute(index, (i, sectionModifiers) -> {
                                        if (sectionModifiers == null) sectionModifiers = new ArrayList<>();
                                        sectionModifiers.add(sectionModifier);
                                        return sectionModifiers;
                                    });
                                }
                            }
                        }
                    }
                    // Apply awaiting forks
                    processFork(chunk);
                } catch (Throwable e) {
                    MinecraftServer.getExceptionManager().handleException(e);
                } finally {
                    // End generation
                    if (instance instanceof InstanceContainer instanceContainer) {
                        instanceContainer.refreshLastBlockChangeTime();
                    }
                    resultFuture.complete(chunk);
                }
            };
            if (isInExecutor) {
                task.run();
            } else {
                CompletableFuture.runAsync(task, chunkManager.getGenerationExecutor());
            }
            return resultFuture;
        } else {
            processFork(chunk);
            return CompletableFuture.completedFuture(chunk);
        }
    }

    private void processFork(Chunk chunk) {
        this.generationForks.compute(ChunkUtils.getChunkIndex(chunk), (aLong, sectionModifiers) -> {
            if (sectionModifiers != null) {
                for (var sectionModifier : sectionModifiers) {
                    applyFork(chunk, sectionModifier);
                }
            }
            return null;
        });
    }

    private void applyFork(@NotNull Chunk chunk, GeneratorImpl.SectionModifierImpl sectionModifier) {
        synchronized (chunk) {
            Section section = chunk.getSectionAt(sectionModifier.start().blockY());
            Palette currentBlocks = section.blockPalette();
            // -1 is necessary because forked units handle explicit changes by changing AIR 0 to 1
            sectionModifier.genSection().blocks().getAllPresent((x, y, z, value) -> currentBlocks.set(x, y, z, value - 1));
            applyGenerationData(chunk, sectionModifier);
        }
    }

    private void applyGenerationData(Chunk chunk, GeneratorImpl.SectionModifierImpl section) {
        var cache = section.genSection().specials();
        if (cache.isEmpty()) return;
        final int height = section.start().blockY();
        synchronized (chunk) {
            Int2ObjectMaps.fastForEach(cache, blockEntry -> {
                final int index = blockEntry.getIntKey();
                final Block block = blockEntry.getValue();
                final int x = ChunkUtils.blockIndexToChunkPositionX(index);
                final int y = ChunkUtils.blockIndexToChunkPositionY(index) + height;
                final int z = ChunkUtils.blockIndexToChunkPositionZ(index);
                chunk.setBlock(x, y, z, block);
            });
        }
    }
}

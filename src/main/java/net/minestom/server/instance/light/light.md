# Light Engine

The complexity of a líght engine in a multithreaded server is really getting out of hand. For the sake of my (and future
maintainers') sanity, this document is meant as an explanation/guide on how the light engine works on a high level. The
goal is to have a light engine that is

- "easily" verifiable for correctness (nothing is easy trust me I'm going insane)
- explains different concepts
- shows dangers and explicit antipatterns that mustn't be done in the future

## Requirements

1. Implement `getBlockLight(...)` and `getSkyLight(...)`

2. `get*Light(...)` must return consistent results while holding a lock; the underlying data may only be updated while
holding the write-lock.

3. `setBlock(...)` followed by `get*Light(...)` must always return up-to-date data. `get*Light(...)` must behave like a
single-threaded (immediate) light engine would behave, while holding the relevant chunk's lock.

4. `setBlock(...)` should have as little extra overhead as possible. Full block palette copies, HeightMap rebuilding etc.
should not be done.

### Implied Requirements

5. [Access to BlockPalette without the use of the chunk's read-lock.](#dangers-case-1)

## Immutable Data and Versioning

This is a common concept, but for the sake of completeness will be explained here. The idea is to have some data
associated with a version. When an update happens, the `nextVersion` counter is atomically incremented with
`getAndIncrement()`. When the update finishes, the finished data overwrites the current data only if the
`dataVersion>currentVersion`. This has the advantage that 2 expensive data computations can be done completely in
parallel, and the old computation would be discarded if the newer computation finishes first. Without the version the
old data could overwrite the newer data, which is invalid behavior.

This approach goes hand-in-hand with immutable data, because using this approach a full overwrite is required.

It *could* perhaps be done with patches instead of full overwrites, but that would require some sort of tracking/queuing
of updates if a newer computation finishes first. Computation with version N would always have to wait for computation
N-1 before applying. This concept may be worth exploring in the future.

## Approaches

### Snapshotting

The idea is simple. Take a full immutable snapshot of all data, send the snapshots to worker threads, worker compute
data and send updated data back to the chunk.

## Dangers/Problems

<h3 id="dangers-case-1">Case 1</h3>

```
Thread T1, T2
Chunk A(0,0) and B(0,1) are neighbors

T1: A.writeLock()
T2: B.writeLock()

T1: A.setBlock(...) # enqueues light recomputation/propagation
T2: B.setBlock(...) # enqueues light recomputation/propagation
<recomputation/propagation somewhere off-thread>
T1: A.get*Light(...) # has to wait for recomputation A to finish
T2: B.get*Light(...) # has to wait for recomputation B to finish

T1: A.writeUnlock()
T2: B.writeUnlock()
```

The issue here is deadlocks. If the recomputation tries to read-lock neighbors to access BlockPalette/HeightMap, then
this will always deadlock.

**This also means that read-locking is disallowed as an action during recomputation.**

With read-locking disallowed, this means that immutable snapshots of the BlockPalette/HeightMap must be accessible at
all times. These snapshots can take different forms, and lazily populated accessors are also allowed, but they must be
thread-safe and not dependent on the chunk's lock.

A valid solution is snapshotting the BlockPalette, so a valid immutable version can always be accessed. The HeightMap can be computed from BlockPalette. Effective memory 2x what it was. Also very allocation heavy. This solution is kind of ruled out because of requirement the requirement to keep setBlock(...) lightweight.

Another valid solution is to send all modifications to a queue managed by the light engine, which maintains a separate copy of the entire world. Assuming the queue offer is performant, setBlock(...) performance shouldn't be affected much. Though memory consumption is now 2x what it was before. The separate copy is managed by the light engine itself. The light engine can guarantee access to that copy without relying on the chunk's locks.

I've though about detecting the deadlock, and hijacking a deadlocked thread. Basically get*Light(...) would mark the thread to be locked as either read/write lock. Using this information, if a write-locked thread is locked, the write-lock can be hijacked to do some work, similar to Player.java, though I would not want to maintain such a monstrosity. Also, if the caller is only read-locked, the write-lock can't be obtained so what to do in this scenario? I don't know, hence I don't think this approach is possible/plausible)

NOTE: try-read-locking is allowed as a performance optimization. It can't cause a deadlock without involvement of more
locks inside the try-read-lock.


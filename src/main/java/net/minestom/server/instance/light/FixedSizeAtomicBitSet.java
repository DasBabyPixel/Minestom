package net.minestom.server.instance.light;

import java.util.concurrent.atomic.AtomicIntegerArray;

public class FixedSizeAtomicBitSet {
    private final AtomicIntegerArray array;

    public FixedSizeAtomicBitSet(int bitCount) {
        array = new AtomicIntegerArray((bitCount + 31) >>> 5); // bits to ints
    }

    public void set(int pos) {
        int idx = idx(pos);
        int bit = 1 << pos;
        while (true) {
            int val = array.get(idx);
            if ((val & bit) != 0) return; // Already set
            if (array.compareAndSet(idx, val, val | bit)) return;
        }
    }

    public void clear(int pos) {
        int idx = idx(pos);
        int bit = 1 << pos;
        while (true) {
            int val= array.get(idx);
            if ((val & bit) == 0) return; // Already cleared
            if (array.compareAndSet(idx, val, val ^ bit)) return;
        }
    }

    public boolean get(int pos) {
        int bit = 1 << pos;
        int idx = idx(pos);
        int val = array.get(idx) & bit;
        return val != 0;
    }

    private static int idx(int pos) {
        return (pos >>> 5);
    }
}

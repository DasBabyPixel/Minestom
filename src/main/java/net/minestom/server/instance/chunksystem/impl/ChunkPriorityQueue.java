package net.minestom.server.instance.chunksystem.impl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Custom linked blocking queue to remove a backlog of chunks that are being loaded
 */
public class ChunkPriorityQueue<T> extends AbstractQueue<T> implements BlockingQueue<T> {
    private final AtomicInteger count = new AtomicInteger();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private Node<T> head;
    private Node<T> tail;

    @Override
    public @NotNull Iterator<T> iterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int size() {
        return count.get();
    }

    @Override
    public void put(@NotNull T t) throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean offer(T t, long timeout, @NotNull TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull T take() throws InterruptedException {
        while (true) {
            var v = poll(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            if (v != null) return v;
        }
    }

    @Override
    public @Nullable T poll(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        var l = lock;
        l.lockInterruptibly();
        try {
            var first = head;
            notEmpty.await(timeout, unit);
        } finally {
            l.unlock();
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int drainTo(@NotNull Collection<? super T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int drainTo(@NotNull Collection<? super T> c, int maxElements) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean offer(T item) {
        throw new UnsupportedOperationException();
    }

    @Override
    public T poll() {
        throw new UnsupportedOperationException();
    }

    @Override
    public T peek() {
        throw new UnsupportedOperationException();
    }

    public class Node<E> {
        private E item;
        private Node<E> prev, next;

        private Node(E item) {
            this.item = item;
        }
    }
}

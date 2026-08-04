package Problems.S01_Classic.P01_BlockingQueue;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BlockingQueue<T> {
    private final int maxSize;
    private int currentSize = 0;
    private final Lock lock;
    private final Condition emptyCondition;
    private final Condition fullCondition;
    private List<T> queue;

    public BlockingQueue(int maxSize, boolean fair) {
        this.maxSize = maxSize;
        this.lock = new ReentrantLock(fair);
        this.emptyCondition = lock.newCondition();
        this.fullCondition = lock.newCondition();
        this.queue = new LinkedList<>();
    }

    /* ================= INSERT METHODS ================= */

    public void add(T item) throws IllegalStateException {
        if (item == null) {
            throw new NullPointerException("Element is null.");
        }
        lock.lock();
        try {
            if (currentSize == maxSize) {
                throw new IllegalStateException("Queue is already full.");
            }
            addElement(item);
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException("Element is null.");
        }
        lock.lock();
        try {
            if (currentSize == maxSize) {
                return false;
            }
            addElement(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Element is null.");
        }
        lock.lock();
        try {
            while (currentSize == maxSize) {
                fullCondition.await();
            }
            addElement(item);
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item, long timeout, TimeUnit timeUnit) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Element is null.");
        }
        long nanos = timeUnit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        lock.lock();
        try {
            while (currentSize == maxSize) {
                nanos = deadline - System.nanoTime();
                boolean awaitSuccess = fullCondition.await(nanos, TimeUnit.NANOSECONDS);
                if (awaitSuccess == false) {
                    return false;
                }
            }
            addElement(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void addElement(T item) {
        currentSize++;
        queue.add(item);
        emptyCondition.signal();
    }

    /* ================= REMOVE METHODS ================= */

    public T poll() {
        lock.lock();
        try {
            if (currentSize == 0) {
                return null;
            }
            return getFront();
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (currentSize == 0) {
                emptyCondition.await();
            }
            return getFront();
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit timeUnit) throws InterruptedException {
        long nanos = timeUnit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        lock.lock();
        try {
            while (currentSize == 0) {
                nanos = deadline - System.nanoTime();
                boolean awaitSuccess = emptyCondition.await(nanos, TimeUnit.NANOSECONDS);
                if (awaitSuccess == false) {
                    return null;
                }
            }
            return getFront();
        } finally {
            lock.unlock();
        }
    }

    private T getFront() {
        T item = queue.removeFirst();
        currentSize--;
        fullCondition.signal();
        return item;
    }

    /* ================= EXAMINE METHODS ================= */

    public T peek() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (currentSize == 0) {
                return null;
            }
            return queue.getFirst();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return currentSize;
        } finally {
            lock.unlock();
        }
    }

    public int remainingCapacity() {
        lock.lock();
        try {
            return maxSize - currentSize;
        } finally {
            lock.unlock();
        }
    }
}

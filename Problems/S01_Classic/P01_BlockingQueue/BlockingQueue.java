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
    
    private final List<T> queue;
    
    private final Lock lock;
    private final Condition emptyCondition;
    private final Condition fullCondition;

    public BlockingQueue(int maxSize, boolean fair) {
        this.queue = new LinkedList<>();
        this.lock = new ReentrantLock(fair);
        this.emptyCondition = lock.newCondition();
        this.fullCondition = lock.newCondition();
        this.maxSize = maxSize;
    }

    /* ================= INSERT METHODS ================= */

    public void add(T item) throws IllegalStateException {
        if(lock == null) {
            throw new NullPointerException("Element is null.");
        }
        lock.lock();
        try {
            if(currentSize == maxSize) {
                throw new IllegalStateException("The size of the queue is full.");
            }
            queue.add(item);
            currentSize++;
            emptyCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item) {
        if(lock == null) {
            throw new NullPointerException("Element is null.");
        }    
        lock.lock();
        try {
            if(currentSize == maxSize) {
                return false;
            }
            currentSize++;
            queue.add(item);
            emptyCondition.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException();
        }
        lock.lockInterruptibly();
        try {
            while(currentSize == maxSize) {
                fullCondition.await();
            }
            currentSize++;
            queue.add(item);
            emptyCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item, long timeout, TimeUnit timeUnit) throws InterruptedException {
        long nanos = timeUnit.toNanos(timeout);
        final long deadline = System.nanoTime() + nanos;
        
        lock.lockInterruptibly();
        try {
            while(currentSize == maxSize) {
                nanos = deadline - System.nanoTime();
                boolean awaitSuccess = fullCondition.await(nanos, TimeUnit.NANOSECONDS);
                if(awaitSuccess == false) {
                    return false;
                }
            }
            queue.add(item);
            currentSize++;
            emptyCondition.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }


    /* ================= REMOVE METHODS ================= */

    // private void remove(T item) {
    //     // TBD
    // }

    public T poll() {
        lock.lock();
        try {
            if(currentSize == 0) {
                return null;
            }
            T item = queue.removeFirst();
            currentSize--;
            fullCondition.signalAll();
            return item;
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while(currentSize == 0) {
                emptyCondition.await();
            }
            T item = queue.removeFirst();
            currentSize--;
            fullCondition.signalAll();
            return item;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit timeUnit) throws InterruptedException {
        long nanos = timeUnit.toNanos(timeout);
        final long deadline = System.nanoTime() + nanos;
        lock.lockInterruptibly();
        try {
            while(currentSize == 0) {
                nanos = deadline - System.nanoTime();
                boolean awaitSuccess = emptyCondition.await(nanos, TimeUnit.NANOSECONDS);
                if(!awaitSuccess) {
                    return null;
                }
            }

            T item = queue.removeFirst();
            currentSize--;
            fullCondition.signalAll();
            return item;
        } finally {
            lock.unlock();
        }
    }
    
    /* ================= EXAMINE METHODS ================= */

    public T peek() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if(currentSize == 0) {
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

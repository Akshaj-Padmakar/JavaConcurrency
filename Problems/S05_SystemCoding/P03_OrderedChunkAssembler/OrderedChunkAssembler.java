package Problems.S05_SystemCoding.P03_OrderedChunkAssembler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class OrderedChunkAssembler {
    private final int windowSize;
    private long currentId = 0;

    private final Map<Long, byte[]> buffer = new HashMap<>();

    private final Lock lock = new ReentrantLock();
    private final Condition putCondition = lock.newCondition();
    private final Condition takeCondition = lock.newCondition();

    private boolean finished = false;
    private long lastSequence = 0;
    private Throwable failure = null;

    public OrderedChunkAssembler(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("window size should be > 0.");
        }
        this.windowSize = windowSize;
    }

    public void put(long sequence, byte[] chunk) throws InterruptedException {
        if (sequence < 0 || chunk == null) {
            throw new IllegalArgumentException("Corrupted data.");
        }
        lock.lock();
        try {
            if (finished && sequence > lastSequence) {
                throw new IllegalArgumentException("Corrupted data. Sequence number invalid !");
            }

            while (currentId + windowSize - 1 < sequence && failure == null) {
                putCondition.await();
            }

            if (failure != null) {
                throw new IllegalStateException("Data transmission for some sequence failed. Aborting.");
            }

            if (sequence < currentId) {
                // This is already consumed.
                // Maybe transmitted again.
                return;
            }

            buffer.put(sequence, chunk);
            takeCondition.signalAll(); // Only single thread calls take, so signal() should also be sufficient.
        } finally {
            lock.unlock();
        }
    }

    public byte[] take() throws InterruptedException {
        lock.lock();
        try {
            while (buffer.get(currentId) == null
                    && failure == null
                    && !(finished && currentId > lastSequence)) {
                takeCondition.await();
            }
            if (buffer.get(currentId) != null) {
                byte[] ans = buffer.remove(currentId++);
                putCondition.signalAll();
                return ans;
            }
            if (failure != null) {
                throw new IllegalStateException("Data transmission for some sequence failed. Aborting.");
            }
            return null; // o.w currentId > lastSequence, all data is consumed.
        } finally {
            lock.unlock();
        }
    }

    public void finish(long lastSequence) {
        // How does take() i.e. consumer thread, will know that
        // it have fetched all the required data ?
        // sent by producer. // O-based
        lock.lock();
        try {
            finished = true;
            this.lastSequence = lastSequence;
            takeCondition.signalAll();
            putCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void fail(Throwable cause) {
        // What if some data could never be fetched ?
        // fail the assembler.
        // sent by producer.
        lock.lock();
        try {
            if (failure == null) {
                failure = cause;
            }
            takeCondition.signalAll();
            putCondition.signalAll();
        } finally {
            lock.unlock();
        }

    }
}

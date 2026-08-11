package Problems.S01_Classic.P02_ReadWriteLock;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
 * Conditions for Lock:
 * as many readers as possible can acquire the lock.
 * only single writer can acquire the lock.
 * writers are preferred over readers, so if a writer thread comes, no more readers would be allowed.
          The already ongoing readers would be complete to complete the read.
 * lock is reentrant from writer to reader
 * lock is reentrant from reader to writer(if there is only 1 reader)
 */

public class ReadWriteLock {

    private final Lock lock;
    private final Condition readWaitCondition;
    private final Condition writeWaitCondition;

    private final Map<Thread, Integer> readingThread;
    private Thread writer;
    private int writerReentranceCnt = 0;
    private int writeWaitCnt = 0;

    public ReadWriteLock() {
        this(false);
    }

    public ReadWriteLock(boolean fair) {
        this.lock = new ReentrantLock(fair);
        this.readWaitCondition = this.lock.newCondition();
        this.writeWaitCondition = this.lock.newCondition();

        readingThread = new HashMap<>();
    }

    public void readLock() throws InterruptedException {
        Thread thread = Thread.currentThread();
        lock.lock();
        try {
            while (!grantReadAccess(thread)) {
                readWaitCondition.await();
            }
            Integer cnt = readingThread.get(thread);
            if (cnt == null) {
                cnt = 0;
            }
            readingThread.put(thread, ++cnt);
        } finally {
            lock.unlock();
        }
    }

    public void readUnlock() {
        Thread thread = Thread.currentThread();
        lock.lock();
        try {
            Integer cnt = readingThread.get(thread);
            if (cnt == null) {
                throw new IllegalMonitorStateException("This thread doesn't hold the read-lock, cannot unlock !");
            }
            if (--cnt == 0) {
                readingThread.remove(thread);
                writeWaitCondition.signalAll();
                // All writers need to wake up, lets say a single reader wants to upgrade when an earlier writer is waiting. => upgrade the reader.
                return;
            }
            readingThread.put(thread, cnt);
        } finally {
            lock.unlock();
        }
    }

    public void writeLock() throws InterruptedException {
        Thread thread = Thread.currentThread();
        lock.lock();
        try {
            writeWaitCnt++;
            try {
                while (!grantWriteAccess(thread)) {
                    writeWaitCondition.await();
                }
            } finally {
                writeWaitCnt--; // if writer thread is interrupted, decrement wait cnt.
                if (writeWaitCnt == 0) {
                    readWaitCondition.signalAll(); // if write thread is interrupted, wake up readers.
                }
            }
            writerReentranceCnt++;
            writer = thread;
        } finally {
            lock.unlock();
        }
    }

    public void writeUnlock() {
        Thread thread = Thread.currentThread();
        lock.lock();
        try {
            if (writer != thread) {
                throw new IllegalMonitorStateException("This thread doesn't hold the write-lock, cannot unlock !");
            }
            writerReentranceCnt--;
            if (writerReentranceCnt == 0) {
                writer = null;
                writeWaitCondition.signal();
                readWaitCondition.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean grantReadAccess(Thread thread) {
        if (writer != null) {
            return writer == thread;
        }

        if (readingThread.get(thread) != null) {
            return true;
        } else {
            return writeWaitCnt == 0;
        }
    }

    private boolean grantWriteAccess(Thread thread) {
        if (writer != null) {
            return writer == thread;
        }
        if (readingThread.isEmpty()) {
            return true;
        } else if (readingThread.size() == 1) {
            return readingThread.get(thread) != null;
        }
        return false;
    }

}

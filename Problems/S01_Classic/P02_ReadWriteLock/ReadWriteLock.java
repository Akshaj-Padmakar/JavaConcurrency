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
    * writers are preffered over readers, so if a writer thread comes, no more readers would allowed. The already onging readers would be complete to complete the read.
    * lock is reentrant from writer to reader
    * lock is reentrant from reader to writer(if there is only 1 reader)
*/

public class ReadWriteLock {
    private final Map<Thread, Integer> readingThreads;
    private Thread writer = null;
    private int writerRequest = 0;
    private int writerCnt = 0;

    private Lock lock = new ReentrantLock();
    private Condition readWaitCondition = lock.newCondition();
    private Condition writeWaitCondition = lock.newCondition();

    public ReadWriteLock() {
        this.readingThreads = new HashMap<>();
    }

    public void readLock() throws InterruptedException {
        lock.lock();
        try {
            Thread thread = Thread.currentThread();
            while (!grantReadAccess(thread)) {
                readWaitCondition.await();
            }
            Integer cnt = readingThreads.get(thread);
            if (cnt == null) {
                cnt = 0;
            }
            cnt++;
            readingThreads.put(thread, cnt);
        } finally {
            lock.unlock();
        }
    }

    public void readUnlock() {
        lock.lock();
        try {
            Thread thread = Thread.currentThread();
            Integer cnt = readingThreads.get(thread);
            if (cnt == null) {
                throw new IllegalMonitorStateException("This thread doesnot hold the read-lock, cannot unlock !");
            }
            cnt--;
            if (cnt == 0) {
                readingThreads.remove(thread);
                writeWaitCondition.signal();
            } else {
                readingThreads.put(thread, cnt);
            }
        } finally {
            lock.unlock();
        }
    }

    public void writeLock() throws InterruptedException {
        lock.lock();
        try {
            Thread thread = Thread.currentThread();
            writerRequest++;
            try {
                while (!grantWriteAccess(thread)) {
                    writeWaitCondition.await();
                }
            } finally {
                writerRequest--;
            }
            writer = thread;
            writerCnt++;
        } finally {
            lock.unlock();
        }
    }

    public void writeUnlock() {
        lock.lock();
        try {
            Thread thread = Thread.currentThread();
            if (writer != thread) {
                throw new IllegalMonitorStateException("This Thread doesnot hold the write-lock, cannot unlock !");
            }
            writerCnt--;
            if (writerCnt == 0) {
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
        if (readingThreads.get(thread) != null) {
            return true;
        } else {
            return writerRequest == 0;
        }
    }

    private boolean grantWriteAccess(Thread thread) {
        if (writer != null) {
            return writer == thread;
        }

        if (readingThreads.size() == 0) {
            return true;
        } else if (readingThreads.size() == 1) {
            return readingThreads.containsKey(thread);
        }
        return false;
    }

}

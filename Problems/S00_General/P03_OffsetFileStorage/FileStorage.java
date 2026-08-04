package Problems.S00_General.P03_OffsetFileStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
Discussion:
- Is this storage backed by memory or an actual file on disk ? 
- offset is byte offsets or charter index ?


=> in-memory storage 
=> ASCII characters -> 1 byte is 1 character.
=> offset is 0 based.
 */

public class FileStorage {
    private final List<Character> file = new ArrayList<>();
    private final List<ReadWriteLock> segmentLocks = new ArrayList<>();
    private final int BLOCK_SIZE = 1024;

    private final Lock lock = new ReentrantLock();

    public void write(int offset, String data) {
        if (data == null || offset < 0) {
            throw new IllegalArgumentException("Parameters passed are wrong.");
        }
        if (data.length() == 0) {
            return;
        }

        int leftBlock = offset / BLOCK_SIZE, rightBlock = (offset + data.length() - 1) / BLOCK_SIZE;
        int lastAcquired = leftBlock - 1;
        try {
            lock.lock();
            try {
                while (segmentLocks.size() <= rightBlock) {
                    segmentLocks.add(new ReadWriteLock());
                }
            } finally {
                lock.unlock();
            }
            for (int i = leftBlock; i <= rightBlock; i++) {
                segmentLocks.get(i).writeLock();
                lastAcquired = i;
            }

            // [offset, offset + 1,.... offset + data.size() - 1]
            int end = offset + data.length();
            lock.lock();
            try {
                while (file.size() < end) {
                    file.add(' ');
                }
            } finally {
                lock.unlock();
            }

            for (int i = 0; i < data.length(); i++) {
                file.set(i + offset, data.charAt(i));
            }
        } catch (InterruptedException ex) {
            ex.printStackTrace();
            Thread.currentThread().interrupt();
        } finally {
            for (int i = lastAcquired; i >= leftBlock; i--) {
                segmentLocks.get(i).writeUnlock();
            }
        }
    }

    public String read(int offset, int length) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("Parameters passed are wrong.");
        }
        if (length == 0) {
            return "";
        }

        int leftBlock = offset / BLOCK_SIZE, rightBlock = (offset + length - 1) / BLOCK_SIZE;
        int lastAcquired = leftBlock - 1;
        try {
            lock.lock();
            try {
                while (segmentLocks.size() <= rightBlock) {
                    segmentLocks.add(new ReadWriteLock());
                }
            } finally {
                lock.unlock();
            }
            for (int i = leftBlock; i <= rightBlock; i++) {
                segmentLocks.get(i).readLock();
                lastAcquired = i;
            }

            StringBuilder ans = new StringBuilder();
            for (int i = 0; i < length; i++) {
                if (offset + i >= file.size()) {
                    break;
                }
                ans.append(file.get(offset + i));
            }
            return ans.toString();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
            Thread.currentThread().interrupt();
            return null;
        } finally {
            for (int i = lastAcquired; i >= leftBlock; i--) {
                segmentLocks.get(i).readUnlock();
            }
        }
    }

    private class ReadWriteLock {
        /*
         * Conditions for Lock:
         * As many readers can acquire the lock.
         * Only single writer can acquire the lock.
         * Writers are preffered over readers
         * (since write request anyway will make the read request data stale if they are
         * preffered).
         * Lock is reentrant from reader to reader.
         * Lock is reentrant from writer to reader.
         * Lock is reentrant from reader to writer(if there is only 1 reader)
         */

        private final Map<Thread, Integer> readingThreads;
        private int writerRequest = 0;
        private int writerCnt = 0; // for Reentrant record
        private Thread writer = null;

        private final Lock lock = new ReentrantLock();
        private final Condition readWaitCondition = lock.newCondition();
        private final Condition writeWaitCondition = lock.newCondition();

        public ReadWriteLock() {
            readingThreads = new HashMap<>();
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
                    throw new IllegalMonitorStateException("This thread doesn't hold the readLock, cannot Unlock!");
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
                    writerCnt++;
                    writer = thread;
                } finally {
                    // I don't really understand why writerRequest is decremented here and not
                    // inside the next finally block...
                    writerRequest--;
                }
            } finally {
                lock.unlock();
            }
        }

        public void writeUnlock() {
            lock.lock();
            try {
                Thread thread = Thread.currentThread();
                if (writer != thread) {
                    throw new IllegalMonitorStateException("This thread doesn't hold the write Lock, cannot Unlock!");
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
            if (writer != null) { // already a writer exist
                return writer == thread;
            }

            if (readingThreads.get(thread) != null) {
                return true;
            } else if (writerRequest > 0) {
                return false;
            }

            return true;
        }

        private boolean grantWriteAccess(Thread thread) {
            if (writer != null) { // already writer exist
                return writer == thread;
            }

            if (readingThreads.size() == 0) {
                return true;
            } else if (readingThreads.size() == 1) { // only 1 thread reading
                return readingThreads.containsKey(thread);
            }

            return false;
        }

    }

}

package Problems.S00_General.P03_OffsetFileStorage;

import java.util.ArrayList;
import java.util.Arrays;
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
=> unwritten gaps are filled with ' ' (space).
 */

public class FS2 {
    private static final int BLOCK_SIZE = 1024;

    // Each block is its own fixed-size char[]. Different blocks are independent
    // memory, so a grow/write on one block cannot corrupt reads on another. A
    // block's contents are only ever touched while holding that block's segment
    // lock, which also supplies the happens-before ordering between operations.
    private final List<char[]> blocks = new ArrayList<>();
    private final List<ReadWriteLock> segmentLocks = new ArrayList<>();
    private int size = 0;

    // Guards the structure of `blocks`/`segmentLocks` and the `size` field.
    // Never held while touching block contents.
    private final Lock lock = new ReentrantLock();

    public void write(int offset, String data) {
        if (data == null || offset < 0) {
            throw new IllegalArgumentException("Parameters passed are wrong.");
        }
        if (data.isEmpty()) {
            return;
        }

        int end = offset + data.length();
        int leftBlock = offset / BLOCK_SIZE;
        int rightBlock = (end - 1) / BLOCK_SIZE;

        // Snapshot the block arrays and their locks under the global lock so we
        // never call blocks.get(i)/segmentLocks.get(i) while another thread is
        // structurally modifying those lists.
        List<char[]> myBlocks;
        List<ReadWriteLock> myLocks;
        lock.lock();
        try {
            ensureCapacity(rightBlock);
            if (size < end) {
                size = end;
            }
            myBlocks = new ArrayList<>(blocks.subList(leftBlock, rightBlock + 1));
            myLocks = new ArrayList<>(segmentLocks.subList(leftBlock, rightBlock + 1));
        } finally {
            lock.unlock();
        }

        int acquired = 0;
        try {
            // Locks acquired in increasing block order -> no deadlock.
            for (; acquired < myLocks.size(); acquired++) {
                myLocks.get(acquired).writeLock();
            }
            for (int i = 0; i < data.length(); i++) {
                int idx = offset + i;
                myBlocks.get(idx / BLOCK_SIZE - leftBlock)[idx % BLOCK_SIZE] = data.charAt(i);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring write locks", ex);
        } finally {
            for (int i = acquired - 1; i >= 0; i--) {
                myLocks.get(i).writeUnlock();
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

        int start;
        int effLen;
        int leftBlock;
        List<char[]> myBlocks;
        List<ReadWriteLock> myLocks;
        lock.lock();
        try {
            if (offset >= size) {
                return "";
            }
            int end = Math.min(offset + length, size); // exclusive, trimmed to current size
            start = offset;
            effLen = end - start;
            leftBlock = start / BLOCK_SIZE;
            int rightBlock = (end - 1) / BLOCK_SIZE;
            myBlocks = new ArrayList<>(blocks.subList(leftBlock, rightBlock + 1));
            myLocks = new ArrayList<>(segmentLocks.subList(leftBlock, rightBlock + 1));
        } finally {
            lock.unlock();
        }

        int acquired = 0;
        try {
            for (; acquired < myLocks.size(); acquired++) {
                myLocks.get(acquired).readLock();
            }
            StringBuilder ans = new StringBuilder(effLen);
            for (int i = 0; i < effLen; i++) {
                int idx = start + i;
                ans.append(myBlocks.get(idx / BLOCK_SIZE - leftBlock)[idx % BLOCK_SIZE]);
            }
            return ans.toString();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring read locks", ex);
        } finally {
            for (int i = acquired - 1; i >= 0; i--) {
                myLocks.get(i).readUnlock();
            }
        }
    }

    // Must be called while holding `lock`. Grows storage so that `block` is a
    // valid index. New blocks are pre-filled with ' ', which also defines the
    // fill for sparse gaps between existing data and a later offset.
    private void ensureCapacity(int block) {
        while (blocks.size() <= block) {
            char[] arr = new char[BLOCK_SIZE];
            Arrays.fill(arr, ' ');
            blocks.add(arr);
            segmentLocks.add(new ReadWriteLock());
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
                    writeWaitCondition.signalAll();
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
                    // Once granted (or on failure) this thread is no longer a
                    // pending writer, so drop its request from the writer-preference
                    // count here rather than at unlock time.
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
                    writeWaitCondition.signalAll();
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

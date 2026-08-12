package Problems.S00_General.P03_OffsetFileStorage;

import java.util.*;
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

public class FileStorage {

    // here each block has its own fixed char[].
    // Different blocks read/write, don't corrupt reads to another.

    private static final int BLOCK_SIZE = 1024;
    private final List<char[]> blocks = new ArrayList<>();
    private final List<ReadWriteLock> segmentLocks = new ArrayList<>();
    private int size = 0;

    private final Lock lock = new ReentrantLock();
    // Used when changing shared variables like size, segmentLocks, blocks size

    public void write(int offset, String data) {
        if (data == null || offset < 0) {
            throw new IllegalArgumentException("Parameters to write are incorrect !");
        }
        if (data.isEmpty()) {
            return;
        }

        int end = data.length() + offset;

        int leftBlock = offset / BLOCK_SIZE;
        int rightBlock = (end - 1) / BLOCK_SIZE;


        List<char[]> myBlocks;
        List<ReadWriteLock> myLocks;
        lock.lock();
        try {
            ensureCapacity(rightBlock);
            myBlocks = new ArrayList<>(blocks.subList(leftBlock, rightBlock + 1));
            myLocks = new ArrayList<>(segmentLocks.subList(leftBlock, rightBlock + 1));
        } finally {
            lock.unlock();
        }

        int acquired = 0;
        try {
            for (; acquired < myLocks.size(); acquired++) {
                myLocks.get(acquired).lockWrite();
            }

            for (int i = 0; i < data.length(); i++) {
                int idx = offset + i;
                myBlocks.get(idx / BLOCK_SIZE - leftBlock)[idx % BLOCK_SIZE] = data.charAt(i);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring lock");
        } finally {
            for (int i = acquired - 1; i >= 0; i--) {
                myLocks.get(i).unlockWrite();
            }
        }

        lock.lock();
        try {
            if (size < end) { // make size visible once write is successful.
                size = end;
            }
        } finally {
            lock.unlock();
        }
    }


    public String read(int offset, int length) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("Parameters to read are incorrect.");
        }
        if (length == 0) {
            return "";
        }
        int start;
        int end;
        int effLen;
        int leftBlock;
        int rightBlock;

        List<char[]> myBlocks;
        List<ReadWriteLock> myLocks;

        lock.lock();
        try {
            if (offset >= size) {
                return "";
            }
            start = offset;
            end = Math.min(offset + length, size);
            effLen = end - start;

            leftBlock = start / BLOCK_SIZE;
            rightBlock = (end - 1) / BLOCK_SIZE;

            myBlocks = new ArrayList<>(blocks.subList(leftBlock, rightBlock + 1));
            myLocks = new ArrayList<>(segmentLocks.subList(leftBlock, rightBlock + 1));
        } finally {
            lock.unlock();
        }

        int acquired = 0;
        try {
            for (; acquired < myLocks.size(); acquired++) {
                myLocks.get(acquired).lockRead();
            }
            StringBuilder ans = new StringBuilder();
            for (int i = 0; i < effLen; i++) {
                int idx = offset + i;
                ans.append(myBlocks.get(idx / BLOCK_SIZE - leftBlock)[idx % BLOCK_SIZE]);
            }
            return ans.toString();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring lock");
        } finally {
            for (int i = acquired - 1; i >= 0; i--) {
                myLocks.get(i).unlockRead();
            }
        }
    }

    private void ensureCapacity(int rightBlock) {
        while (blocks.size() <= rightBlock) {
            char[] arr = new char[BLOCK_SIZE];
            Arrays.fill(arr, ' ');
            blocks.add(arr);
            segmentLocks.add(new ReadWriteLock());
        }
    }

    private static class ReadWriteLock {
        private final Lock lock;
        private final Condition readWaitCondition;
        private final Condition writeWaitCondition;

        private Thread writer = null;
        private int writerRequest = 0;
        private int writerCnt = 0;
        private final Map<Thread, Integer> readingThread;

        public ReadWriteLock() {
            this(false);
        }

        public ReadWriteLock(boolean fair) {
            this.lock = new ReentrantLock(fair);
            this.readWaitCondition = this.lock.newCondition();
            this.writeWaitCondition = this.lock.newCondition();
            this.readingThread = new HashMap<>();
        }

        public void lockRead() throws InterruptedException {
            Thread thread = Thread.currentThread();
            lock.lock();
            try {
                while (!allowRead(thread)) {
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

        public void unlockRead() {
            Thread thread = Thread.currentThread();
            lock.lock();
            try {
                if (!readingThread.containsKey(thread)) {
                    throw new IllegalMonitorStateException("This thread doesn't hold the read lock !");
                }
                Integer cnt = readingThread.get(thread);
                if (--cnt == 0) {
                    readingThread.remove(thread);
                    writeWaitCondition.signal();
                } else {
                    readingThread.put(thread, cnt);
                }
            } finally {
                lock.unlock();
            }
        }

        public void lockWrite() throws InterruptedException {
            Thread thread = Thread.currentThread();
            lock.lock();
            try {
                writerRequest++;
                try {
                    while (!allowWrite(thread)) {
                        writeWaitCondition.await();
                    }
                } catch (InterruptedException ex) {
                    if (--writerRequest == 0) {
                        readWaitCondition.signalAll();
                    }
                    throw ex;
                }
                writer = thread;
                writerCnt++;
                writerRequest--;
            } finally {
                lock.unlock();
            }
        }

        public void unlockWrite() {
            Thread thread = Thread.currentThread();
            lock.lock();
            try {
                if (writer != thread) {
                    throw new IllegalMonitorStateException("This thread doesn't hold the write Lock !");
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

        private boolean allowRead(Thread thread) {
            if (writer != null) {
                return writer == thread;
            }

            if (readingThread.containsKey(thread)) {
                return true;
            } else {
                return writerRequest == 0;
            }
        }

        private boolean allowWrite(Thread thread) {
            if (writer != null) {
                return thread == writer;
            }

            if (readingThread.isEmpty()) {
                return true;
            } else if (readingThread.size() == 1) {
                return readingThread.containsKey(thread);
            } else {
                return false;
            }
        }
    }

}

package Problems.S00_General.P04_ConcurrentFileCopy;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentFileCopy {

    private final int readerThreadCnt;
    private final int writerThreadCnt;
    private final Fs fs;
    private final int maxCapacity;
    private final BlockingQueue<Chunk> queue;

    private int srcFd;
    private int dstFd;

    private Exception firstError;

    private static final int BLOCK_SIZE = 1024;

    private final List<Thread> allThreads = new ArrayList<>();

    public ConcurrentFileCopy(int readerThreadCnt, int writerThreadCnt, Fs fs, int maxCapacity) {
        this.readerThreadCnt = readerThreadCnt;
        this.writerThreadCnt = writerThreadCnt;
        this.fs = fs;
        this.maxCapacity = maxCapacity;
        this.queue = new BlockingQueue<>(maxCapacity);
    }


    public void copy(String dst, String src) throws IOException, InterruptedException {
        this.srcFd = fs.open(src);
        try {
            this.dstFd = fs.open(dst);
            try {
                runPipeline();
            } finally {
                fs.close(dstFd);
            }
        } finally {
            closeQuietly(srcFd);
        }
    }

    private void runPipeline() {
        List<Thread> readerThreads = new ArrayList<>();
        List<Thread> writerThreads = new ArrayList<>();
        for (int i = 0; i < readerThreadCnt; i++) {
            readerThreads.add(new Thread(new ReaderRunnable(i), "Read-Thread-" + i));
        }

        for (int i = 0; i < writerThreadCnt; i++) {
            writerThreads.add(new Thread(new WriterRunnable(i), "Writer-Thread-" + i));
        }
        allThreads.addAll(readerThreads);
        allThreads.addAll(writerThreads);

        for (Thread t : readerThreads) {
            t.start();
        }

        for (Thread t : writerThreads) {
            t.start();
        }

        for (Thread t : allThreads) {
            try {
                t.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                recordError(new IOException("Copy was interrupted."));
                abort();
            }
        }
    }

    private synchronized void recordError(IOException ex) {
        if (firstError == null) {
            firstError = ex;
        }
    }

    private void abort() {
        for (Thread t : allThreads) {
            if (t != Thread.currentThread()) {
                t.interrupt();
            }
        }
    }

    private class ReaderRunnable implements Runnable {
        private final int id;

        public ReaderRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            try {
                int block = this.id;
                while (readBlock(block * BLOCK_SIZE)) {
                    block += readerThreadCnt;
                }
            } catch (IOException ex) {
                recordError(ex);
                abort();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean readBlock(int base) throws IOException, InterruptedException {
            Chunk chunk = new Chunk();
            int read = fs.pread(srcFd, chunk.getBuf(), base);
            if (read == 0) {
                if (id == 0) {
                    putPoisonPill();
                }
                return false;
            }
            chunk.fill(read, base);
            queue.put(chunk);
            return true;
        }

        private void putPoisonPill() throws InterruptedException {
            for (int i = 0; i < writerThreadCnt; i++) {
                queue.put(Chunk.POISON_PILL);
            }
        }
    }

    private class WriterRunnable implements Runnable {
        private final int id;

        public WriterRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    Chunk chunk = queue.take();
                    if (chunk == Chunk.POISON_PILL) {
                        return;
                    }
                    fs.pwrite(dstFd, chunk.getBuf(), chunk.getSize(), chunk.getOffset());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                recordError(ex);
                abort();
            }
        }
    }

    private void closeQuietly(int fd) {
        try {
            fs.close(fd);
        } catch (IOException ex) {
            // Ignore
        }
    }


    public interface Fs {
        int open(String name) throws IOException;

        long size(int fd) throws IOException;                       // <-- the one I added

        int pread(int fd, byte[] buf, long offset) throws IOException;

        void pwrite(int fd, byte[] buf, int len, long offset) throws IOException;

        void close(int fd) throws IOException;
    }

    private static class Chunk {
        private byte[] buf;
        private int size;
        private int offset;
        private static final Chunk POISON_PILL = new Chunk(0);

        public Chunk() {
            this.buf = new byte[BLOCK_SIZE];
        }

        private Chunk(int sz) {
            this.buf = new byte[sz];
        }

        public byte[] getBuf() {
            return this.buf;
        }

        public int getSize() {
            return this.size;
        }

        public int getOffset() {
            return this.offset;
        }

        public void fill(int size, int offset) {
            this.size = size;
            this.offset = offset;
            this.buf = Arrays.copyOf(this.buf, size);
        }
    }

    private class BlockingQueue<E> {
        private final int maxCapacity;
        private final Queue<E> queue;
        private final Lock lock;
        private final Condition fullCondition;
        private final Condition emptyCondition;

        public BlockingQueue(int maxCapacity) {
            this.maxCapacity = maxCapacity;
            this.queue = new LinkedList<>();
            this.lock = new ReentrantLock();
            this.fullCondition = this.lock.newCondition();
            this.emptyCondition = this.lock.newCondition();
        }

        public void put(E item) throws InterruptedException {
            lock.lock();
            try {
                while (this.queue.size() == maxCapacity) {
                    fullCondition.await();
                }
                this.queue.add(item);
                emptyCondition.signal();
            } finally {
                lock.unlock();
            }
        }

        public E take() throws InterruptedException {
            lock.lock();
            try {
                while (this.queue.isEmpty()) {
                    emptyCondition.await();
                }
                fullCondition.signal();
                return this.queue.poll();
            } finally {
                lock.unlock();
            }
        }
    }

}

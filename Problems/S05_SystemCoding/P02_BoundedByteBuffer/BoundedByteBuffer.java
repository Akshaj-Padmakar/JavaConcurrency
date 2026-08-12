package Problems.S05_SystemCoding.P02_BoundedByteBuffer;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedByteBuffer {
    private final byte[] buf;
    private final int capacity;

    private int readIndex = 0;
    private int cnt = 0;
    private boolean closed = false;

    private final Lock lock = new ReentrantLock();
    private final Condition emptyCondition = lock.newCondition();
    private final Condition fullCondition = lock.newCondition();

    public BoundedByteBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0.");
        }

        this.capacity = capacity;
        this.buf = new byte[capacity];
    }

    public void write(byte[] src, int offset, int len) throws InterruptedException {
        // write from src to buf ->
        // since len and offset are defined by producer,
        // this is blocking till it writes the complete data.
        checkBounds(src, offset, len);
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("buffer is closed while writing");
            }

            int written = 0;
            while (written < len) {
                while (cnt == capacity && !closed) {
                    fullCondition.await();
                }

                if (closed) {
                    throw new IllegalStateException("buffer is closed while writing");
                }

                int chunk = Math.min(capacity - cnt, len - written);
                int writeIndex = (readIndex + cnt) % capacity;

                int toEnd = Math.min(chunk, capacity - writeIndex);
                // cyclic queue, so write till the end if chunk exceeds.

                System.arraycopy(src, offset + written, buf, writeIndex, toEnd);

                if (toEnd < chunk) {
                    System.arraycopy(src, offset + written + toEnd, buf, 0, chunk - toEnd);
                }

                cnt += chunk;
                written += chunk;
                emptyCondition.signalAll();
                // signalAll() ? because a lot of read thread can be woken up... (write writes a lot of data in buf).
            }
        } finally {
            lock.unlock();
        }
    }

    public int read(byte[] dst, int offset, int len) throws InterruptedException {
        // read from buf to dst -> best effort to read len,
        // since consumers don't know about the length possible, they will do a hit and trial
        // -1 if queue is closed, o.w return best effort to read len bytes.
        checkBounds(dst, offset, len);

        lock.lock();
        try {
            if (len == 0) {
                return 0;
            }
            while (cnt == 0 && !closed) {
                emptyCondition.await();
            }
            if (cnt == 0) { // Thread awake due to closed
                return -1;
            }

            int chunk = Math.min(cnt, len);
            int toEnd = Math.min(chunk, capacity - readIndex);
            // cyclic queue, so read till the end if chunk exceeds.

            System.arraycopy(buf, readIndex, dst, offset, toEnd);
            if (toEnd < chunk) {
                System.arraycopy(buf, 0, dst, offset + toEnd, chunk - toEnd);
            }
            readIndex += chunk;
            readIndex %= capacity;
            cnt -= chunk;
            fullCondition.signalAll(); // signalAll() ? lot of write threads can be woken up...
            return chunk;
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            this.closed = true;
            fullCondition.signalAll();
            emptyCondition.signalAll();
            // close all waiting threads.
        } finally {
            lock.unlock();
        }
    }

    public int size() { // How many bits are written ?
        lock.lock();
        try {
            return this.cnt;
        } finally {
            lock.unlock();
        }
    }

    private void checkBounds(byte[] arr, int offset, int len) {
        if (arr == null) {
            throw new NullPointerException("array must not be null");
        }

        if (offset < 0 || len < 0 || offset + len - 1 >= arr.length) {
            throw new IndexOutOfBoundsException("invalid index/offsets for array.");
        }
    }
}

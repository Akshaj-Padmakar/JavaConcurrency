package Old;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BlockingQueue<T> {
    private int maxSize;
    private List<T> list;
    private ReentrantLock lock;
    private Condition notEmpty;
    private Condition notFull;

    public BlockingQueue(int maxSize, boolean fair) {
        this.maxSize = maxSize;
        this.list = new LinkedList<T>();
        lock = new ReentrantLock(fair);
        notEmpty = lock.newCondition();
        notFull = lock.newCondition();
    }

    public boolean offer(T item) throws InterruptedException {
        lock.lockInterruptibly();
        try{
            while (list.size() == maxSize) {
                notFull.await();
            }
            list.add(item);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T poll() throws InterruptedException { 
        lock.lockInterruptibly();
        try{
            while(this.list.size() == 0){
                notEmpty.await();
            }
            T item = list.removeFirst();
            notFull.signal();
            return item;
        }finally{
            lock.unlock();
        }
    }

    public boolean offer(T item, long timeout, TimeUnit timeUnit) throws InterruptedException, NullPointerException{
        if(item == null){
            throw new NullPointerException();
        }
        lock.lockInterruptibly();
        try{
            while(this.list.size() == this.maxSize){
                boolean awaitSuccess = notFull.await(timeout, timeUnit);
                if(!awaitSuccess){
                    return false;
                }
            }
            this.list.add(item);
            notEmpty.signal();
            return true;
        }finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit timeUnit) throws InterruptedException {
        lock.lockInterruptibly();
        try{
            while(this.list.size() == 0){
                boolean awaitSuccess = this.notEmpty.await(timeout, timeUnit);
                if(!awaitSuccess){
                    return null;
                }
            }
            T item = this.list.removeLast();
            this.notFull.signal();
            
            return item;
        } finally {
            lock.unlock();
        }
    }

    public int size() throws InterruptedException {
        lock.lockInterruptibly();
        try{
            return this.list.size();
        } finally {
            lock.unlock();
        }
    }
}
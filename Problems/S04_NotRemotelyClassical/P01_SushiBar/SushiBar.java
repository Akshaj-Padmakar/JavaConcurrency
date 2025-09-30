package Problems.S04_NotRemotelyClassical.P01_SushiBar;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SushiBar {
    private final int N;
    private final int CAPACITY = 5;
    public SushiBar(int N) {
        this.N = N;
    }

    private final Lock lock = new ReentrantLock();
    private final Queue<Node> waitingQueue = new LinkedList<>();
    private int inside = 0;
    private boolean full = false;
    private final Random rnd = new Random();

    private class Node {
        private final int id;
        private Condition condition;
        
        public Node(int id) {
            this.id = id;
        }

    }

    public class CustomerRunnable implements Runnable {
        private int id;
        public CustomerRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();

            Node node = new Node(id);
            node.condition = lock.newCondition();

            waitingQueue.add(node);
            try {
                while(full) {
                    node.condition.await();
                }
                waitingQueue.poll();
                inside++;
                if(inside == CAPACITY) {
                    full = true;
                }
                System.out.println("Customer-" + this.id + " has entered the sushi-bar. Customer inside count = " + inside);
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

            try {
                Thread.sleep(rnd.nextInt(500));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            lock.lock();
            try {
                inside--;
                if(full && inside == 0) {
                    full = false;
                    List<Node> customerSignal = peekFirstK(waitingQueue, CAPACITY);

                    for(Node cNode : customerSignal) {
                        cNode.condition.signal();
                    }
                }
            System.out.println("Customer-" + this.id + " has exited the sushi-bar. Customer inside count = " + inside);
            } finally {
                lock.unlock();
            }
        }
    }

    private List<Node> peekFirstK(Queue<Node> queue, int k) {
        Iterator<Node> it = queue.iterator();
        int i = 0;
        List<Node> ans = new ArrayList<>();
        while(i < k && it.hasNext()) {
            ans.add(it.next());
        }
        return ans;
    }

    public void solve() throws InterruptedException {
        List<Thread> customerThreads = new ArrayList<>();
        for(int i = 0; i < this.N; i++) {
            customerThreads.add(new Thread(new CustomerRunnable(i), "Customer-Runnable-" + i));
        }

        for(Thread t : customerThreads) {
            t.start();
        }
        for(Thread t : customerThreads) {
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int N = 14;

        new SushiBar(N).solve();
    }
}

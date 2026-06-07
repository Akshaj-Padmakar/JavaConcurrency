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
    private final int N; // Number of customers

    public SushiBar(int N) {
        this.N = N;
    }

    private final Lock lock = new ReentrantLock();
    private boolean partying = false; // Bar full ?

    private int insideCnt = 0;
    private final int MAX_CAPACITY = 5;

    private class Node {
        private int id;
        private Condition condition;

        public Node(int id) {
            this.id = id;
            condition = lock.newCondition();
        }

        public int getId() {
            return id;
        }

        public Condition getCondition() {
            return condition;
        }
    }

    Queue<Node> waitingCustomers = new LinkedList<>();

    private Random rnd = new Random();

    private class CustomerRunnable implements Runnable {
        private Node node;

        public CustomerRunnable(int id) {
            this.node = new Node(id);
        }

        private int dinningTime() {
            return 300 + rnd.nextInt(300);
        }

        @Override
        public void run() {
            enter();

            dineIn();

            exit();
        }

        private void enter() {
            lock.lock();
            waitingCustomers.add(this.node);
            try {
                while (partying || waitingCustomers.peek() != node || insideCnt == MAX_CAPACITY) {
                    this.node.getCondition().await();
                }
                waitingCustomers.poll();
                insideCnt++;
                if (insideCnt == MAX_CAPACITY) {
                    partying = true;
                }
                logEntry();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void dineIn() {
            logDinning();
            try {
                Thread.sleep(dinningTime());
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }

        private void exit() {
            lock.lock();
            insideCnt--;
            try {
                if (partying && insideCnt == 0) {
                    partying = false;
                    List<Node> nxtCustomers = peekFirstK(waitingCustomers, MAX_CAPACITY);
                    for (Node customer : nxtCustomers) {
                        customer.getCondition().signal();
                    }
                }
                logExit();
            } finally {
                lock.unlock();
            }
        }

        private void logEntry() {
            System.out.println("Customer-" + this.node.getId() + " has entered the Sushi bar.");
            if (insideCnt == MAX_CAPACITY) {
                System.out.println("PARTY STARTED !!!!!!");
            }
        }

        private void logDinning() {
            System.out.println("Customer-" + this.node.getId() + " is now dinning........");
        }

        private void logExit() {
            System.out.println("Customer-" + this.node.getId() + " is now exiting.");
            if (insideCnt == 0 && partying) {
                System.out.println("PARTY ENDED :/");
            }
        }
    }

    private List<Node> peekFirstK(Queue<Node> queue, int k) {
        List<Node> ret = new ArrayList<>();
        Iterator<Node> it = queue.iterator();
        int i = 0;
        while (i < k && it.hasNext()) {
            ret.add(it.next());
            i++;
        }
        return ret;
    }

    public void solve() throws InterruptedException {
        List<Thread> customerThreads = new ArrayList<>();
        for (int i = 0; i < this.N; i++) {
            customerThreads.add(new Thread(new CustomerRunnable(i), "Customer-Runnable-" + i));
        }

        for (Thread t : customerThreads) {
            t.start();
        }
        for (Thread t : customerThreads) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int N = 14;

        new SushiBar(N).solve();
    }
}

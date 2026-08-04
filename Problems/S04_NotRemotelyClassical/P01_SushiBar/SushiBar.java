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
    private final int N; // Number of Customers

    public SushiBar(int N) {
        this.N = N;
    }

    private final Lock lock = new ReentrantLock();

    private final Queue<Node> waitingList = new LinkedList<>();

    private boolean partying = false;
    private int insideCnt = 0;

    private final int MAX_CAPACITY = 5;

    private final Random rnd = new Random();

    private class Node {
        private final int id;
        private final Condition condition;

        private Node(int id) {
            this.id = id;
            this.condition = lock.newCondition();
        }

        private int getId() {
            return this.id;
        }

        private Condition getCondition() {
            return this.condition;
        }
    }

    private class CustomerRunnable implements Runnable {
        private final Node node;

        public CustomerRunnable(int id) {
            this.node = new Node(id);
        }

        @Override
        public void run() {
            if (!enter()) {
                return;
            }

            dineIn();

            exit();
        }

        private boolean enter() {
            lock.lock();
            try {
                waitingList.add(this.node);
                while (partying || waitingList.peek() != this.node || insideCnt == MAX_CAPACITY) {
                    this.node.getCondition().await();
                }
                waitingList.poll();
                insideCnt++;
                if (insideCnt == MAX_CAPACITY) {
                    partying = true;
                }
                logEntry();
                return true;
            } catch (InterruptedException ex) {
                // Interruption on await
                waitingList.remove(this.node);
                if (!waitingList.isEmpty()) {
                    waitingList.peek().getCondition().signal();
                }
                ex.printStackTrace();
                Thread.currentThread().interrupt();
                return false;
            } finally {
                lock.unlock();
            }
        }

        private void dineIn() {
            try {
                Thread.sleep(dinningTime());
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }

        private void exit() {
            lock.lock();
            try {
                insideCnt--;
                logExit();
                if (insideCnt == 0 && partying) {
                    partying = false;
                    List<Node> nxtCustomers = peekFirstK(waitingList, MAX_CAPACITY);
                    for (Node node : nxtCustomers) {
                        node.getCondition().signal();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private int dinningTime() {
            return 200 + rnd.nextInt(100);
        }

        private List<Node> peekFirstK(Queue<Node> queue, int k) {
            List<Node> list = new ArrayList<>();
            if (queue == null) {
                return list;
            }
            int cnt = 0;
            for (Node node : queue) {
                if (cnt >= k) {
                    break;
                }
                cnt++;
                list.add(node);
            }
            return list;
        }

        private void logEntry() {
            System.out.println("Customer-" + this.node.getId() + " has entered the Sushi bar.");
            if (insideCnt == MAX_CAPACITY) {
                System.out.println("PARTY STARTED !!!!!!");
            }
        }

        private void logExit() {
            System.out.println("Customer-" + this.node.getId() + " is now exiting.");
            if (insideCnt == 0 && partying) {
                System.out.println("PARTY ENDED :/");
            }
        }
    }

    private void solve() throws InterruptedException {
        List<Thread> customerThreads = new ArrayList<>();
        for (int i = 0; i < this.N; i++) {
            customerThreads.add(new Thread(new CustomerRunnable(i), "Customer-Thread-" + i));
        }

        for (Thread t : customerThreads) {
            t.start();
        }

        for (Thread t : customerThreads) {
            t.join();
        }
    }

    public static void main(String args[]) throws InterruptedException {
        new SushiBar(25).solve();
    }
}

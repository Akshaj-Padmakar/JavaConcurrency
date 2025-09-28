package SenateBusProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SenateBus {
    private final int BUS_CAPACITY = 50;
    private Random rnd = new Random();
    private Lock lock = new ReentrantLock();
    private Condition peopleWaiting = lock.newCondition();
    private Condition peopleReached = lock.newCondition();
    private Condition busStartCondition = lock.newCondition();
    private Condition busFree = lock.newCondition();

    private int waiting = 0;
    private int onboard = 0;
    private boolean busArrived = false;
    private boolean reached = false;
    public class PeopleRunnable implements Runnable {
        private final int id;
        public PeopleRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(1000));
                if(this.id > 70) {
                    Thread.sleep(3000);
                }
                enterBusStop();
                boardBus();
                exitBus();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void enterBusStop() {
            lock.lock();
            try {
                waiting++;
                System.out.println("People-" + this.id + " has reached the BusStop.");
            } finally {
                lock.unlock();
            }
        }

        private void boardBus() {
            lock.lock();
            try {
                
                while(!busArrived){
                    peopleWaiting.await();
                }
                while(onboard >= BUS_CAPACITY) {
                    peopleWaiting.await();
                }
                waiting--;
                onboard++;
                System.out.println("People-" + this.id + " has boarded the bus. onBoard = " + onboard + " waitingPeople = " + waiting);
                if(waiting == 0 || onboard == BUS_CAPACITY) {
                    busStartCondition.signal();
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
        
        private void exitBus() {
            lock.lock();
            try {
                while(!reached) {
                    peopleReached.await();
                }
                System.out.println("People-" + this.id + " has reached it's destination, off-boarding now.");
                onboard--;
                if(onboard == 0) {
                    busFree.signal();
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

    }

    public class BusRunnable implements Runnable {
        public BusRunnable() {

        }

        @Override
        public void run() {
            try {
                for(int i = 0; i < 3; i++) { // 2 bus will come to the bus stop only.
                    Thread.sleep(1100);
                    reachedBusStop(i);
                    leavingBusStop();
                    Thread.sleep(1100);
                    reachedDestination();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void reachedBusStop(int i) {
            lock.lock();
            try {
                busArrived = true;
                System.out.println("[itr-" + i + "], Bus has reached Bus-Stop. onBoard = " + onboard + " waitingPeople = " + waiting);
                peopleWaiting.signalAll();
                while(!(onboard == BUS_CAPACITY || waiting == 0)) {
                    busStartCondition.await();
                }
                busArrived = false;
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        public void leavingBusStop() {
            lock.lock();
            try {
                System.out.println("Bus has started its journey. onBoard = " + onboard + " waitingPeople = " + waiting);
            } finally {
                lock.unlock();
            }
        }

        public void reachedDestination() {
            lock.lock();
            try {
                reached = true;
                peopleReached.signalAll();
                while(onboard != 0) {
                    busFree.await();
                }
                reached = false;
                System.out.println("Bus has reached its destination. onBoard = " + onboard + " waitingPeople = " + waiting);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }
    public void solve(int people) throws InterruptedException {
        List<Thread> peopleThreads = new ArrayList<>();
        Thread busThread = new Thread(new BusRunnable(), "Bus-Thread");
        for(int i = 0; i < people; i++) {
            peopleThreads.add(new Thread(new PeopleRunnable(i), "People-Thread-" + i));
        }
        busThread.start();
        for(Thread t : peopleThreads) {
            t.start();
        }
        busThread.join();
        for(Thread t : peopleThreads) {
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int people = 150;
        new SenateBus().solve(people);
    }
}

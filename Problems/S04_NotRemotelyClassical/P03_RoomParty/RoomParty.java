package Problems.S04_NotRemotelyClassical.P03_RoomParty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
This is the perfect algorithm for the problem(maybe not FIFO fair), simulation could be better....
 */

public class RoomParty {
    private final int N; // Number of students.

    public RoomParty(int N) {
        this.N = N;
    }

    private final Lock lock = new ReentrantLock();
    private final Condition studentEntryCondition = lock.newCondition();
    private final Condition deanEntryCondition = lock.newCondition();
    private final Condition deanExitCondition = lock.newCondition();

    private int studentCnt = 0;
    private static final int PARTY_STUDENT_CNT = 50;
    private boolean raid = false;

    private class Dean implements Runnable {
        @Override
        public void run() {
            for (int i = 0; i < 3; i++) { // 3 visits.
                enter();

                stay();

                leave();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }

        private void enter() {
            lock.lock();
            try {
                while (studentCnt > 0 && studentCnt <= PARTY_STUDENT_CNT) {
                    deanEntryCondition.await();
                }
                logEntry();
                raid = true;
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void stay() {
            logStay();
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }

        private void leave() {
            lock.lock();
            try {
                while (studentCnt > 0) {
                    deanExitCondition.await();
                }
                logExit();
                raid = false;
                studentEntryCondition.signalAll();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void logEntry() {
            System.out.println("Dean has enterd the room.");
            if (studentCnt == 0) {
                System.out.println("Since no students are present, Dean is searching the room !");
            } else if (studentCnt > PARTY_STUDENT_CNT) {
                System.out.println(PARTY_STUDENT_CNT + " or more students present, Dean is busting the party !");
            }
        }

        private void logStay() {
            System.out.println("Dean inside......");
        }

        private void logExit() {
            System.out.println("Dean is done searching the room and raid is done !");
        }
    }

    private class Student implements Runnable {
        private final int id;

        public Student(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            enter();

            stay();

            exit();
        }

        private void enter() {
            lock.lock();
            try {
                while (raid) {
                    studentEntryCondition.await();
                }
                logEntry();
                studentCnt++;
                if (studentCnt > PARTY_STUDENT_CNT) {
                    deanEntryCondition.signal();
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void stay() {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }

        private void exit() {
            lock.lock();
            try {
                logExit();
                studentCnt--;
                if (studentCnt == 0) {
                    deanEntryCondition.signal();
                    deanExitCondition.signal();
                }
            } finally {
                lock.unlock();
            }
        }

        private void logEntry() {
            System.out.println("Student-" + this.id + " has entered. studentCnt = " + studentCnt
                    + " ongoingRaid = " + raid);
        }

        private void logExit() {
            System.out.println("Student-" + this.id + " has exited. studentCnt = " + studentCnt
                    + " ongoingRaid = " + raid);
        }
    }

    private void solve() throws InterruptedException {
        List<Thread> studentThreads = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            studentThreads.add(new Thread(new Student(i), "Student-Thread-" + i));
        }
        Thread deanThread = new Thread(new Dean(), "Dean-Thread");
        deanThread.start();
        for (Thread t : studentThreads) {
            t.start();
        }

        for (Thread t : studentThreads) {
            t.join();
        }
        deanThread.join();
    }

    public static void main(String[] args) throws InterruptedException {
        int student = 400;

        new RoomParty(student).solve();
    }
}

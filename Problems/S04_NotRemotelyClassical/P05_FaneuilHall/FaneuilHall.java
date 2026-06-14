package Problems.S04_NotRemotelyClassical.P05_FaneuilHall;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class FaneuilHall {

    private final int spectatorsCnt;
    private final int immigrantsCnt;

    public FaneuilHall(int spectatorsCnt, int immigrantsCnt) {
        this.spectatorsCnt = spectatorsCnt;
        this.immigrantsCnt = immigrantsCnt;
    }

    private Lock lock = new ReentrantLock();
    private Condition judgeConfirmCondition = lock.newCondition();
    private Condition immigrantsGetCertificateCondition = lock.newCondition();
    private Condition immigrantsLeaveCondition = lock.newCondition();

    private boolean judgePresent = false;
    private boolean judgeConfirmed = false;
    private int spectators = 0;
    private int enteredImmigrants = 0;
    private int checkInImmigrants = 0;

    private class Judge implements Runnable {
        @Override
        public void run() {
            enter();

            confirm();

            leave();
        }

        private void enter() {
            lock.lock();
            try {
                judgePresent = true;
                logJudgeEntered();
            } finally {
                lock.unlock();
            }
        }

        private void confirm() {
            lock.lock();
            try {
                while (checkInImmigrants != enteredImmigrants) {
                    judgeConfirmCondition.await();
                }
                logJudgeConfirming();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }

            try {
                Thread.sleep(400); // simulation time
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }

            lock.lock();
            try {
                judgeConfirmed = true;
                immigrantsGetCertificateCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void leave() {
            lock.lock();
            try {
                logLeave();
                judgePresent = false;
                immigrantsLeaveCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void logJudgeEntered() {
            System.out.println("Judge has entered the Faneuil Hall !!");
        }

        private void logJudgeConfirming() {
            System.out.println("Judge has started confirming...");
        }

        private void logLeave() {
            System.out.println("Judge has left the Faneuil Hall !!");
        }
    }

    private class Spectator implements Runnable {
        private int id;

        public Spectator(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            try {
                if (judgePresent) {
                    logCannotEnter();
                    return;
                }
                enter();
            } finally {
                lock.unlock();
            }

            spectate();

            leave();
        }

        private void enter() {
            spectators++;
            logEnter();
        }

        private void spectate() {
            logSpectating();
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }

        private void leave() {
            lock.lock();
            try {
                spectators--;
                logLeave();
            } finally {
                lock.unlock();
            }
        }

        private void logCannotEnter() {
            System.out.println("Spectator-" + this.id + " cannot enter, since, judge is already inside the building");
        }

        private void logEnter() {
            System.out.println("Spectator-" + this.id + " has entered the Hall !");
        }

        private void logSpectating() {
            System.out.println("Spectator-" + this.id + " is specatating.");
        }

        private void logLeave() {
            System.out.println("Spectator-" + this.id + " is leaving.");
        }
    }

    private class Immigrant implements Runnable {
        private int id;

        public Immigrant(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            try {
                if (judgePresent) {
                    logCannotEnter();
                    return;
                }
                enter();
            } finally {
                lock.unlock();
            }

            checkIn();

            sitDown();

            swear();

            getCertificate();

            leave();
        }

        private void enter() {
            enteredImmigrants++;
            logEnter();
        }

        private void checkIn() {
            lock.lock();
            try {
                logCheckIn();
                checkInImmigrants++;
                if (checkInImmigrants == enteredImmigrants) {
                    logAllCheckedIn();
                    judgeConfirmCondition.signal();
                }
            } finally {
                lock.unlock();
            }
        }

        private void sitDown() {
            logSitDown();
        }

        private void swear() {
            logSwear();
        }

        private void getCertificate() {
            lock.lock();
            try {
                while (!judgeConfirmed) {
                    immigrantsGetCertificateCondition.await();
                }
                logGetCertificate();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void leave() {
            lock.lock();
            try {
                while (judgePresent) {
                    immigrantsLeaveCondition.await();
                }
                logLeave();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void logCannotEnter() {
            System.out.println("Immigrant-" + this.id + " cannot enter, since, judge is already inside the building");
        }

        private void logEnter() {
            System.out.println("Immigrant-" + this.id + " has entered the Hall !");
        }

        private void logCheckIn() {
            System.out.println("Immigrant-" + this.id + " has checked-In the Hall !");
        }

        private void logAllCheckedIn() {
            System.out.println("All immigrants have checked in, signalling Judge.");
        }

        private void logSitDown() {
            System.out.println("Immigrant-" + this.id + " has sit down.");
        }

        private void logSwear() {
            System.out.println("Immigrant-" + this.id + " has swearing now.");
        }

        private void logGetCertificate() {
            System.out.println("Immigrant-" + this.id + " has got their certificate.");
        }

        private void logLeave() {
            System.out.println("Immigrant-" + this.id + " is now leaving.");
        }
    }

    private void solve() throws InterruptedException {
        List<Thread> spectatorThreads = new ArrayList<>();
        List<Thread> immigrantThreads = new ArrayList<>();
        Thread judgeThread = new Thread(new Judge(), "Judge-Thread");

        for (int i = 0; i < this.spectatorsCnt; i++) {
            spectatorThreads.add(new Thread(new Spectator(i), "Spectator-Thread-" + i));
        }

        for (int i = 0; i < this.immigrantsCnt; i++) {
            immigrantThreads.add(new Thread(new Immigrant(i), "Immigrant-Thread-" + i));
        }

        for (Thread t : spectatorThreads) {
            t.start();
        }
        for (Thread t : immigrantThreads) {
            t.start();
        }
        judgeThread.start();

        for (Thread t : spectatorThreads) {
            t.join();
        }
        for (Thread t : immigrantThreads) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int spectators = 15;
        int immigrants = 10;

        new FaneuilHall(spectators, immigrants).solve();
    }
}

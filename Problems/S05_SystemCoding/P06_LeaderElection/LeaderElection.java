package Problems.S05_SystemCoding.P06_LeaderElection;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class LeaderElection {

    public static class Node {
        private static final int NO_VOTE = -1;

        private final int id;
        private final Cluster cluster;
        private final long maxTimeoutMillis;
        private final long minTimeoutMillis;
        private final long heartbeatMillis;

        private final Lock lock = new ReentrantLock();
        private final Condition deadlineChanged = lock.newCondition();

        private Role role = Role.FOLLOWER;
        private long currentTerm = 0;
        private int votedFor = NO_VOTE;

        private long electionDeadlineNanos = 0;

        private boolean running = false;

        private volatile Thread thread;

        public Node(int id, Cluster cluster, long minTimeoutMillis, long maxTimeoutMillis, long heartbeatMillis) {
            this.id = id;
            this.cluster = cluster;
            this.minTimeoutMillis = minTimeoutMillis;
            this.maxTimeoutMillis = maxTimeoutMillis;
            this.heartbeatMillis = heartbeatMillis;
        }

        public int getId() {
            return this.id;
        }

        public State snapshot() {
            // Atomic Snapshot -> role and term read together and returned to client.
            lock.lock();
            try {
                return new State(this.role, this.currentTerm);
            } finally {
                lock.unlock();
            }
        }

        public void start() {
            // can be called to re-start a node when it comes back up
            // or at the start of system(not necessary tho)
            lock.lock();
            try {
                if (this.running) { // already started.
                    return;
                }
                this.running = true;
                this.role = Role.FOLLOWER;
                this.votedFor = NO_VOTE;
                resetDeadline();
            } finally {
                lock.unlock();
            }

            Thread t = new Thread(this::run, "node-" + id);
            this.thread = t;
            t.setDaemon(true);
            t.start();
        }

        public void stop() {
            Thread t;
            lock.lock();
            try {
                if (!running) return;
                running = false;
                deadlineChanged.signalAll();
                t = thread;
            } finally {
                lock.unlock();
            }

            if (t != null) { // Simulating crash...
                t.interrupt();
                try {
                    t.join(1_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // RPC Methods ===> Other node's thread invoke this through Cluster.
        public VoteResponse requestVote(long term, int candidateId) {
            lock.lock();
            try {
                if (!running) {
                    return null;
                }
                if (term > currentTerm) {
                    stepDown(term);
                }
                if (term < currentTerm) {
                    return new VoteResponse(currentTerm, false);
                }

                if (votedFor == NO_VOTE || votedFor == candidateId) {
                    votedFor = candidateId;
                    resetDeadline();

                    deadlineChanged.signalAll();
                    return new VoteResponse(currentTerm, true);
                }
                return new VoteResponse(currentTerm, false);
            } finally {
                lock.unlock();
            }
        }

        public AppendResponse appendEntries(long term, int leaderId) {
            lock.lock();
            try {
                if (!running) {
                    return null;
                }
                if (currentTerm > term) return new AppendResponse(currentTerm, false);
                if (currentTerm < term) stepDownLocked(term);

                role = Role.FOLLOWER;
                resetDeadline();
                deadlineChanged.signalAll();
                return new AppendResponse(currentTerm, true);
            } finally {
                lock.unlock();
            }
        }

        // node's loop------------->
        public void run() {
            while (true) {
                Role current;
                lock.lock();
                try {
                    if (!running) return;
                    current = role;
                } finally {
                    lock.unlock();
                }

                if (current == Role.LEADER) {
                    sendHeartBeats();
                    sleepMillis(heartbeatMillis);
                } else if (awaitElectionDeadline()) {
                    startElection();
                }
            }
        }

        private boolean awaitElectionDeadline() {
            lock.lock();
            try {
                while (running && role != Role.LEADER) {
                    long remaining = electionDeadlineNanos - System.nanoTime();
                    if (remaining <= 0) return true;
                    deadlineChanged.awaitNanos(remaining);
                }
                return false;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                lock.unlock();
            }
        }

        private void startElection() {
            long electionTerm;
            List<Integer> peers;
            lock.lock();
            try {
                if (!running || role == Role.LEADER) {
                    return;
                }
                currentTerm++;
                role = Role.CANDIDATE;
                votedFor = id;
                resetDeadline();
                electionTerm = currentTerm;
                peers = cluster.peers(id);
            } finally {
                lock.unlock();
            }

            int votes = 1;
            int majority = cluster.size() / 2 + 1;
            for (int peer : peers) {
                VoteResponse response = cluster.requestVote(id, peer, electionTerm, id);
                if (response == null) {
                    continue; // dropped; crashed peer or severed link
                }
                if (response.getTerm() > electionTerm) {
                    stepDown(response.getTerm());
                    return;
                }

                if (response.isGranted() && ++votes >= majority) {
                    break;
                }
            }

            if (votes >= majority) {
                lock.lock();
                try {
                    if (running && role == Role.CANDIDATE && currentTerm == electionTerm) {
                        // Re-validate -> we dropped lock, so we could have been stepped Down.
                        role = Role.LEADER;
                    }
                } finally {
                    lock.unlock();
                }
            }
        }

        private void sendHeartBeats() {
            long term;
            List<Integer> peers;
            lock.lock();
            try {
                if (!running || role != Role.LEADER) {
                    return;
                }

                term = this.currentTerm;
                peers = this.cluster.peers(this.id);
            } finally {
                lock.unlock();
            }

            for (int peer : peers) {
                AppendResponse response = cluster.appendEntries(id, peer, term, id);
                if (response != null && response.getTerm() > term) {
                    stepDown(response.getTerm());
                    return;
                }
            }
        }

        private void stepDown(long newTerm) {
            lock.lock();
            try {
                if (newTerm > this.currentTerm) {
                    stepDownLocked(newTerm);
                }
            } finally {
                lock.unlock();
            }
        }

        private void stepDownLocked(long newTerm) {
            this.currentTerm = newTerm;
            this.role = Role.FOLLOWER;
            votedFor = NO_VOTE;
            resetDeadline();
            deadlineChanged.signalAll();
        }

        private void sleepMillis(long time) {
            try {
                Thread.sleep(time);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private void resetDeadline() {
            long timeout = ThreadLocalRandom.current().nextLong(minTimeoutMillis, maxTimeoutMillis + 1);
            this.electionDeadlineNanos = System.nanoTime() + timeout * 1_000_000;
        }
    }


    public interface Cluster {
        List<Integer> peers(int selfId);

        int size();

        VoteResponse requestVote(int from, int to, long term, int leaderId);

        AppendResponse appendEntries(int from, int to, long term, int leaderId);
    }

    public static class AppendResponse {
        private final long term;
        private final boolean success;

        public AppendResponse(long term, boolean success) {
            this.term = term;
            this.success = success;
        }

        public long getTerm() {
            return this.term;
        }

        public boolean isSuccess() {
            return this.success;
        }
    }

    public static class VoteResponse {
        private final long term;
        private final boolean granted;

        public VoteResponse(long term, boolean granted) {
            this.term = term;
            this.granted = granted;
        }

        public long getTerm() {
            return this.term;
        }

        public boolean isGranted() {
            return this.granted;
        }

    }

    public static class State {
        private final Role role;
        private final long currentTerm;

        public State(Role role, long currentTerm) {
            this.role = role;
            this.currentTerm = currentTerm;
        }

        public Role getRole() {
            return this.role;
        }

        public long getCurrentTerm() {
            return this.currentTerm;
        }
    }

    public enum Role {
        LEADER, FOLLOWER, CANDIDATE;
    }
}

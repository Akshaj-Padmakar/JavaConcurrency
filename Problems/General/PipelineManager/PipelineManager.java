package Problems.General.PipelineManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public class PipelineManager {
    public class Job {
        String jobId;
        public Job(String jobId) {
            this.jobId = jobId;
        }

        public void doJob() throws JobException, InterruptedException {
            // if(jobId.contains("4")) { // TEST EXCEPTION
            //     throw new JobException("Failure!!!! for JobId: " + jobId);
            // }
            Thread.sleep(1000);
            System.out.println("Job with jobId: " + jobId + " is completed.");
            
        }
    }

    public class JobException extends Exception {
        public JobException(String msg) { super(msg); }
    }   
    private int n = 5;
    private Map<Job, List<Job>> g = new HashMap<>();
    private Map<Job, Integer> indegree = new HashMap<>();
    private final ExecutorService threadPool = Executors.newFixedThreadPool(10);
    private List<Job> topSort = new ArrayList<>();
    private volatile boolean stop = false;
    private Map<Job, Semaphore> jobSemaphores = new HashMap<>();
    List<Job> jobs = new ArrayList<>();
    private final AtomicBoolean stoppedOnce = new AtomicBoolean(false);

    public class dfs implements Runnable {
        private Job node;
        public dfs(Job node) {
            this.node = node;
        }
        @Override
        public void run() {
            boolean success = false;
            try {
                jobSemaphores.get(node).acquire(indegree.get(node));
                if(stop) {
                    return;
                }
                node.doJob();
                success = true;
            } catch (InterruptedException e) {
                doStop();
                e.printStackTrace();
                return;
            } catch (JobException e) {
                doStop();
                e.printStackTrace();
                return;
            }
            if(success && !stop){
                for(Job child : g.get(node)) {
                    jobSemaphores.get(child).release(1);
                }
            }
        }
    }

    public void doStop() {
        stop = true;
        handleStop();
    }
    private void handleStop() {
        if (!stoppedOnce.compareAndSet(false, true)) return; // only first thread proceeds
        System.out.println("Stopping now !");
        threadPool.shutdownNow();
    }

    public void solve() throws InterruptedException {
        for(int i = 0; i < n; i++) {
            jobs.add(new Job("JobId-" + i));
            indegree.put(jobs.get(i), 0);
            jobSemaphores.put(jobs.get(i), new Semaphore(0));
             g.put(jobs.get(i), new ArrayList<>());
        }

        addDependency(jobs.get(0), jobs.get(1));
        addDependency(jobs.get(0), jobs.get(2));
        addDependency(jobs.get(0), jobs.get(3));
        addDependency(jobs.get(1), jobs.get(3));
        addDependency(jobs.get(1), jobs.get(4));
        addDependency(jobs.get(2), jobs.get(4));

        Queue<Job> queue = new LinkedList<>();
        for(Job job : jobs) {
            if(indegree.get(job) == 0) {
                // root node.
                queue.add(job);
            }
        }
        
        Map<Job, Integer> indegreeCopy = new HashMap<>(indegree);

        while(queue.size() != 0) {
            Job job = queue.poll();
            topSort.add(job);
            
            for(Job child : g.get(job)) {
                indegreeCopy.put(child, indegreeCopy.get(child) - 1);
                if(indegreeCopy.get(child) == 0) {
                    queue.add(child);
                }
            }
        }
        if(topSort.size() != n) {
            for(Job job: topSort) {
                System.out.println(job.jobId);
            }
            System.out.println("Jobs dependecy graph is cyclic!");
            return;
        }

        for(Job job : topSort) {
            threadPool.submit(new dfs(job));
        }
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            threadPool.shutdownNow();
            throw ie;
        }
    }

    public void addDependency(Job a, Job b) { // a -> b, b depends on a.
        g.get(a).add(b);
        indegree.put(b, indegree.get(b) + 1);
    }

    public static void main(String[] args) throws InterruptedException {
        new PipelineManager().solve();
    }
    
    
}

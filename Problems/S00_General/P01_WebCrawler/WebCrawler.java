package Problems.S00_General.P01_WebCrawler;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class WebCrawler {
    // Iterate over a directed graph, such that common nodes don't do their work twice.

    private final PageSource pageSource;
    private final int workerCnt;

    private final HashMap<Page, Boolean> visited;
    private final AtomicInteger activeTask;
    private final CountDownLatch completionLatch;
    private final ExecutorService threadPool;


    public WebCrawler(PageSource pageSource, int workerCnt) {
        if (workerCnt <= 0 || pageSource == null) {
            throw new IllegalArgumentException("Arguments are not valid !");
        }
        this.pageSource = pageSource;
        this.workerCnt = workerCnt;

        this.visited = new HashMap<>();
        this.activeTask = new AtomicInteger(0);
        this.completionLatch = new CountDownLatch(1);
        this.threadPool = Executors.newFixedThreadPool(workerCnt);
    }

    public void crawl(Page seed) {
        if (seed == null) {
            return;
        }

        if (alreadyVisited(seed)) {
            return;
        }
        activeTask.incrementAndGet();
        threadPool.submit(new dfs(seed, new Page(-1, "-1")));
        try {
            completionLatch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        threadPool.shutdown();
    }

    private class dfs implements Runnable {
        private final Page node;
        private final Page par;

        public dfs(Page node, Page par) {
            this.node = node;
            this.par = par;
        }

        @Override
        public void run() {
            try {
                node.fetch();

                System.out.println("Visited node: " + this.node.getPageId() +
                        " and previousNode: " + this.par.getPageId() + " and using Thread: " +
                        Thread.currentThread().getName() + " url =" + this.node.getUrl());


                List<Page> pages = pageSource.linksOn(node);
                if (pages == null) {
                    return;
                }

                for (Page ch : pages) {
                    if (alreadyVisited(ch)) {
                        continue;
                    }
                    activeTask.incrementAndGet();
                    threadPool.submit(new dfs(ch, node));
                }
            } finally {
                if (activeTask.decrementAndGet() == 0) {
                    completionLatch.countDown();
                }
            }
        }
    }

    private boolean alreadyVisited(Page node) {
        synchronized (this.visited) {
            if (visited.getOrDefault(node, false)) {
                return true;
            }
            visited.put(node, true);
            return false;
        }
    }


    public interface PageSource {
        List<Page> linksOn(Page page);
    }


    public static class Page {
        private final long pageId;
        private final String url;

        public Page(long pageId, String url) {
            this.pageId = pageId;
            this.url = url;
        }

        public long getPageId() {
            return this.pageId;
        }

        public String getUrl() {
            return this.url;
        }

        public void fetch() { // Some work that is done by each node => reading the content of a page.
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Page)) return false;

            return this.getPageId() == ((Page) o).getPageId();
        }

        @Override
        public int hashCode() {
            return Long.hashCode(pageId);
        }
    }


}

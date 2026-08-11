package Problems.S03_NotSoClassical.P01_SearchInsertDeleteList;

/*
Design a single linked list, that is accessed by 3 types of threads:
    * Searchers -> examine the list, any number of searchers can concurrently access.
    * Inserters -> add element to the end of the list, mutually exclusive wrt other Inserters, but can concurrently access with Searchers.
    * Deleters -> remove items from anywhere in the list. -> mutually exclusive wrt other Inserters or Searchers or Deleters.
 */
public class SearchInsertDeleteList<E> {
    private volatile Node<E> head = null; // because searcher threads, needs to read the value directly from memory flushed by inserter threads.(immediately) => head is read/written
    private SearchInsertDeleteLock lock;

    public SearchInsertDeleteList() {
        this(false);
    }

    public SearchInsertDeleteList(boolean fair) {
        this.lock = new SearchInsertDeleteLock(fair);
    }

    public boolean search(E key) throws InterruptedException {
        lock.lockSearch();
        try {
            return doSearch(key);
        } finally {
            lock.unlockSearch();
        }
    }

    public void insert(E key) throws InterruptedException {
        lock.lockInsert();
        try {
            doInsert(key);
        } finally {
            lock.unlockInsert();
        }
    }

    public boolean delete(E key) throws InterruptedException {
        lock.lockDelete();
        try {
            return doDelete(key);
        } finally {
            lock.unlockDelete();
        }
    }

    private boolean doSearch(E key) {
        Node<E> cur = this.head;
        while (cur != null) {
            if (cur.getVal() == key) {
                return true;
            }
            cur = cur.getNxt();
        }
        return false;
    }

    private void doInsert(E key) {
        Node<E> node = new Node<E>(key);

        if (head == null) {
            head = node;
            return;
        }
        Node<E> cur = head;
        while (cur.getNxt() != null) {
            cur = cur.getNxt();
        }
        cur.setNxt(node);
    }

    private boolean doDelete(E key) {
        Node<E> prev = null;
        Node<E> cur = head;
        while (cur != null) {
            if (cur.getVal().equals(key)) {
                if (prev == null) {
                    head = cur.getNxt();
                } else {
                    prev.setNxt(cur.getNxt());
                }
                return true;
            }
            prev = cur;
            cur = cur.getNxt();
        }
        return false;
    }

    public static class Node<E> {
        private E val;
        private volatile Node<E> nxt;
        // because searcher threads, needs to read the value directly from memory flushed by inserter threads.(immediately)

        public Node(E val) {
            this.val = val;
        }

        public E getVal() {
            return this.val;
        }

        public void setVal(E val) {
            this.val = val;
        }

        public Node<E> getNxt() {
            return this.nxt;
        }

        public void setNxt(Node<E> nxt) {
            this.nxt = nxt;
        }

    }
}

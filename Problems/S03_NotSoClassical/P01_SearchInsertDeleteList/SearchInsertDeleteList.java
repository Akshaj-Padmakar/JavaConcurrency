package Problems.S03_NotSoClassical.P01_SearchInsertDeleteList;

/*
Design a single linked list, that is accessed by 3 types of threads:
    * Searchers -> examine the list, any number of searchers can concurrently access.
    * Inserters -> add element to the end of the list, mutually exclusive wrt other Inserters, but can concurrently access with Searchers.
    * Deleters -> remove items from anwhere in the list. -> mutually exclusive wrt other Inserters or Searchers or Deleters.
 */
public class SearchInsertDeleteList<E> {

    private Node<E> head = null;
    private SearchInsertDeleteLock lock;

    public SearchInsertDeleteList(SearchInsertDeleteLock lock) {
        this.lock = lock;
    }

    public boolean search(E key) throws InterruptedException {
        lock.searchEnter();
        try {
            return doSearch(key);
        } finally {
            lock.searchExit();
        }
    }

    public void insert(E value) throws InterruptedException {
        lock.insertEnter();
        try {
            doInsert(value);
        } finally {
            lock.insertExit();
        }
    }

    public boolean delete(E key) throws InterruptedException {
        lock.deleteEnter();
        try {
            return doDelete(key);
        } finally {
            lock.deleteExit();
        }
    }

    private boolean doSearch(E key) {
        Node<E> cur = head;
        while (cur != null) {
            if (cur.getVal().equals(key)) {
                return true;
            }
            cur = cur.getNxt();
        }
        return false;
    }

    private void doInsert(E value) {
        Node<E> node = new Node<E>(value);
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
                    head = head.getNxt();
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

    private static class Node<E> {
        private E val;
        private Node<E> nxt;

        Node(E val) {
            this.val = val;
        }

        public E getVal() {
            return this.val;
        }

        public void setNxt(Node<E> nxt) {
            this.nxt = nxt;
        }

        public Node<E> getNxt() {
            return this.nxt;
        }
    }

}

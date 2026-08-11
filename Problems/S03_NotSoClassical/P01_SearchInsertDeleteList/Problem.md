# Search–Insert–Delete

> Book ref: *The Little Book of Semaphores* §6.1.
> Turns up in interviews (Rubrik included) as *"3-state access control"* or *"a synchronised linked
> list with searchers, inserters and deleters."*

> A singly-linked list is used by three kinds of thread. **Searchers** only read it — any number can
> run at once. **Inserters** append to the tail — one at a time, but they may run alongside
> searchers. **Deleters** remove a node from anywhere — they need the list completely to themselves.
> Write the synchronisation. Then tell me who starves.

## What you're building

**Role-based access control over a shared structure**, where the compatibility rules are richer than
reader/writer.

A snapshot catalogue behaves exactly like this: lookups run constantly, appends happen as a backup
streams new chunk records in, and a retention GC that unlinks expired entries needs the structure to
itself. Lookups don't conflict with each other. An append doesn't conflict with a lookup — it only
adds. An unlink conflicts with everything, because it rewires pointers that others are standing on.

The whole spec is one table:

|              | Searcher | Inserter | Deleter |
| ------------ | :------: | :------: | :-----: |
| **Searcher** |    ✅    |    ✅    |    ❌   |
| **Inserter** |    ✅    |    ❌    |    ❌   |
| **Deleter**  |    ❌    |    ❌    |    ❌   |

Many searchers **plus** one inserter may overlap; a deleter runs alone.

**Why this isn't a read-write lock.** `ReentrantReadWriteLock` has two modes. Here the middle role is
a *writer that tolerates readers* — a third state the two-mode lock cannot express. Model it as two
roles and you either serialise search against insert (throwing away the concurrency the problem
exists to provide) or you let a deleter run beside a searcher.

## Worked example

| # | Thread | Action | Active | Outcome |
|---|---|---|---|---|
| 1 | S1 | search | searchers=1 | runs |
| 2 | S2 | search | searchers=2 | runs — searchers share |
| 3 | I1 | insert | searchers=2, inserter=1 | runs — insert tolerates searchers |
| 4 | I2 | insert | unchanged | **blocks** — one inserter at a time |
| 5 | D1 | delete | unchanged | **blocks** — anything active excludes a deleter |
| 6 | S3 | search | ? | **depends entirely on your starvation policy** |
| 7 | — | S1, S2, I1 all exit | nothing active | D1 finally runs, alone |

Step 6 is the design decision the problem is really asking about.

## The failure you're designing against

A steady trickle of searchers, each overlapping the last:

| # | Active searchers | Deleter D1 |
|---|---|---|
| 1 | S1 | arrives, blocks |
| 2 | S1, S2 | still blocked |
| 3 | S2, S3 (S1 left) | still blocked — the count never reached 0 |
| 4 | S3, S4 | still blocked |
| … | never empty | **never runs** |

No individual searcher does anything wrong and the count is never illegal — the deleter simply never
sees a gap. This is livelock-adjacent: the system makes progress, one participant never does.

## The API

```java
public class SearchInsertDeleteList<E> {
    public SearchInsertDeleteList(SearchInsertDeleteLock lock);

    public boolean search(E key)   throws InterruptedException;
    public void    insert(E value) throws InterruptedException;
    public boolean delete(E key)   throws InterruptedException;
}
```

The policy lives in a separate object, so the list only knows *when* it's allowed to touch itself:

```java
public class SearchInsertDeleteLock {
    public void searchEnter() throws InterruptedException;  public void searchExit();
    public void insertEnter() throws InterruptedException;  public void insertExit();
    public void deleteEnter() throws InterruptedException;  public void deleteExit();
}
```

```java
var list = new SearchInsertDeleteList<String>(new SearchInsertDeleteLock());
list.insert("chunk-a");
boolean found = list.search("chunk-a");
list.delete("chunk-a");
```

Each list method is `enter → work → exit`, with `exit` in a `finally`.

## Constraints

- **No `ReentrantReadWriteLock`, no `StampedLock`** — and note they couldn't express the matrix
  anyway.
- **No `java.util.concurrent` collections.** The list is hand-rolled; that's the point.
- `ReentrantLock` + `Condition` is fine. Be ready to drop to `synchronized` / `wait` / `notifyAll`,
  or to build it from hand-rolled semaphores.
- **No busy-waiting**, and no `Thread.sleep` used as synchronisation.

## Requirements

- **Enforce the matrix exactly.** No illegal overlap, in either direction.
- **Search and insert must genuinely overlap** — a solution that serialises them satisfies the matrix
  and fails the problem.
- **State a starvation policy** for deleters, and say who pays for it.
- **The concurrent search/insert must be memory-safe**, not merely correctly scheduled.
- Waiting threads consume no CPU and respond to interruption.
- `exit` must run even if the operation throws.

## Edge cases

- Searching, inserting into, or deleting from an empty list.
- Deleting the head; deleting a key that isn't present.
- A searcher traversing while an inserter appends the node it is about to reach.
- The last searcher leaving at the same moment a deleter arrives.
- `insertExit()` / `deleteExit()` called by a thread that never entered.
- A thread interrupted while waiting to enter.
- A searcher that calls `search` again from inside a search (reentrancy).

## Questions to answer before you code

1. Write each of the three entry guards as a boolean over your state. What is the **minimum** state —
   how many counters and how many flags?
2. Which single cell of the matrix is the one `ReentrantReadWriteLock` cannot express? Say it in one
   sentence.
3. Searchers and inserters overlap by design, so one thread reads the node fields while another
   writes them **with no common lock held**. Is your list memory-safe? Correct scheduling and correct
   visibility are different problems — which one have you actually solved?
4. Appending at the **tail** is structurally benign for a concurrent searcher — why? Would inserting
   in the **middle** still be, and what changes?
5. Under your policy, which role starves? Write the exact sequence of operations that starves it.
6. Blocking new arrivals once a deleter is waiting fixes deleter starvation. What does that break,
   and what would you do about *that*?
7. When a deleter exits, who do you wake — one searcher, all searchers, one inserter, or everything?
   Justify from the shape of each predicate, not from what feels efficient.
8. Do the three roles need three separate conditions, or can some share one? What decides it?
9. What should `deleteExit()` do if called by a thread that never entered?
10. How would you *prove* the matrix holds under stress? Name the invariant you'd assert and the
    moment you'd assert it.
11. The lock is a separate object from the list. What does that separation buy you — and what would
    you lose by folding the counters into the list itself?

## Jargon

| The plain phrasing | The term to use out loud |
|---|---|
| "any number at once" | shared mode |
| "one at a time, completely alone" | exclusive mode |
| "a writer that tolerates readers" | the third state — why a two-mode lock doesn't fit |
| "who is allowed in together" | compatibility matrix |
| "the first one in locks the door, the last one out unlocks it" | lightswitch |
| "a waiting deleter blocks new arrivals" | deleter preference; turnstile |
| "one role never gets a turn" | starvation |
| "everyone is served in arrival order" | FIFO fairness, ticket lock |
| "safe to read while another thread is writing it" | safe publication, visibility |
| "the scheduling is right but the data isn't visible" | data race — distinct from a race condition |
| "read without locking and check afterwards" | optimistic reads, RCU, hazard pointers |

# Search-Insert-Delete — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §6.1. Rubrik-style: "3-state access control" /
> "synchronized linked list with searchers, inserters, deleters."

## One-line idea

Three roles, one compatibility matrix: **many searchers ‖ one inserter** may overlap; a **deleter is
totally exclusive**. Encode the matrix with per-role state + conditions (that's *scheduling*), and
publish the shared node fields with `volatile`/`final` (that's *visibility*). Both are required.

## Compatibility matrix

|            | Searcher | Inserter | Deleter |
| ---------- | :------: | :------: | :-----: |
| **Searcher** |   ✅    |    ✅    |   ❌    |
| **Inserter** |   ✅    |    ❌    |   ❌    |
| **Deleter**  |   ❌    |    ❌    |   ❌    |

Not a plain read-write lock: insert is a "writer that tolerates readers" — a **third** state RWLock
can't express.

## Design (this repo): monitor, deleter-preference

State: `activeSearchers` (count), `inserterActive`, `deleterActive` (flags), and
`waiting{Searchers,Inserters,Deleters}` counts. One `ReentrantLock` + three `Condition`s.

**Enter predicates — wait while an incompatible party is active OR a deleter is waiting:**

```java
searchEnter : while (deleterActive || waitingDeleters > 0) await;        activeSearchers++
insertEnter : while (inserterActive || deleterActive || waitingDeleters > 0) await;  inserterActive = true
deleteEnter : while (activeSearchers > 0 || inserterActive || deleterActive) await;  deleterActive = true
```

The `waitingDeleters > 0` term in search/insert is **deleter-preference** — it stops new
searchers/inserters from jumping ahead of a waiting deleter (prevents deleter starvation).

**Exit — wake the right waiters:**

```java
searchExit : activeSearchers--; if (activeSearchers==0 && waitingDeleters>0) signal deleter
insertExit : inserterActive=false; if (waitingDeleters>0) signal deleter
                                    else if (waitingInserters>0) signal ONE inserter
deleteExit : deleterActive=false; if (waitingDeleters>0) signal deleter
                                   else { signal ONE inserter; signalAll searchers }  // both resume together
```

- `searchExit` only needs to wake a **deleter** — nothing else waits on searchers.
- `insertExit` needn't wake searchers: a searcher blocks *only* on deleter state, so if one is
  blocked a deleter is waiting → we signal the deleter (which later frees searchers).
- `deleteExit` frees the world: one inserter **and** all searchers (they're mutually compatible).

## ⭐ The deep lesson: scheduling ≠ visibility

The list ops (`doSearch`/`doInsert`) run **outside** the lock — `enter()` releases it before the
traversal, `exit()` re-acquires it after. That's *required*: holding it across the traversal would
serialize search+insert and destroy the concurrency. But then, for the **allowed** overlap (search
running while insert appends), two threads touch `head` / `Node.next` / `Node.val` with **no common
lock held** → a **data race**.

Concrete failure:

```java
Node n = new Node(value);   // (1) constructor writes n.val
oldTail.setNxt(n);          // (2) publish the node
```

The JMM does **not** guarantee (1) is visible before (2) to another thread. A concurrent searcher can
read `oldTail.next == n` (sees the link) but `n.val == null` (stale) → **NPE** or wrong result. Passes
tests on x86 (strong memory model), can break on ARM / under load / after JIT reordering.

**Fix — publish the shared fields:**

```java
private volatile Node<E> head;          // read by searchers, written by inserters/deleters
private static final class Node<E> {
    private final E val;                 // final-field freeze: any thread seeing the node sees val set
    private volatile Node<E> nxt;        // mutated after construction -> volatile (can't be final)
}
```

- **`final val`** — once the constructor returns, a `final` field is guaranteed visible to any thread
  that later sees the object. Kills the "node visible but val null" bug.
- **`volatile head`/`nxt`** — happens-before on every read; kills stale-link reads.

> If you held one exclusive lock across the whole op, its release/acquire would publish everything for
> free — but then search+insert couldn't overlap. **The price of the overlap is that you must publish
> the fields yourself.** Naming this scheduling-vs-visibility split is the senior signal.

## Starvation — the follow-up they always ask

- **Deleter starvation** (the classic worry): fixed here by **deleter-preference** (`waitingDeleters
  > 0` blocks new searchers/inserters). State this explicitly.
- **New problem it creates:** a delete flood now **starves searchers/inserters**.
- **Make it fair:** a **FIFO turnstile / ticket** — everyone queues in arrival order; grant a run of
  compatible ops (batch searchers, or one inserter+searchers) then the next deleter. Bounded waiting
  for all three.

## JDK primitives — and why the obvious one is not enough

- **`ReentrantReadWriteLock`** — ❌ only **two** states. It can't express "insert (a writer) runs
  concurrently with searchers (readers)." You'd need read=search, but insert is neither a plain read
  nor a plain exclusive write.
- **`ReentrantLock` + `Condition`s** — ✅ what we used; full control over the 3-role matrix.
- **Semaphores** — a searcher-count **lightswitch** (`roomEmpty` held while any searcher OR the
  deleter is in) + an `insertMutex`; the book's style.
- **Lock-free** — `ConcurrentLinkedQueue` / `ConcurrentSkipListSet` sidestep the whole thing with
  CAS + hazard pointers/RCU; mention if asked to scale reads under writes.

## Interview follow-ups (rapid fire)

- Prevent deleter starvation? → deleter-preference (shown). Then make it fair? → FIFO turnstile.
- Why not `ReentrantReadWriteLock`? → only 2 states; insert-tolerates-search is a 3rd.
- Is concurrent search+insert memory-safe? → only with `volatile`/`final` on the shared fields.
- Insert in the **middle** (not tail) concurrently with searchers? → pointer rewiring can be seen
  mid-traversal; needs care (or make insert exclusive-with-search too).
- Test the matrix? → instrument active counts; assert `deleterActive ⇒ others == 0`,
  `inserterActive ⇒ no other inserter/deleter`.

## Things to keep in mind

- **`while`-guard** every `await` (spurious wakeups + re-check after reacquire).
- Wake **exactly** the compatible waiters: deleter alone, or one inserter + all searchers.
- Bookkeeping under the lock; **traversal outside** the lock → publish fields.
- Decide + **state** the starvation policy.

## 30-second recall

> 3 roles: many searchers ‖ one inserter overlap; deleter alone. Monitor with per-role counts/flags;
> enter waits on incompatible-active **or** `waitingDeleters>0` (**deleter-preference**, stops deleter
> starvation). Exit: deleter alone, else one inserter + `signalAll` searchers. **Scheduling ≠
> visibility** — traversal runs outside the lock, so publish `head`/`next` (`volatile`) and `val`
> (`final`). `ReentrantReadWriteLock` can't do it (only 2 states). Fair version = FIFO turnstile.

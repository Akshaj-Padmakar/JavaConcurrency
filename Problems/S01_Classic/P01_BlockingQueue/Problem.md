# Bounded Blocking Queue

> Implement a thread-safe bounded queue that multiple producer threads and multiple consumer
> threads can share. A producer that finds it full waits until there's room; a consumer that finds
> it empty waits until there's something to take. Also give me non-blocking and timed versions of
> both, and the usual inspection methods.

## What you're building

A hand-off buffer between threads that run at different speeds — a backup agent's reader threads
producing file chunks while uploader threads drain them, a log pipeline, the task queue inside a
thread pool.

The bound is the entire point. An unbounded queue with a fast producer and a slow consumer doesn't
fail loudly — it grows until the process dies of memory exhaustion, usually in production, usually
at 3am. Making the producer *wait* when the queue is full is how the consumer's slowness gets
communicated backwards to the producer. The blocking isn't a limitation of the design; it **is** the
design. Everything else here is convenience API around that one idea.

## Worked example

Capacity 2, one producer thread `P`, one consumer thread `C`.

| # | Thread | Call | Queue after | Result |
|---|---|---|---|---|
| 1 | P | `put(A)` | `[A]` | returns |
| 2 | P | `put(B)` | `[A, B]` | returns |
| 3 | P | `put(C)` | `[A, B]` | **blocks** — full |
| 4 | C | `take()` | `[B]` | returns `A`, and P becomes runnable |
| 5 | P | *(resumes)* | `[B, C]` | returns |
| 6 | C | `take()` | `[C]` | returns `B` |
| 7 | C | `take()` | `[]` | returns `C` |
| 8 | C | `take()` | `[]` | **blocks** — empty |

Note steps 3 and 8: the *same* structure blocks a producer and a consumer for opposite reasons.

## The API

```java
public class BlockingQueue<T> {

    public BlockingQueue(int capacity);
    public BlockingQueue(int capacity, boolean fair);

    /* insert */
    public void    add(T item);                       // inserts, or throws if full
    public boolean offer(T item);                     // inserts, or returns false if full
    public void    put(T item) throws InterruptedException;                 // waits for room
    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException;

    /* remove */
    public T poll();                                  // removes head, or null if empty
    public T take() throws InterruptedException;      // waits for an item
    public T poll(long timeout, TimeUnit unit) throws InterruptedException;

    /* inspect */
    public T   peek();                                // head without removing, null if empty
    public int size();
    public int remainingCapacity();
}
```

Three groups of three, and within each group the same escalation: **fail immediately → wait forever
→ wait with a deadline.** How a caller drives it:

```java
BlockingQueue<Chunk> q = new BlockingQueue<>(64);

// on each producer thread                 // on each consumer thread
q.put(readNextChunk());                    upload(q.take());

// or, if the caller can't afford to block indefinitely:
if (!q.offer(chunk, 500, MILLISECONDS)) dropAndCount(chunk);
```

## Constraints

- **No `java.util.concurrent` queue or collection.** `ArrayBlockingQueue`, `LinkedBlockingQueue`,
  `ConcurrentLinkedQueue` *are* the answer to this question — handing one back answers nothing.
- `ReentrantLock` + `Condition` are allowed; they're the named primitive, not the answer. Be ready
  to say you can drop to `synchronized` / `wait` / `notifyAll` instead, and to actually do it if
  asked.
- Plain `java.util` for the storage itself (`LinkedList`, `ArrayDeque`, a raw array) is fine — the
  container isn't the subject of the question, the coordination is.
- **No busy-waiting.** A spin loop that keeps checking `size()` is a wrong answer even when it
  produces correct output.

## Requirements

- Thread-safe for many producers *and* many consumers simultaneously.
- Strict FIFO: items come out in the order they went in.
- Capacity fixed at construction; never exceeded, under any interleaving.
- Blocking methods consume no CPU while waiting.
- Timed methods honour their **total** deadline — waking early and going back to sleep must not
  restart the clock.
- Blocked threads respond to interruption.
- The three inspection methods never block.

## Edge cases

- `capacity` of `0`, or negative.
- A `null` item.
- A timeout of `0`, or negative.
- A thread interrupted while parked in `put` / `take` / a timed call.
- Several producers blocked on a full queue when exactly one slot frees up.
- A caller using `size()` or `remainingCapacity()` to decide whether to `add()`.
- The queue drained to empty and refilled while consumers are still parked.

## Questions to answer before you code

1. Which of these eleven methods can block, and which must never block? What does that split imply
   about how many separate wait-sets you need?
2. If you used a single condition variable for both "not full" and "not empty", what specifically
   goes wrong — and would it show up as a hang or as a corruption?
3. Every method that changes the queue's *contents* owes the same obligation before it releases the
   lock. What is it? Now list every method that changes contents — is that list longer than you
   first thought?
4. `signal` or `signalAll`? What property of the waiting threads decides it, and what does the wrong
   choice cost you in each direction?
5. Why must the wait be inside a `while`, never an `if`? Name both reasons.
6. A timed `offer` wakes with time still on the clock and the queue is still full. What must it do
   next — and what must it *not* recompute?
7. Is `if (q.size() < cap) q.add(x)` safe? If not, is the bug in the caller or in your API?
8. What should `new BlockingQueue<>(0)` do — construct fine, or refuse? What breaks if you allow it?
9. A thread blocked in `take()` gets interrupted at the same moment a producer signals it. Where
   could that signal go, and who is left waiting?
10. Does the `fair` flag affect correctness, or only scheduling? Would you default it on or off?

## Jargon

| The plain phrasing | The term to use out loud |
|---|---|
| "wait until there's room" | blocking; backpressure / flow control |
| "the place a thread waits for one specific thing" | condition variable, wait-set |
| "re-check the situation after waking up" | guarded suspension; predicate loop |
| "it woke up and nothing had happened" | spurious wakeup |
| "someone else grabbed the slot before the woken thread ran" | barging |
| "the item landed but nobody ever woke up" | lost wakeup |
| "wake everyone, one wins, the rest go back to sleep" | thundering herd |
| "a hand-off buffer that slows the producer down" | bounded buffer — the producer-consumer problem |
| "waiting threads get served in arrival order" | fairness (FIFO lock acquisition) |
| "one unlucky thread never gets a turn" | starvation |

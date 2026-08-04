# The Santa Claus Problem

> Book ref: _The Little Book of Semaphores_ §5.5.

## Problem

Santa sleeps at the North Pole and is woken in exactly two situations:

- **All 9 reindeer** have returned from vacation → Santa harnesses them and **delivers toys**, then
  unharnesses them (they leave on vacation again).
- **3 elves** are waiting with a problem → Santa **helps that group of 3**, then they go back to work.

### Priority rule (the crux)

- If **9 reindeer** are waiting **and** a group of elves is also waiting, Santa handles the
  **reindeer first** (Christmas takes priority).
- Santa can only do **one** thing at a time; while delivering toys or helping elves, he isn't
  woken again.

### Elf batching rule

- Elves only wake Santa in **groups of exactly 3**. If there are fewer than 3 waiting, an elf waits.
- While Santa is helping 3 elves (or 3 have already engaged him), a **4th, 5th, … elf must wait** —
  the "waiting room" for elves holds at most 3 at a time; extras block until the current group is
  done. (This is a `Multiplex`/counting-gate of size 3.)

### API (typical)

```java
class SantaClaus {
    void santa();            // sleep; wake for reindeer (priority) or a group of 3 elves
    void reindeer(int id);   // return from vacation; the 9th wakes Santa; get harnessed; deliver
    void elf(int id);        // get a problem; every 3rd elf wakes Santa; get helped
}
```

## The traps

1. **Priority under simultaneous readiness.** When the 9th reindeer and the 3rd elf arrive at nearly
   the same time, Santa must deterministically pick reindeer. Naive signalling can let elves slip in.
2. **The "3rd elf" gate.** Only the elf that completes a group of 3 should wake Santa; elves 1 and 2
   wait, and elves 4+ must be held out until the current group finishes — otherwise 6 elves stampede
   one Santa.
3. **Rendezvous both ways.** Reindeer must all be harnessed before delivery and released after;
   elves must all be helped before dispatch. Nobody leaves early, Santa doesn't start the next
   event mid-way.

## Points to Ponder

- **How do you encode the reindeer-over-elves priority?** In a monitor: check `reindeerReturned == 9`
  **before** the elf condition in Santa's `while`. With semaphores: Santa waits on a single
  "santaSem", and the *counting* logic decides which path he takes.
- **Counting who wakes Santa.** The `k`-th reindeer / 3rd elf is the one that signals — the classic
  "last one in the group flips the switch" pattern (like the lightswitch in readers-writers).
- **The elf multiplex of 3.** How do you cap concurrent problem-elves at 3 and reset the gate after a
  group is served? (A semaphore initialized to 3, or a counter + condition.)
- **Reset / re-arm each round.** After delivery, reindeer counter resets to 0; after helping, the elf
  group counter resets and the next 3 can form. Where exactly do you reset to avoid a lost wake?
- **Starvation.** With strict reindeer priority, can elves starve? (In practice reindeer events are
  rare — once a year — so it's acceptable; worth stating.)
- **`signal` vs `signalAll`.** Reindeer wait on one predicate, elves on another, Santa on a third —
  which signals must be broadcast so the whole group proceeds together?
- **Generalization.** This is a **barrier** (group of N must assemble) + **priority scheduling** +
  **multiplex** all in one. Which sub-pattern maps to which rule?
- **Termination.** How do Santa, reindeer, and elves shut down without leaving Santa asleep forever?

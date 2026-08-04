# Santa Claus — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §5.5. Monitor solution (`ReentrantLock` + `Condition`s).

## One-line idea

Santa sleeps until **9 reindeer** are back (→ deliver toys) **or** **3 elves** are waiting (→ help
the group). If both are ready, **reindeer win**. Extra elves are held out until the current group of
3 is fully served. It's three patterns stacked: **barrier** (assemble a group) + **priority** +
**multiplex of 3**.

## The three sub-problems (map each to its mechanism)

| Sub-problem                         | Mechanism                                                        |
| ----------------------------------- | --------------------------------------------------------------- |
| Wake Santa for reindeer OR elves    | Santa sleeps on `while (reindeer<9 && elves<3)`; the last member signals |
| Reindeer beat elves on a tie        | after waking, check `if (reindeerWaiting == 9)` **first**       |
| Only 3 elves engage Santa at a time | the **gate/baton**: `while (elfWaiting == 3 || helping)`        |
| A group acts together & re-arms     | last member of the group resets the round and reopens the gate  |

## State model

| Field                                  | Meaning                                             |
| -------------------------------------- | --------------------------------------------------- |
| `reindeerWaiting` / `reindeerDelivered`| reindeer back this round / finished delivery        |
| `elfWaiting` / `elfHelped`             | elves gathered in the group / helped in the group   |
| `delivering` (bool)                    | reindeer go-flag (Santa is delivering)              |
| `helping` (bool)                       | elf go-flag (Santa is helping the 3)                |

Conditions: `santaSleepCondition`, `reindeerWaitingCondition` (gate + wait-for-delivery),
`elfWaitingCondition` (gate + wait-for-help).

## Core templates

**Santa** — sleep on `&&`, reindeer priority, **consume the trigger**:

```java
while (reindeerWaiting < 9 && elfWaiting < 3) santaSleepCondition.await();
if (reindeerWaiting == 9) {              // PRIORITY: reindeer first
    delivering = true;  reindeerWaiting = 0;  reindeerWaitingCondition.signalAll();
} else {                                 // elfWaiting == 3
    helping = true;     elfWaiting = 0;       elfWaitingCondition.signalAll();
}
```

**Elf** — gate (baton), 3rd wakes Santa, last of group re-arms:

```java
while (elfWaiting == 3 || helping) elfWaitingCondition.await();  // GATE: hold extras out
elfWaiting++;
if (elfWaiting == 3) santaSleepCondition.signal();              // 3rd wakes Santa, keeps the baton
while (!helping) elfWaitingCondition.await();                   // wait to be helped
// ...helped...
if (++elfHelped == 3) { elfHelped = 0; helping = false; elfWaitingCondition.signalAll(); } // reopen gate
```

**Reindeer** — entry gate, 9th wakes Santa, last of round re-arms:

```java
while (delivering) reindeerWaitingCondition.await();           // don't join a round mid-delivery
reindeerWaiting++;
if (reindeerWaiting == 9) santaSleepCondition.signal();        // 9th wakes Santa
while (!delivering) reindeerWaitingCondition.await();          // wait to be harnessed
// ...delivering...
if (++reindeerDelivered == 9) { reindeerDelivered = 0; delivering = false; reindeerWaitingCondition.signalAll(); }
```

## The four bugs that make this hard (I hit all of them)

1. **Santa's sleep predicate must be `&&`, not `||`.** Sleep while *not-enough-reindeer* **AND**
   *not-enough-elves*. With `||` Santa only wakes when **both** are ready at once — he ignores
   reindeer-only and elf-only events.
2. **The 3rd elf must `signal()` Santa, not `await()`.** `await()` puts the *elf* to sleep on
   Santa's condition — Santa is never woken, **and** now elf + Santa share `santaSleepCondition`, so
   a reindeer's `signal()` can wake the sleeping elf instead of Santa → Santa freezes at `9/9`.
3. **The gate needs `elfWaiting == 3 || helping`, not just `helping`.** Between the 3rd elf gathering
   and Santa flipping `helping`, a bare `while (helping)` lets a 4th/5th/… elf slip in → groups grow
   past 3 (`waiting (10/3)`). The `elfWaiting == 3` term is what "keeps the baton."
4. **Re-arm with `signalAll()`, not `signal()`.** `elfWaitingCondition` serves **two** predicates
   (gate wait `while(full/helping)` and help wait `while(!helping)`). Overloading one condition with
   two predicates **requires** `signalAll` so every waiter re-checks; `signal()` can wake the wrong
   one and strand the rest.

## Why "consume the trigger" matters (subtle)

After Santa decides to act he sets the go-flag **and zeroes the counter** (`reindeerWaiting = 0` /
`elfWaiting = 0`) *before looping*. Otherwise Santa loops back, still sees `reindeerWaiting == 9`
(the group hasn't reset it yet), and **delivers twice**. The go-flag stays true and is cleared by the
**last member** of the group ("last one closes the round") — the same lightswitch idea as elsewhere.

## Design note: one condition vs two

This version overloads `elfWaitingCondition` for both the gate and the help-wait, which is why every
wake on it **must** be `signalAll`. A cleaner alternative is **two** elf conditions — `elfGate`
(held-out elves) and `elfHelpCond` (the 3 being helped) — each with a single predicate, so targeted
`signal`/`signalAll` is unambiguous and less error-prone.

## Things to keep in mind

- **`while`-guard every `await`** (spurious wakeups + predicate may change before you reacquire).
- **Last member of a group re-arms** the round and reopens the gate.
- **Starvation:** strict reindeer priority *can* starve elves in theory; fine in practice (reindeer
  events are rare). State the trade-off.
- **Termination:** infinite simulation; `solve()` `join()`s forever. Add a `done` flag checked in
  Santa's `while` + wake-all to shut down cleanly.

## 30-second recall

> Santa sleeps on `while(reindeer<9 && elves<3)`; **reindeer beat elves** on a tie. The group's
> **last arrival signals** Santa (9th reindeer / 3rd elf). Elf **gate = `elfWaiting==3 || helping`**
> ("3rd keeps the baton"); reopen it when the group's **last member** finishes (`signalAll`).
> **Consume the trigger** (`=0`) so Santa doesn't double-fire. Overloaded condition ⇒ `signalAll`.

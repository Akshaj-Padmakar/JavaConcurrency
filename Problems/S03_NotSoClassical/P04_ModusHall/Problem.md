# The Modus Hall Problem

> Book ref: _The Little Book of Semaphores_ §6.4. Written by Nathan Karst (Olin College).
> Sibling of [P03_BaboonCrossing](../P03_BaboonCrossing/Problem.md) — same categorical-exclusion
> shape, **different** fairness mechanism: **majority rule** instead of a fixed batch size.

## Setup

- A single-file path connects the "Mods" (**heathens**) to West Hall (**prudes**).
- Two people of the **same** faction meeting on the path: no problem, one steps aside.
- Two people of **opposite** factions meeting: a "skirmish" — the faction with **more people
  currently on the path wins**; the smaller faction waits.

## The trap

- No fixed batch cap here — the rule that decides who controls the path is **relative**: whichever
  side currently has more checked-in members. That threshold moves as people check in and out.
- No-starvation isn't bounded by "at most `B` before a forced check" (as in P02/P03) — it's bounded
  by "the losing side keeps accumulating until it **outnumbers** the winning side," at which point it
  can force a switch.

## Key idea

- Field `status` ∈ `neutral / heathens rule / prudes rule / transition-to-heathens /
  transition-to-prudes`, guarded by one mutex alongside `heathens`/`prudes` counters.
- **Check-in:** if the field is `neutral`, claim it. If the other faction rules but your arrival tips
  the count in your favor, start a **transition** (lock the *other* faction's entry turnstile) and
  queue. If a transition to your side is already underway, queue. Otherwise (your side already rules,
  or transitioning to the other side), proceed.
- **Check-out:** if you're the **last of your faction** leaving: if mid-transition, unlock the other
  side's turnstile; hand control to whoever's waiting (or go `neutral`). If you're **not** the last
  but your leaving flips the majority, preemptively start a transition the other way.

## Ponder

- How does "majority rule" avoid needing a fixed `BATCH_SIZE` at all — what plays that role instead?
- Why must the *losing* faction's entry be blocked with an actual turnstile during a transition,
  rather than just relying on the `status` flag to gate new arrivals?
- The book flags a specific correctness caveat: threads can be interrupted **after** passing the
  turnstile but **before** checking in (incrementing their counter). Why does that not break
  correctness, only concurrency/efficiency?
- Does majority rule guarantee **maximum concurrency**? (The book says explicitly: no.)
- Compare to P02/P03's fixed-batch approach: which is fairer when `N ≫ M`? Which gives better
  throughput when `N ≈ M`?
- Could this be built with a monitor (`ReentrantLock` + `Condition`, this repo's usual style) instead
  of raw semaphores + turnstiles? What replaces `heathenTurn`/`prudeTurn`?

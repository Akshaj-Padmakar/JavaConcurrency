# Cigarette Smoker Problem Notes

## Core Idea

Three smokers:

- Tobacco smoker
- Paper smoker
- Match smoker

Each smoker permanently owns one ingredient.

Agent places two ingredients.

Only the smoker with the missing third ingredient may proceed.

---

## Why Pushers Were Introduced

Ingredients arrive independently.

But smoker selection depends on the **pair**.

Pushers act as:

**event combiners / matchers**.

Role:

1. Observe ingredient arrival
2. Detect valid pair
3. Determine missing ingredient
4. Wake correct smoker

Without pushers:

- agent must contain matching logic
- coordination becomes centralized
- synchronization becomes harder

Pushers separate:

```text
ingredient arrival
```

from:

```text
smoker selection
```

---

## Important Condition Variable Rule

Wait condition should include:

```text
business condition OR termination condition
```

Pattern:

```java
while (!condition && !stop) {
    await();
}
```

Reason:

Condition signals are:

```text
not buffered
```

A signal sent before:

```text
await()
```

is lost.

Guarding with shared state prevents:

- missed wakeups
- hanging joins
- startup races

---

## Stop / Cleanup Pattern

Single iteration termination:

```text
stop=true
signal(all)
```

Purpose:

Wake blocked threads and allow:

```text
join()
```

to finish.

Reusable pattern:

```text
termination flag + broadcast signal
```

Common in:

- worker shutdown
- producer-consumer cleanup
- coordinated thread exit

---

## Key Matching Logic

Pushers perform:

```text
tobacco + paper -> match smoker
tobacco + match -> paper smoker
paper + match -> tobacco smoker
```

This is the actual synchronization logic.

Everything else supports safe coordination.

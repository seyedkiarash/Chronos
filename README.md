# Chronos

A personal planner for Android that decides **when** to do things, not only **what**. It plans
the hours of the coming weeks towards your goals and deadlines, arranges each day around classes,
meals, travel, sleep and rest using a model of your energy and fatigue, and learns from what you
log. On top of it, the **Navigator** keeps a graph of long-term goals and forecasts the chance of
reaching each one in time.

The interface is in English and Persian (right to left, Jalali dates).

## What it does

- **Horizon**: spreads the hours of each goal over the coming days up to its deadline, and says
  early when a deadline cannot be met and what would fix it.
- **Day**: places every block where it costs least, using a body-clock model (sleep pressure,
  circadian rhythm, mental and physical fatigue). Free time is filled with leisure you chose,
  under a daily ceiling of stimulation.
- **Log**: sessions, focus, tiredness, sleep, classes and check-ins; each night the model learns
  how long things really take, how tiring they are and when you work best.
- **Navigator**: a goal graph (tasks, skills, milestones, decisions) with Monte Carlo forecasts,
  a weekly allocation of hours, and proposals from the app's own rules or from an LLM that you
  approve or reject one change at a time.

A full guide for new users, in Persian, is in [`docs/`](docs/).

## Building

Android Studio (AGP 9) with JDK 17 or newer.

```bash
./gradlew :app:assembleDebug
```

Tests (the planning engine and the Navigator are plain Java and run on the JVM):

```bash
./gradlew :app:testDebugUnitTest :navigator:test
```

## Layout

- `app/src/main/java/com/chronos/engine`: the planner — horizon, day sequencer, energy model,
  learning (`Calibrator`), feasibility, leisure and patterns in the log (`Habits`).
- `app/src/main/java/com/chronos/data`: Room database, migrations and the repository.
- `app/src/main/java/com/chronos/ui`: Jetpack Compose screens.
- `navigator/`: the goal graph, its estimation, forecasting and allocation, and the LLM layer
  (prompts, checks of every proposed change). Its test graph is a made-up sample.

## Licence

Copyright © 2026 seyedkiarash. All rights reserved; see [LICENSE](LICENSE). Written with the
assistance of Claude (Anthropic).

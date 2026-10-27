# Chronos — how the code is laid out

Two Gradle modules:

- **`:app`**: the Android app. Screens, database, background work, and the Chronos planning engine (plain
  Java, testable on the JVM).
- **`:navigator`**: the goal-graph engine (plain Java 17 + Gson, no Android).

Inside `:app` the code is grouped by what you do with the app. Under every screen sits the same stack:

**screen → view model → repository → engine → Room database**

## By what you do

| You… | Screen (`ui/…`) | Logic | Stored in |
|---|---|---|---|
| set up goals, activities, upkeep, leisure, classes, sleep | `ui/setup` (SetupScreen, SetupBoard, SetupEditors, ClassTimetable) | `validate/Readiness` (what is missing), `engine/Umbrellas` (goals as groups; moving work to another goal), `model/*` (windows with weekdays of their own, resolved per date by `Config.on`) | `goals`, `activities`, `commitments`, `params` |
| set priorities | `ui/setup/WeightsScreen` (four levels, one order, drag to move) | `engine/Priorities` (level and place → weight; places a new target with its reasons) | `goals.tier`, `goals.rank` |
| build and read the plan | `ui/horizon` (HorizonScreen, HorizonViews) | `engine/HorizonPlanner` (hours per day per goal), `Sacrifice` (cuts for deadlines), `Feasibility` (what is short, what fixes it) | `snapshots` |
| see a day | `ui/day` (DayScreen, DayMatchPanel) | `engine/DaySequencer` (where each block sits), `Energy` + `Objective` (body clock, fatigue, stimulation), `FixedFrame` (classes, travel) | `day_plans` |
| log what happened | `ui/log` (LogScreen, ReminderPanels) | `auto/Reminders`, `auto/QuickLog` (one-line replies to the model) | `logs` |
| let it learn (every night) | — | `auto/AutoPlanner` (NightlyWorker, close-out), `engine/Calibrator` (body clock, quality, fatigue, class sessions, leisure stimulation), `engine/RestAdvisor` | `params`, `activities`, `app_state` |
| look ahead with the Navigator | `ui/navigator` (Home, Network map, Plan, Proposal, Crew, Llm, History…) | `:navigator` — `model` (graph), `estimate` (Kalman speed, adherence), `forecast` (Monte Carlo bands, guard), `allocate` (weekly hours), `trigger`, `llm` (prompts, ChangeSet checks, Staleness) | `nav_*` tables |
| get proposals and approve them | `ui/navigator/NavigatorProposal`, `NavigatorCrew` | `navigator/llm/client` (CrewRun: crew, relay, habits packages; LlmClient: several keys per provider through KeyRing, cut-off answers continued; NavAsk: sendSure across providers; Outbox: your messages kept until answered; Recheck), `engine/Habits` (patterns in the log), `navigator/bridge/ChronosControl` (try a change on a copy, apply it once approved) | `nav_proposals`, files under `navigator-crew/` |
| keep both sides in step | — | `navigator/bridge` (Bridge: Chronos → Navigator inputs; NavEngine: runs, approvals; Sync: deadlines and units both ways) | — |
| see it in the phone's calendar | — | `calendar/CalendarSync`, `CalendarWriter` | `day_plans.synced_to_calendar` |

## The layers in `:app`

- `ui/`: `MainActivity` (drawer, tabs), `ChronosViewModel` (the planner's state). Sub-packages by task:
  - `setup`, `horizon`, `day`, `log`, `navigator`
  - `common`: Theme and colours, widgets, charts, `TimeColumns`, and the translator (`Strings`, `StringsFa`)
- `data/`:
  - `ChronosRepository`: the one way in for the screens and the workers
  - `ChronosDb` + `DatabaseWiring`: Room, with every migration
  - `Mappers` and `Codec`: row ⇄ model
  - `dao`, `entity`
- `engine/`: the planner. No Android in it.
- `model/`: plain data — Config, Goal, Activity, Commitment, LogEntry, Block, Params.
- `auto/`: work in the background — the nightly close-out, reminders, quick log, log export.
- `calendar/`: writing the plan into the phone's calendar.
- `navigator/`: the Android side of the Navigator.
  - `store`: its tables
  - `bridge`: talking to Chronos
  - `llm/client`: network calls, the crew, the copy-and-return relay, the outbox
- `validate/`: what Setup still lacks before the planner may run.

## Rules that keep it in order

1. **The engine stays pure.**
   - `engine/`, `model/` and `:navigator` import nothing from Android, and every rule there has a JVM test
     (`app/src/test/.../harness`, `navigator/src/test`).
   - Run them with `./gradlew :app:testDebugUnitTest :navigator:test`.
2. **Screens never touch the database.** They go through the view models, and the view models through
   `ChronosRepository` or `NavEngine`.
3. **Nothing changes your data without you.**
   - The Navigator's changes (an LLM's or the app's own rules') are packages in the inbox, each one measured
     on a copy first.
   - Only `NavEngine.approveCrew` applies them.
4. **A schema change is a migration.**
   - Add an `ALTER TABLE` in `DatabaseWiring` and raise the version in `ChronosDb`.
   - Back up the phone's database before installing.
5. **A change to how days are arranged raises `ENGINE_VERSION`** in `ChronosViewModel`, so the stored plan
   is rebuilt once.
6. **Every visible English string has a Persian line in `ui/common/StringsFa.kt`.** Sentences with numbers
   go there as templates with `{}`.
7. **New screen code goes in the sub-package of its task.** What two tasks share goes in `ui/common`.
8. **What you write is never lost to the network.** A message to a model is kept on the phone first and sent in
   the background (`Outbox`, WorkManager); a failure is shown with its reason, to send again or edit.

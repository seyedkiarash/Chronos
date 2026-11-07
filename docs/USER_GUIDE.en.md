# Chronos — user guide

For anyone trying the app for the first time (beta 0.9). The full guide is in Persian, in
[USER_GUIDE.fa.md](USER_GUIDE.fa.md); this is the same tour, shorter.

## What it is

Chronos is a personal planner for Android that decides **when** to do things. It spreads the hours of
the coming weeks over your goals, arranges each day around classes, meals, travel and sleep using a
model of your energy and fatigue, and learns from what you log. The **Navigator** looks further: a
graph of long-term goals, a forecast of the chance of reaching each, a daily conversation, and
proposals that change nothing until you approve them. Everything stays on your phone.

## Install

Install the `Chronos Planner` APK (allow installs from that source if asked). The app opens on Setup.

## The screens

- **Setup**: language, theme, an optional app lock and hiding from screenshots; goals (with a deadline
  and units, or ongoing with weekly hours) and their activities; upkeep; leisure; classes and travel;
  sleep and the body clock; when the day closes.
- **Horizon**: four tiles (deadlines on track, hours in the next 7 days, room left, problems), the
  month and a timeline per goal. When a goal does not fit, it says by how much and what would fix it.
- **Day**: the hour-by-hour plan and why each block is where it is. **Is today heavy?** — say how
  heavy, take the suggested share of the day (deadlines stay first), or make it a rest day.
- **Log**: sleep, energy hour by hour, what happened to each block, extra work, classes, leisure, and
  what kind of day it was. Each entry counts a few seconds after you log it.
- **Priorities**: compare goals two at a time.
- **Navigator**: see below.

## The Navigator

- **Start**: *Make it from my goals*, or import a graph file in its Settings.
- **At a glance**: inbox, the week, goals at risk, what needs your answer; a row of buttons jumps to
  each section.
- **Today's conversation**: a short talk about your day, the morning and evening checks (1–5), and
  *Wrap up the day*, which writes a summary to your journal and, if something should change your
  plans, a message for the Navigator that is sent only if you tap it. An evening reminder is optional.
- **Tell the Navigator**: describe what happened or ask about the graph. A quick reply comes first,
  then the crew of models prepares a package that the app measures. *Copy and return* works with your
  own chat app instead of API keys.
- **Map and paths**: where each main path stands, one path at a time with its branches, and ideas for
  paths you had not considered.
- **Inbox**: your questions, what the app raised, and everything reviewed. A package shows what it
  does to each path before anything is applied; tick the changes you want.
- **Is it too much?**: every deadline against your hours, and what could give way, the least important
  first, for you to choose.
- **Settings**: graph import, LLM providers with several keys each, a test of every key, the monthly
  budget, the crew, automatic checks (off by default in this edition).

## Privacy

Data stays on the phone. API keys are encrypted with a key in the Android Keystore. Only a summary
goes to a model when you ask for one; never the raw log.

## If something does not work

Models silent: run *Test every key* in the LLM settings; a network or location error may need a VPN.
The planner locked: look for red dots in Setup. Please report problems with a short description and,
if you can, a screenshot.

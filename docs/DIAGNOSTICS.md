# When the game fails on the phone

There is no Android device or GPU in the build environment, so the shaders and the whole GL path are
compiled for the first time on the phone this is installed on, and the phone is the only place that can
say a driver disagreed. Three installs have found bugs that no desktop test could. This file is what to do
when the fourth does.

## What the app does by itself

**It prints what it is doing, on the screen, while it does it.** The launch runs as stages -- GL strings,
crash record, manifest, bundles, map raster, game screen -- and each one is named in the panel as it
starts. If a stage fails, the panel keeps the failure, with the stack frames and, when the failure came
from the driver, the driver's own words.

**It records what killed it, outside itself.** A crash journal is installed by `AndroidLauncher` before
libGDX is created, so it is in place even if the game never starts. It writes two files in the app's
private storage (`/data/data/ai.arena.qatar.peninsula/files/`):

| File | What it holds |
|---|---|
| `last-crash.txt` | The throwable, its causes and up to 24 stack frames, plus the last launch stage reached |
| `boot-trace.txt` | One line per launch stage: the time, the stage, and the heap in use at that moment |

The trace is the more useful of the two when the app is **killed** rather than crashed: a phone kills an
app that grows too large without an exception, so there is no throwable and no stack trace, and the last
breadcrumb with its heap number is the whole diagnosis.

**It shows the previous run's record before starting.** The next launch opens with a scrollable,
selectable dialog. `Start anyway` runs the game, `Clear record and start` deletes the record first, and
`Copy` puts the whole text on the clipboard so it can be pasted into a message. The record is never
deleted on its own -- a crash that repeats stays on the screen for as long as it takes to read it, which
is what the previous recorder was changed to do after the second round got it wrong: it printed the trace
into a panel that dies with the process, then deleted the file.

## What to do when it closes

1. Open the app again. If it closed, the dialog will have the reason from the previous run.
2. Press **Copy** and paste the text into the message. That is the whole diagnosis: throwable, frames and
   the last stage with its heap figure.
3. If the app now starts, the panel's first line says the version -- `0.1.0-m0+diag3` for this round --
   and the same text is in `last-crash.txt` if the dialog was dismissed.

Nothing here needs a cable, a developer machine or `adb`.

## What the panel's lines mean

| Line | Meaning |
|---|---|
| `Qatar: The Peninsula 0.1.0-m0+diag3 on 1080x2400` | The build stamp and the surface size: the first thing to check that the new build is the one installed |
| `GL vendor=`, `GL renderer=`, `GL version=` | The driver: Mali, Adreno, PowerVR. Every driver bug so far has been a driver refusing a shader |
| `Manifest: …` | The compiled world was read: grid size, building and road counts |
| `Country loaded in … ms` | The bundles were verified against their manifest digests and parsed |
| `Map raster 1024x1024` | The map was painted |
| `Playing: …` | The game screen started |
| `stage … \| heap … of … MB` | A breadcrumb: which stage, and how much heap it was using. The last one before a silent close is the diagnosis |
| `chunk X,Z could not be built (it is left empty, the rest of the world carries on)` | One 500 m chunk failed to mesh. It appears as a hole in the world; everything else keeps running. This is the failure that used to kill the whole app |
| `chunk X,Z hit the vertex budget: its detail is …` | The chunk is dense enough to fill all three of its mesh parts: the largest buildings are drawn and the rest is left out. Not fatal; a sign the area is dense |
| `No ES 3.0 context` | The phone's GL is too old for the country's shaders (`#version 300 es`); only the toolchain demo will run |
| `demo terrain/water/solid: …` | The fallback scene's shader failed too, with the driver's message |
| `the diagnostics panel failed to draw and fell back to bars` | The panel itself hit an error: it now draws one coloured bar per line instead of text |

## Why the panel exists at all

The first install showed a blue screen and nothing else. The reason -- the world shader declared a
variable called `half`, which GLSL ES 3.00 reserves, so the program never linked and the next frame
dereferenced a null program -- was in `logcat`, where the person holding the phone cannot read it. A build
that fails silently on a device is a build nobody can fix, so the app now reports on itself: the panel for
what is happening, the journal for what killed it, the dialog for what happened last time.

## When the phone cannot even open the dialog

If the app closes before any window appears at all, the failure is in `AndroidLauncher.onCreate` before
libGDX: the journal is installed first, so `last-crash.txt` still exists even then. The file can be read
with `adb shell run-as ai.arena.qatar.peninsula cat files/last-crash.txt`, or by reinstalling and
launching again, because the dialog is shown before the game is started.

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
3. If the app now starts, the panel's first line says the version -- `0.1.0-m0+diag11` for this round --
   and the same text is in `last-crash.txt` if the dialog was dismissed.

Nothing here needs a cable, a developer machine or `adb`.

## How long the panel stays

The panel is a report, not furniture. It is on the screen for the whole launch, and after that it stays
while its news is recent: an error keeps it up for thirty seconds and then it gets out of the way, because
the failure is in the journal and the next launch shows it in full. A launch that fails outright -- the
world could not load -- leaves it up for good, behind the dialog that says why.

## What the panel's lines mean

| Line | Meaning |
|---|---|
| `Qatar: The Peninsula 0.1.0-m0+diag11 on 1080x2400` | The build stamp and the surface size: the first thing to check that the new build is the one installed |
| `GL vendor=`, `GL renderer=`, `GL version=` | The driver: Mali, Adreno, PowerVR. Every driver bug so far has been a driver refusing a shader |
| `Manifest: …` | The compiled world was read: grid size, building and road counts |
| `Country loaded in … ms` | The bundles were verified against their manifest digests and parsed |
| `Map raster 1024x1024` | The map was painted |
| `Playing: …` | The game screen started |
| `Subsystems: world=true controls=false map=true …` | Which parts of the game screen were built. Every subsystem is now independent: this one line says which is missing, without anybody having to read a stack trace |
| `Heap for this app: N MB (needs about 150 MB)` | How much heap the phone granted this app, printed before the country is asked to fit in it. If the country fails to load with an `OutOfMemoryError`, this is the number that explains it |
| `stage … \| heap … of … MB` | A breadcrumb: which stage, and how much heap it was using. The last one before a silent close is the diagnosis |
| `chunk X,Z could not be built (it is left empty, the rest of the world carries on)` | One 500 m chunk failed to mesh. It appears as a hole in the world; everything else keeps running. This is the failure that used to kill the whole app |
| `chunk X,Z hit the vertex budget: its detail is …` | The chunk is dense enough to fill all three of its mesh parts: the largest buildings are drawn and the rest is left out. Not fatal; a sign the area is dense |
| `No ES 3.0 context` | The phone's GL is too old for the country's shaders (`#version 300 es`); only the toolchain demo will run |
| `demo terrain/water/solid: …` | The fallback scene's shader failed too, with the driver's message |
| `the diagnostics panel failed to draw and fell back to bars` | The panel itself hit an error: it now draws one coloured bar per line instead of text |
| `chunks … rebases` | How many times the floating origin has moved since the launch. It should climb only when the player has travelled far -- a count that climbs while they stand still is the eleventh install's teleport fault, and it is printed for exactly that reason |
| `horizon 11 km, 6144 cells …` | The far horizon: the country at 300 m a cell out to 11.4 km, which is what stops the world ending in fog. `pending` means it has not been built yet; `FAILED` means it threw and the fog is hiding the edge of the streamed world instead |
| `Control failed: …` | A touch or key handler threw. The event was discarded and the game carried on; the handler is named. Before the eleventh install this class of failure closed the app, silently, because the input callbacks are outside the frame guard |

## What the alert's frames are, and why they changed

The dialog prints the exception, then up to six frames, then the last stage and the heap. The frames are
chosen by `StackTraceText`: **this game's own frames first** (`ai.arena.qatar.…`), then the framework's if
the throw never crossed our code on the way out, and the JVM's own only if there is nothing else.

That order exists because of the sixth install. A stack trace is deepest-first, and the deepest frames
belong to whoever *threw*: the dialog's six lines were six lines of `java.util.Formatter`, and the frame
that said which line of ours had asked for the wrong thing was below them, off the end of the list. The
report was perfect and the cause was invisible. A line like `(+4 more, the JDK's own, in the record)` at
the end is the count of what was left out; the full trace is still written to the journal.

## When the app closes instead of doing something

Opening the map closed the app, and the cause was not the map: it was the **teardown**. The frame guard
gives up on the game screen after two seconds of failed frames and disposes it -- and disposing touches
GL. `SpriteBatch.end()` calls `glDepthMask(...)` directly, so with no current context it throws
`NullPointerException: com.badlogic.gdx.Gdx.gl` on the GL thread, inside the very catch block that was
meant to make things better, and the process goes with it.

Two rules follow, and both are in the code now:

1. **Never touch GL from a catch block.** The teardown is queued and performed at the start of the next
   frame, inside `renderFrame`, where the frame guard covers it.
2. **Ask before using the context.** `QatarGame.glAvailable()` answers "is there a context, on this
   thread", and `GameScreen.dispose(boolean)` / `HudRenderer.dispose(boolean)` take that answer: with no
   context they leak the GL objects and release everything else. A leak at that moment is invisible; a
   crash is not.

If a report says "it closes when I ...", the question to ask first is not what the button did but what
the *failure path* did afterwards -- and whether anything in it touches GL.

## When the panel and the controls are invisible but the world is not

The seventh install showed the country and nothing else: no buttons, no minimap, no status line, and no
panel -- while the world drew perfectly. Nothing had thrown, so there was nothing to report; the code
was right and the GPU was discarding it.

The cause was GL state, and it is the reason `GlState2D` exists. The world renderer enables
`GL_CULL_FACE` and `GL_DEPTH_TEST` at the start of each frame and never turns them off. libGDX's
`SpriteBatch.begin()` sets `glDepthMask(false)` and nothing else -- depth testing and culling stay on.
And SpriteBatch's sprites are (x,y), (x,y+h), (x+w,y+h), (x+w,y): clockwise in a y-up system, against
the counter-clockwise front face the world renderer leaves behind. With culling on, **every quad the
HUD draws is a back face and is thrown away**.

So: if the world is visible and *nothing* 2D is, suspect the state, not the drawing. `HudRenderer.begin()`
and the panel's own `draw()` now call `GlState2D.beginUi()` themselves, and `tools/check_ui_state.py`
fails the build if any `SpriteBatch` is opened without it.

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

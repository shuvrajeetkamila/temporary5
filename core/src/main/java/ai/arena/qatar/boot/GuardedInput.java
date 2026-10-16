package ai.arena.qatar.boot;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;

/**
 * Wraps the game's input processor so that a tap which throws cannot close the app.
 *
 * <p>This closes a hole that was in every build of this game up to and including the tenth install, and it
 * is worth writing down exactly where the hole was, because it explains three of the reports that came back
 * from the phone -- "opening the minimap crashes it", "teleport crashes", "the app closes immediately" --
 * and it explains why the game's own crash reporter never had a word to say about any of them.
 *
 * <p>libGDX's Android backend draws a frame like this (verbatim, {@code AndroidGraphics.onDrawFrame},
 * 1.12.1):
 *
 * <pre>
 *     app.getInput().processEvents();                     // the touch and key handlers run here
 *     frameId++;
 *     app.getApplicationListener().render();              // ... and the game's frame guard is in here
 * </pre>
 *
 * <p>Only the second of those two lines is inside this game's frame guard. {@code processEvents()} is on
 * the GL thread, outside any try/catch that this game controls, and every input callback -- every tap, every
 * key press -- runs inside it. So the moment a touch handler threw, the exception escaped the frame
 * entirely: the GL thread died, and the process went with it, with the game's own "a frame failed" reporter
 * never having been reached. That is a silent close, from a tap, on the exact code path the player reported.
 *
 * <p>The fix is unavoidable and small: the handlers go behind a wrapper that catches, records, reports, and
 * swallows. An event that throws is an event that did not happen -- the controls carry on working, the
 * panel says which handler failed and why, and the crash record has the stack.
 *
 * <p>It is installed in one place ({@link #install}) so there is no processor in the game that is not behind
 * it, including the fallback demo's camera.
 */
public final class GuardedInput implements InputProcessor {

    /** Where a handled failure is announced: the game screen puts it on the panel in two languages. */
    public interface FailureSink {
        void onInputFailure(String where, Throwable throwable);
    }

    /** Failures are logged and recorded this many times; after that only the count moves. */
    private static final int MAX_REPORTS = 5;

    private static int failures;
    private static String lastFailure = "";

    private final InputProcessor delegate;
    private final FailureSink sink;

    private GuardedInput(InputProcessor delegate, FailureSink sink) {
        this.delegate = delegate;
        this.sink = sink;
    }

    /**
     * Sets the input processor to a guarded view of itself.
     *
     * <p>The delegate may be null: on a device where the controls could not be built, a tap then does
     * nothing rather than throwing.
     */
    public static void install(InputProcessor delegate, FailureSink sink) {
        Gdx.input.setInputProcessor(new GuardedInput(delegate, sink));
    }

    /** How many input events have thrown since the app started, for the diagnostics panel. */
    public static int failures() {
        return failures;
    }

    /** The last failure, in one line, or an empty string. */
    public static String lastFailure() {
        return lastFailure;
    }

    /**
     * Records an input failure without ever letting the recording be the thing that breaks the app.
     *
     * <p>Deliberately no GL here: this runs on the GL thread, potentially in the middle of a frame's state,
     * and the third and eighth installs both learned what touching GL from a failure path does. It writes
     * to the log the panel reads and to the crash record on disk, and that is all.
     */
    private void fail(String where, Throwable throwable) {
        failures++;
        lastFailure = where + ": " + throwable;
        if (failures <= MAX_REPORTS) {
            ScreenLog.GLOBAL.error("the input handler for " + where + " threw; the event was discarded and "
                    + "the game carried on", throwable);
            CrashJournal.record("input: " + where, throwable);
            if (sink != null) {
                try {
                    sink.onInputFailure(where, throwable);
                } catch (Throwable ignored) {
                    // A sink that throws is not allowed to turn a handled failure into a fatal one.
                }
            }
        } else if (failures == MAX_REPORTS + 1) {
            ScreenLog.GLOBAL.error("more input events are failing; the count is on the panel and the reason "
                    + "is in the crash record");
        }
    }

    @Override
    public boolean keyDown(int keycode) {
        try {
            return delegate != null && delegate.keyDown(keycode);
        } catch (Throwable t) {
            fail("a key press", t);
            return false;
        }
    }

    @Override
    public boolean keyUp(int keycode) {
        try {
            return delegate != null && delegate.keyUp(keycode);
        } catch (Throwable t) {
            fail("a key release", t);
            return false;
        }
    }

    @Override
    public boolean keyTyped(char character) {
        try {
            return delegate != null && delegate.keyTyped(character);
        } catch (Throwable t) {
            fail("a typed character", t);
            return false;
        }
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        try {
            return delegate != null && delegate.touchDown(screenX, screenY, pointer, button);
        } catch (Throwable t) {
            fail("a finger going down (this is the one the minimap tap uses)", t);
            return false;
        }
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        try {
            return delegate != null && delegate.touchUp(screenX, screenY, pointer, button);
        } catch (Throwable t) {
            fail("a finger coming up (this is the one a map tap uses)", t);
            return false;
        }
    }

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        try {
            return delegate != null && delegate.touchCancelled(screenX, screenY, pointer, button);
        } catch (Throwable t) {
            fail("a cancelled touch", t);
            return false;
        }
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        try {
            return delegate != null && delegate.touchDragged(screenX, screenY, pointer);
        } catch (Throwable t) {
            fail("a finger moving (a stick hold, a map pan)", t);
            return false;
        }
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        try {
            return delegate != null && delegate.mouseMoved(screenX, screenY);
        } catch (Throwable t) {
            fail("a mouse move", t);
            return false;
        }
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        try {
            return delegate != null && delegate.scrolled(amountX, amountY);
        } catch (Throwable t) {
            fail("a scroll", t);
            return false;
        }
    }
}

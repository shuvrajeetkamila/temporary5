package ai.arena.qatar.android;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import ai.arena.qatar.QatarGame;

/**
 * Android entry point. Deliberately minimal: libGDX owns the GL surface and lifecycle, and this class
 * only configures the context (GLES 3.0, no MSAA by default, landscape-friendly immersive mode) and
 * keeps the platform honest about thermal/battery reality by letting the system dim the screen.
 */
public final class AndroidLauncher extends AndroidApplication {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useGL30 = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.useImmersiveMode = true;
        config.numSamples = 0;          // FXAA in-shader; MSAA is opt-in per profile.
        config.depth = 16;
        config.stencil = 0;
        config.r = 8;
        config.g = 8;
        config.b = 8;
        config.a = 8;
        // Screen timeout stays under system control: the game never forces unattended GPU use.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View view = initializeForView(new QatarGame(), config);
        setContentView(view);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Rendering, physics scheduling and streaming all suspend with the activity; see QatarGame.
    }
}

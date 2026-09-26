package damjay.control.ghosthand;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import damjay.control.ghosthand.util.SplashChoreography;

/**
 * The launcher entry point: shows {@link SplashView} for two full taps, then hands over to
 * {@link MainActivity}.
 *
 * <h2>Why a splash activity at all</h2>
 * The animation itself is the request: a hand taps the glass, the glass ripples, and it
 * repeats. Android has no "splash screen" the way games do, so the honest way to show an
 * animation is an activity that owns the screen for a moment. It also gives the app a
 * place to do the first-launch work it will eventually want (checking the accessibility
 * service, warming the socket layer) without a blank frame first.
 *
 * <p><b>Two loops, not forever.</b> The animation repeats indefinitely by design - that is
 * what makes it feel alive - but the splash does not: it hands over after
 * {@link #LOOPS} full cycles so the loop is never cut mid-tap, which is what makes a
 * splash look unfinished. A tap on the screen skips it, because a splash that cannot be
 * dismissed is a splash people resent.
 *
 * <h2>API 19 notes</h2>
 * {@code ValueAnimator} and {@code Handler} both predate KitKat, and the crossfade uses
 * {@code View.animate()} (API 12). No AndroidX transition machinery, no
 * {@code SplashScreen} API (that arrived in API 31), and no window flags that older
 * platforms ignore.
 */
public class SplashActivity extends AppCompatActivity {

    /** How many full taps to show before handing over. */
    private static final int LOOPS = 2;

    /** Crossfade into the next screen, in milliseconds. */
    private static final long FADE_MS = 220L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SplashView splash;
    private final Runnable handOver = this::handOver;
    private boolean handedOver;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        splash = findViewById(R.id.splashAnimation);
        // Tapping anywhere skips the rest of the animation.
        splash.setOnClickListener(view -> handOver());
        splash.start();
        handler.postDelayed(handOver, LOOPS * SplashChoreography.LOOP_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop burning frames while we are not on screen. pause() and not stop(): the art
        // has to survive, or coming back from the background shows an empty animation. The
        // clock restarts from the top of the loop, which is invisible because the loop has
        // no first frame.
        if (splash != null && !handedOver) {
            splash.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (splash != null && !handedOver) {
            splash.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (splash != null) {
            splash.release();
        }
    }

    /** Fades the splash out and starts the role picker. Runs at most once. */
    private void handOver() {
        if (handedOver || isFinishing()) {
            return;
        }
        handedOver = true;
        handler.removeCallbacksAndMessages(null);
        if (splash != null) {
            splash.animate().alpha(0f).setDuration(FADE_MS)
                    .setInterpolator(new AccelerateInterpolator()).start();
        }
        handler.postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            // No animation of our own: the fade above already ended this screen, and the
            // platform's activity transition would replay it.
            overridePendingTransition(0, 0);
            finish();
        }, FADE_MS);
    }
}

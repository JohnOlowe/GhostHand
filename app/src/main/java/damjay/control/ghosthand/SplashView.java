package damjay.control.ghosthand;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import damjay.control.ghosthand.util.SplashChoreography;

/**
 * The splash animation: a hand taps a sheet of glass, the glass ripples, the hand lifts,
 * and it happens again - forever, until the activity takes it off the screen.
 *
 * <h2>Why it is drawn rather than animated from frames</h2>
 * Nothing here is a sprite sequence. The view owns its whole picture: a vertical gradient
 * for the background, a line for the glass, an ellipse or two per ripple, a reflection
 * copy of the hand, and one bitmap - the hand itself, cropped out of the design by
 * {@code design/make_assets.py}. Everything else is geometry, so it is sharp at any
 * density, costs one small PNG instead of a video, and can be tuned by changing a number
 * in {@link SplashChoreography} rather than by re-exporting art.
 *
 * <p>That split is deliberate: the timeline lives in {@code SplashChoreography}, which is
 * plain Java and unit-tested, and this class only asks it questions ({@code handOffset},
 * {@code rippleWidthFraction}, ...) once per frame. There is exactly one number to carry
 * between frames - the elapsed time - so the animation cannot drift out of step with
 * itself.
 *
 * <h2>Compatibility</h2>
 * Every call here exists on API 19 and later: {@link ValueAnimator} (API 11),
 * {@link LinearGradient} (API 1), {@code Canvas.drawPath} (API 1), {@link BitmapFactory}
 * (API 1). The splash runs on any phone the app supports, including the 4.4 one this
 * whole port was for. {@code drawable-nodpi} holds the hand, so the bitmap is decoded at
 * its own size and scaled to fit rather than being re-scaled by the resource system per
 * density.
 */
public class SplashView extends View {

    /** Height of the glass line, in dp. */
    private static final float LINE_HEIGHT_DP = 3f;
    /** Where the glass sits vertically, as a fraction of the view's height. */
    private static final float LINE_Y_FRACTION = 0.68f;
    /** Where the fingertip lands, as a fraction of the view's width. */
    private static final float CONTACT_X_FRACTION = 0.62f;
    /** How far below the glass the reflection starts, as a fraction of view height. */
    private static final float REFLECTION_GAP_FRACTION = 0.008f;
    /** How much the reflection is squashed: a reflection is never as tall as its object. */
    private static final float REFLECTION_SQUASH = 0.75f;
    /** The hand's height, as a fraction of the view's height. */
    private static final float HAND_HEIGHT_FRACTION = 0.34f;
    /** The hand never grows wider than this fraction of the view. */
    private static final float HAND_MAX_WIDTH_FRACTION = 0.70f;
    /**
     * Where the fingertip is inside the hand bitmap, as fractions of its width and height.
     *
     * <p>Not a guess: {@code design/make_assets.py} measures it when it crops the art and
     * refuses to write the file unless the fingertip really is at the bottom right. The
     * hand is drawn so *this* point lands on the glass, which is what makes the tap read
     * as a tap - anchoring by the bitmap's centre instead puts the finger through the
     * screen.
     */
    private static final float HAND_TIP_X_FRACTION = 0.995f;
    private static final float HAND_TIP_Y_FRACTION = 0.987f;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint();
    private final RectF rippleBounds = new RectF();
    private final Rect handSource = new Rect();
    private final Path linePath = new Path();

    private Bitmap hand;
    private ValueAnimator clock;
    private long elapsedMs;

    private int tealTop;
    private int tealBottom;
    private float density;

    public SplashView(Context context) {
        this(context, null);
    }

    public SplashView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        tealTop = ContextCompat.getColor(context, R.color.splash_teal_top);
        tealBottom = ContextCompat.getColor(context, R.color.splash_teal_bottom);
        linePaint.setStyle(Paint.Style.FILL);
        linePaint.setColor(ContextCompat.getColor(context, R.color.splash_glass));
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeWidth(2.5f * density);
        ripplePaint.setColor(ContextCompat.getColor(context, R.color.splash_glass));
        hand = BitmapFactory.decodeResource(getResources(), R.drawable.splash_hand);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Rebuild the background gradient for the new size. A two-stop vertical gradient
        // is what the design uses; drawing it here rather than shipping a 1080x2400 PNG
        // keeps the splash asset list at one small bitmap.
        backgroundPaint.setShader(new LinearGradient(0, 0, 0, h, tealTop, tealBottom,
                Shader.TileMode.CLAMP));
    }

    /** Starts the loop. Safe to call more than once. */
    public void start() {
        if (clock != null) {
            return;
        }
        // One ValueAnimator drives everything: it runs 0 -> 1 over one full cycle,
        // repeats forever, and each frame hands its value to onDraw as the clock. Using
        // the platform's animation clock (rather than a Handler or a sleep loop) means
        // the splash is paused and resumed with the rest of the app's animations.
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(SplashChoreography.LOOP_MS);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(null);   // linear: the choreography supplies all the easing
        clock.addUpdateListener(animation -> {
            Object value = animation.getAnimatedValue();
            elapsedMs = (long) (((Float) value) * SplashChoreography.LOOP_MS);
            invalidate();
        });
        clock.start();
    }

    /**
     * Stops the loop but keeps the art, so the animation can start again at once.
     *
     * <p>Deliberately not {@link #release()}: pausing and freeing are different things, and
     * conflating them is how a screen comes back from the background drawing glass and
     * ripples with no hand on them. (That is the bug this method was split out to fix.)
     */
    public void pause() {
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
    }

    /** Pauses the loop and frees the bitmap. Used when the view is going away for good. */
    public void release() {
        pause();
        if (hand != null) {
            hand.recycle();
            hand = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        canvas.drawRect(0, 0, w, h, backgroundPaint);

        float lineY = h * LINE_Y_FRACTION;
        float lineHeight = LINE_HEIGHT_DP * density;
        // The fingertip lands a little right of centre: the hand reaches in from the left,
        // so the contact point being off-centre is what leaves room for the arm.
        float contactX = w * CONTACT_X_FRACTION;

        if (hand != null && !hand.isRecycled()) {
            drawReflection(canvas, w, lineY);
        }
        drawGlass(canvas, w, lineY, lineHeight, contactX);
        drawRipples(canvas, w, lineY, contactX);
        drawHand(canvas, w, lineY);
    }

    /**
     * The glass line, bent by the travelling wave.
     *
     * <p>A straight line would look like a divider. The bend is tiny - about a percent of
     * the width at the fingertip - but it is what makes the sheet read as a surface with
     * something pressing on it, and it is what carries the "wave" outward as the ripples
     * fade.
     */
    private void drawGlass(Canvas canvas, int w, float lineY, float lineHeight, float contactX) {
        linePath.reset();
        linePath.moveTo(0, lineY);
        float amplitude = SplashChoreography.WAVE_AMPLITUDE_FRACTION * w;
        int steps = 64;
        for (int i = 0; i <= steps; i++) {
            float x = w * (i / (float) steps);
            float distance = Math.abs(x - contactX) / w;
            float bend = amplitude * SplashChoreography.travellingWave(distance, elapsedMs, 0f);
            linePath.lineTo(x, lineY + bend + lineHeight * 0.5f);
        }
        linePath.lineTo(w, lineY + lineHeight);
        linePath.lineTo(0, lineY + lineHeight);
        linePath.close();
        canvas.drawPath(linePath, linePaint);
    }

    /**
     * The hand, mirrored and squashed below the glass.
     *
     * <p>The reflection is the hand bitmap drawn again with a flip and a squash, at
     * whatever alpha the choreography says - so it fades in as the hand comes down and
     * disappears as it lifts. Flipping the same bitmap is why the art only has to exist
     * once.
     */
    private void drawReflection(Canvas canvas, int w, float lineY) {
        float alpha = SplashChoreography.reflectionAlpha(elapsedMs);
        if (alpha <= 0.01f) {
            return;
        }
        handPaint.setAlpha((int) (alpha * 255));
        int save = canvas.save();
        // Mirror about a line just below the glass: a point drawn at y ends up at
        // 2 * mirror - squash * y. Drawing the hand in its own coordinates inside this
        // transform is all it takes; the arithmetic is in the sweep below, not in offsets
        // scattered through the drawing code.
        float mirror = lineY + getHeight() * REFLECTION_GAP_FRACTION;
        canvas.translate(0f, 2f * mirror);
        canvas.scale(1f, -REFLECTION_SQUASH);
        drawHandAt(canvas, w, lineY, handPaint);
        canvas.restoreToCount(save);
        handPaint.setAlpha(255);
    }

    /** Ripples, as flat ellipses lying on the glass. */
    private void drawRipples(Canvas canvas, int w, float lineY, float contactX) {
        for (int i = 0; i < SplashChoreography.RIPPLE_COUNT; i++) {
            float alpha = SplashChoreography.rippleAlpha(elapsedMs, i);
            if (alpha <= 0.005f) {
                continue;
            }
            float halfWidth = SplashChoreography.rippleWidthFraction(elapsedMs, i) * w * 0.5f;
            float halfHeight = halfWidth * SplashChoreography.RIPPLE_FLATTEN;
            // The ripple leans on the glass, so it is drawn around the point of contact
            // rather than centred on the screen: the newest (smallest) one sits right
            // under the fingertip.
            rippleBounds.set(contactX - halfWidth, lineY - halfHeight,
                    contactX + halfWidth, lineY + halfHeight);
            ripplePaint.setAlpha((int) (alpha * 255));
            canvas.drawOval(rippleBounds, ripplePaint);
        }
    }

    /** The hand itself, positioned by the choreography's offset. */
    private void drawHand(Canvas canvas, int w, float lineY) {
        handPaint.setAlpha(255);
        drawHandAt(canvas, w, lineY, handPaint);
    }

    /**
     * Draws the hand so that its fingertip sits exactly where the choreography says.
     *
     * <p>The hand is sized as a fraction of the view but capped by width, so a tall phone
     * gets a large hand and a short one is not overrun - and then positioned by its
     * fingertip ({@link #HAND_TIP_X_FRACTION}), not by its corner. The vertical offset from
     * {@link SplashChoreography#handOffset} is in view-height units, which keeps the press
     * dip proportionate on every screen.
     */
    private void drawHandAt(Canvas canvas, int w, float lineY, Paint paint) {
        if (hand == null || hand.isRecycled()) {
            return;
        }
        float aspect = hand.getWidth() / (float) hand.getHeight();
        float drawHeight = getHeight() * HAND_HEIGHT_FRACTION;
        float drawWidth = drawHeight * aspect;
        float maxWidth = w * HAND_MAX_WIDTH_FRACTION;
        if (drawWidth > maxWidth) {
            drawWidth = maxWidth;
            drawHeight = drawWidth / aspect;
        }
        float contactX = w * CONTACT_X_FRACTION;
        // handOffset() is positive when the hand is *raised*, and screen Y grows
        // downwards - so a positive offset subtracts. (Getting this sign wrong is not
        // subtle: the hand taps upwards through the glass and then sinks away from it.
        // It was wrong until design/splash_preview.py rendered the loop as a filmstrip.)
        float offset = SplashChoreography.handOffset(elapsedMs) * getHeight();
        float left = contactX - HAND_TIP_X_FRACTION * drawWidth;
        float top = lineY - offset - HAND_TIP_Y_FRACTION * drawHeight;
        handSource.set(0, 0, hand.getWidth(), hand.getHeight());
        RectF where = new RectF(left, top, left + drawWidth, top + drawHeight);
        canvas.drawBitmap(hand, handSource, where, paint);
    }
}

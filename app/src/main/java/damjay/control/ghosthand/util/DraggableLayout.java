package damjay.control.ghosthand.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * A bar you can move by dragging anywhere on it, while every button inside
 * keeps working as a button.
 *
 * <p>Mechanics: {@code onInterceptTouchEvent} stays hands-off until the finger
 * travels past the system touch slop (~8 dp). Below that a tap is a tap - the
 * children see it normally; past it we intercept, the child gets a CANCEL, and
 * the rest of the gesture becomes a drag of the whole bar. Translation (not
 * layout params) so nothing re-layouts while a finger is down.
 *
 * <p>The view keeps its base layout position from {@code layout_gravity}; the
 * drag offset is applied on top and clamped so the bar can never leave its
 * parent. Callers persist/clamp across screen changes through
 * {@link Listener#onDragSettled()} and {@link #applyPosition(float, float)}.
 */
public class DraggableLayout extends LinearLayout {

    /** Reports "the user finished moving the bar" so the owner can save it. */
    public interface Listener {
        void onDragSettled();
    }

    private final int touchSlop;
    private float downRawX;
    private float downRawY;
    private float startTx;
    private float startTy;
    private boolean dragging;
    private Listener listener;

    public DraggableLayout(Context context) {
        super(context);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public DraggableLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public DraggableLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** True while the finger owns the gesture (a click will not fire). */
    public boolean isDragging() {
        return dragging;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = ev.getRawX();
                downRawY = ev.getRawY();
                startTx = getTranslationX();
                startTy = getTranslationY();
                dragging = false;
                return false; // children must see the press first
            case MotionEvent.ACTION_MOVE:
                if (!dragging && (Math.abs(ev.getRawX() - downRawX) > touchSlop
                        || Math.abs(ev.getRawY() - downRawY) > touchSlop)) {
                    dragging = true;
                    return true; // steal it: the child is cancelled
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    setTranslationX(startTx + (ev.getRawX() - downRawX));
                    setTranslationY(startTy + (ev.getRawY() - downRawY));
                    clampToParent();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (listener != null) {
                        listener.onDragSettled();
                    }
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** Keeps the bar fully inside its parent (which is the whole screen here). */
    private void clampToParent() {
        View parent = (View) getParent();
        if (parent == null) {
            return;
        }
        float minTx = -getLeft();
        float maxTx = parent.getWidth() - getLeft() - getWidth();
        float minTy = -getTop();
        float maxTy = parent.getHeight() - getTop() - getHeight();
        if (maxTx < minTx) { maxTx = minTx; }
        if (maxTy < minTy) { maxTy = minTy; }
        setTranslationX(Math.max(minTx, Math.min(maxTx, getTranslationX())));
        setTranslationY(Math.max(minTy, Math.min(maxTy, getTranslationY())));
    }

    /**
     * Restores a saved position given as fractions (0..1) of the space the bar
     * can travel. Called on every layout pass, so a rotation re-clamps the bar
     * into the new screen instead of leaving it off-canvas. NaN = never moved.
     */
    public void applyPosition(float fx, float fy) {
        View parent = (View) getParent();
        if (parent == null || Float.isNaN(fx) || Float.isNaN(fy)) {
            return;
        }
        float rangeX = parent.getWidth() - getWidth();
        float rangeY = parent.getHeight() - getHeight();
        if (rangeX <= 0 || rangeY <= 0) {
            return;
        }
        float wantX = fx * rangeX;              // wanted absolute x on screen
        float wantY = fy * rangeY;
        setTranslationX(wantX - getLeft());     // base position comes from gravity
        setTranslationY(wantY - getTop());
        clampToParent();
    }

    /** Current position as fractions of the travel range (for saving). */
    public float[] getPositionFractions() {
        View parent = (View) getParent();
        if (parent == null) {
            return new float[] { Float.NaN, Float.NaN };
        }
        float rangeX = parent.getWidth() - getWidth();
        float rangeY = parent.getHeight() - getHeight();
        float x = getLeft() + getTranslationX();
        float y = getTop() + getTranslationY();
        return new float[] {
                rangeX > 0 ? x / rangeX : 0f,
                rangeY > 0 ? y / rangeY : 0f,
        };
    }
}

package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

/**
 * #906: a vertical panel that the user can swipe away horizontally, like a snackbar. A swipe that
 * starts on a child button is taken over once it moves sideways past the touch slop, so the whole
 * panel responds; taps still reach the children. Screen-reader users get the equivalent
 * accessibility action "Dismiss", because a swipe gesture alone is not accessible.
 *
 * <p>The panel only reports the dismissal; the owner hides it and resets its state. Translation
 * and alpha are reset by {@link #resetSwipe()} before the panel is shown again.
 */
public class KeepADBSwipeDismissLayout extends LinearLayout {
    /** Called once when the user swiped the panel away or used the accessibility action. */
    public interface OnDismissListener {
        void onDismiss();
    }

    /** Share of the width a slow drag must cover to dismiss the panel. */
    static final float DISMISS_FRACTION = 0.35f;
    private static final long ANIMATION_MS = 150;

    private final int touchSlop;
    private final int minFlingVelocity;
    private OnDismissListener listener;
    private VelocityTracker velocity;
    private float downX;
    private float downY;
    private boolean swiping;
    /** Set by {@link #resetSwipe()}: the rest of the current gesture is ignored. */
    private boolean gestureCancelled;

    public KeepADBSwipeDismissLayout(Context context) {
        this(context, null);
    }

    public KeepADBSwipeDismissLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minFlingVelocity = configuration.getScaledMinimumFlingVelocity() * 4;
    }

    public void setOnDismissListener(OnDismissListener listener) {
        this.listener = listener;
    }

    /**
     * Puts the panel back in place and drops a running swipe: the pending swipe-out animation is
     * cancelled (its end action, the dismissal, then never runs) and the remaining events of the
     * current gesture are ignored. Call it before the panel shows another closed card, when it is
     * hidden, and when its activity is destroyed.
     */
    public void resetSwipe() {
        gestureCancelled = true;
        animate().cancel();
        setTranslationX(0f);
        setAlpha(1f);
        swiping = false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                swiping = false;
                gestureCancelled = false;
                trackVelocity(event, true);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (gestureCancelled) return false;
                trackVelocity(event, false);
                return startsSwipe(event);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                recycleVelocity();
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                swiping = false;
                gestureCancelled = false;
                trackVelocity(event, true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (gestureCancelled) return true;
                trackVelocity(event, false);
                if (!swiping) startsSwipe(event);
                if (swiping) {
                    float dx = event.getRawX() - downX;
                    setTranslationX(dx);
                    setAlpha(Math.max(0f, 1f - Math.abs(dx) / Math.max(1, getWidth())));
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (gestureCancelled) {
                    recycleVelocity();
                    return true;
                }
                trackVelocity(event, false);
                if (swiping) finishSwipe(event.getRawX() - downX);
                else if (Math.abs(event.getRawX() - downX) < touchSlop) performClick();
                recycleVelocity();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (swiping) settleBack();
                recycleVelocity();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setDismissable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
            dismiss();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    /** Decides from the movement so far whether this gesture is a sideways swipe. */
    private boolean startsSwipe(MotionEvent event) {
        float dx = Math.abs(event.getRawX() - downX);
        float dy = Math.abs(event.getRawY() - downY);
        if (dx > touchSlop && dx > dy) {
            swiping = true;
            // Keep a surrounding ScrollView from taking the gesture over.
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        return swiping;
    }

    private void finishSwipe(float dx) {
        float speed = 0f;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            speed = velocity.getXVelocity();
        }
        boolean far = Math.abs(dx) > getWidth() * DISMISS_FRACTION;
        boolean fling = Math.abs(speed) > minFlingVelocity && Math.signum(speed) == Math.signum(dx);
        if (far || fling) {
            float target = Math.signum(dx == 0f ? speed : dx) * getWidth();
            animate().translationX(target).alpha(0f).setDuration(ANIMATION_MS)
                    .withEndAction(this::dismiss).start();
        } else {
            settleBack();
        }
        swiping = false;
    }

    private void settleBack() {
        animate().translationX(0f).alpha(1f).setDuration(ANIMATION_MS).start();
        swiping = false;
    }

    private void dismiss() {
        if (listener != null) listener.onDismiss();
    }

    private void trackVelocity(MotionEvent event, boolean restart) {
        if (restart || velocity == null) {
            recycleVelocity();
            velocity = VelocityTracker.obtain();
        }
        // Screen coordinates: the panel follows the finger, so its local x barely changes.
        MotionEvent screen = MotionEvent.obtain(event);
        screen.setLocation(event.getRawX(), event.getRawY());
        velocity.addMovement(screen);
        screen.recycle();
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }
}

package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;

/**
 * One step of the setup assistant (#761). The assistant ({@link OnboardingActivity}) owns the
 * header, the navigation and the single-step mode; a step owns its content and what it may write.
 * A further step (permissions and trusted Wi-Fi in #767, webhook in #768) is one more subclass and
 * one more line in {@link OnboardingActivity#buildSteps}.
 *
 * <p>The contract that carries the whole assistant: {@link #commit} writes only what the user
 * changed (see {@link KeepADBOnboarding}), so leaving a step with "Next" and no input is a no-op.
 */
abstract class OnboardingStep {
    final KeepADBOnboarding.Step id;
    final int titleRes;
    final int questionRes;

    OnboardingStep(KeepADBOnboarding.Step id, int titleRes, int questionRes) {
        this.id = id;
        this.titleRes = titleRes;
        this.questionRes = questionRes;
    }

    /**
     * Fills {@code content} from the stored settings (or, after a rotation, from the selection
     * handed to {@link #restoreState}). Called each time the step is shown.
     */
    abstract void build(Activity host, ViewGroup content);

    /** Writes the user's choice, if it differs from what {@link #build} loaded. Never throws on "no input". */
    abstract void commit(Context context);

    /** The stored value in words, for the closing summary. */
    abstract String summary(Context context);

    /** A step with an action (permissions, webhook) may offer "Skip"; a pure choice never does. */
    boolean hasSkip() {
        return false;
    }

    /** The assistant came back to the front (a step that opens a system page re-reads its state here). */
    void onResume(Context context) {}

    /** The pending, not yet committed selection, so a rotation does not lose it. */
    void saveState(Bundle out) {}

    void restoreState(Bundle in) {}
}

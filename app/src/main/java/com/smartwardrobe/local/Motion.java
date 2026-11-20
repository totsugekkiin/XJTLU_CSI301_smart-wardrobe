package com.smartwardrobe.local;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.animation.PathInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

final class Motion {
    private Motion() {}

    static boolean enabled() { return ValueAnimator.areAnimatorsEnabled(); }

    static void press(View view) {
        view.setOnTouchListener((target, event) -> {
            pressState(target, event);
            return false;
        });
    }

    static void pressState(View target, MotionEvent event) {
        if (!enabled()) { target.setScaleX(1); target.setScaleY(1); return; }
        if (!target.isEnabled()) return;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            target.animate().cancel();
            target.animate().scaleX(.97f).scaleY(.97f).setDuration(100)
                        .setInterpolator(new PathInterpolator(.215f, .61f, .355f, 1)).start();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            target.animate().cancel();
            target.animate().scaleX(1).scaleY(1).setDuration(160)
                        .setInterpolator(new PathInterpolator(.215f, .61f, .355f, 1)).start();
        }
    }

    static void rise(View view) {
        view.animate().cancel();
        if (!enabled()) { view.setAlpha(1); view.setTranslationY(0); return; }
        view.setAlpha(0);
        view.setTranslationY(8 * view.getResources().getDisplayMetrics().density);
        view.animate().alpha(1).translationY(0).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    static void crossfade(ImageView view, Bitmap bitmap) {
        Drawable before = view.getDrawable();
        if (before instanceof TransitionDrawable transition)
            before = transition.getDrawable(transition.getNumberOfLayers() - 1);
        if (before instanceof BitmapDrawable image && image.getBitmap() == bitmap) return;
        if (before == null || bitmap == null || !enabled()) { view.setImageBitmap(bitmap); return; }
        TransitionDrawable transition = new TransitionDrawable(new Drawable[]{before,
                new BitmapDrawable(view.getResources(), bitmap)});
        transition.setCrossFadeEnabled(true);
        view.setImageDrawable(transition);
        transition.startTransition(200);
    }

    static void favorite(View view, Runnable finished) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (!enabled()) { view.setScaleX(1); view.setScaleY(1); finished.run(); return; }
        view.setEnabled(false);
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f, 1f));
        pulse.setDuration(280);
        pulse.setInterpolator(new PathInterpolator(.33f, 1f, .68f, 1f));
        pulse.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                view.setScaleX(1);
                view.setScaleY(1);
                view.setEnabled(true);
                finished.run();
            }
        });
        pulse.start();
    }

    static void fadeTab(View view) {
        view.animate().cancel();
        view.setTranslationY(0);
        view.setAlpha(enabled() ? 0 : 1);
        if (enabled()) view.animate().alpha(1).setDuration(200)
                .setInterpolator(new PathInterpolator(.215f, .61f, .355f, 1)).start();
    }

    static void expand(View view, boolean expanded) {
        Object previous = view.getTag();
        if (previous instanceof ValueAnimator animator) animator.cancel();
        if (!enabled()) {
            view.setVisibility(expanded ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            view.setLayoutParams(params);
            return;
        }
        boolean visible = view.getVisibility() == View.VISIBLE;
        int from = visible ? view.getHeight() : 0;
        int width = ((View) view.getParent()).getWidth();
        view.setVisibility(View.VISIBLE);
        view.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int to = expanded ? view.getMeasuredHeight() : 0;
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        view.setTag(animator);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(value -> {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.height = (int) value.getAnimatedValue();
            view.setLayoutParams(params);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (view.getTag() != animation) return;
                view.setTag(null);
                view.setVisibility(expanded ? View.VISIBLE : View.GONE);
                ViewGroup.LayoutParams params = view.getLayoutParams();
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setLayoutParams(params);
            }
        });
        animator.start();
    }
}

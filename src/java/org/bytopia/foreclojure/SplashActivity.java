package org.bytopia.foreclojure;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.util.Log;

import clojure.lang.Symbol;
import clojure.lang.Var;
import clojure.lang.RT;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;

import org.bytopia.foreclojure.R;

public class SplashActivity extends Activity {

    private static boolean firstLaunch = true;
    private static boolean inProgress = false;
    private static String TAG = "Splash";

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        if (firstLaunch) {
            setupSplash();
            if (!inProgress)
                loadClojure();
            inProgress = true;
        } else {
            proceed();
        }
    }

    public void setupSplash() {
        setContentView(R.layout.splashscreen);

        TextView loading = (TextView)findViewById(R.id.splash_loading_message);
        String[] messages = getResources().getStringArray(R.array.loading_messages_array);
        int idx = (int)Math.floor(Math.random() * messages.length);
        loading.setText(messages[idx] + ", please wait...");

        ImageView eye = (ImageView)findViewById(R.id.splash_gus_eye);
        CyclicTransitionDrawable ctd = new CyclicTransitionDrawable(new Drawable[] {
                new ColorDrawable(Color.RED), new ColorDrawable(Color.GREEN)
            });
        eye.setImageDrawable(ctd);
        ctd.startTransition(500, 0);
    }

    public void proceed() {
        SharedPreferences prefs = getSharedPreferences("4clojure", Context.MODE_PRIVATE);
        String lastUser = prefs.getString("last-user", null);
        Class activity;
        try {
            if (lastUser != null) {
                activity = Class.forName("org.bytopia.foreclojure.ProblemGridActivity");
            } else {
                activity = Class.forName("org.bytopia.foreclojure.LoginActivity");
            }
            startActivity(new Intent(this, activity));
            inProgress = false;
            finish();
        } catch (Exception ex) { throw (RuntimeException)ex; }
    }

    public void loadClojure() {
        new Thread(new Runnable(){
                @Override
                public void run() {
                    Symbol CLOJURE_MAIN = Symbol.intern("neko.init");
                    Var REQUIRE = RT.var("clojure.core", "require");
                    REQUIRE.invoke(CLOJURE_MAIN);

                    Var INIT = RT.var("neko.init", "init");
                    INIT.invoke(SplashActivity.this.getApplication());

                    firstLaunch = true;
                    proceed();
                }
            }).start();
    }

    // Code by Chris Blunt from StackOverflow
    private static class CyclicTransitionDrawable extends LayerDrawable implements Drawable.Callback {

        protected enum TransitionState {
            STARTING, PAUSED, RUNNING
        }

        Drawable[] drawables;
        int currentDrawableIndex;
        int alpha = 0;
        int fromAlpha;
        int toAlpha;
        long duration;
        long startTimeMillis;
        long pauseDuration;
        TransitionState transitionStatus;

        public CyclicTransitionDrawable(Drawable[] drawables) {
            super(drawables);
            this.drawables = drawables;
        }
        public void startTransition(int durationMillis, int pauseTimeMillis) {
            fromAlpha = 0;
            toAlpha = 255;
            duration = durationMillis;
            pauseDuration = pauseTimeMillis;
            startTimeMillis = SystemClock.uptimeMillis();
            transitionStatus = TransitionState.PAUSED;
            currentDrawableIndex = 0;
            invalidateSelf();
        }
        @Override
        public void draw(Canvas canvas) {
            boolean done = true;
            switch (transitionStatus) {
            case STARTING:
                done = false;
                transitionStatus = TransitionState.RUNNING;
                break;
            case PAUSED:
                if ((SystemClock.uptimeMillis() - startTimeMillis) < pauseDuration)
                    break;
                else {
                    done = false;
                    startTimeMillis = SystemClock.uptimeMillis();
                    transitionStatus = TransitionState.RUNNING;
                }
            case RUNNING:
                break;
            }
            // Determine position within the transition cycle
            if (startTimeMillis >= 0) {
                float normalized = (float) (SystemClock.uptimeMillis() - startTimeMillis) / duration;
                done = normalized >= 1.0f;
                normalized = Math.min(normalized, 1.0f);
                alpha = (int) (fromAlpha + (toAlpha - fromAlpha) * normalized);
            }
            if (transitionStatus == TransitionState.RUNNING) {
                // Cross fade the current
                int nextDrawableIndex = 0;
                if (currentDrawableIndex + 1 < drawables.length)
                    nextDrawableIndex = currentDrawableIndex + 1;
                Drawable currentDrawable = getDrawable(currentDrawableIndex);
                Drawable nextDrawable = getDrawable(nextDrawableIndex);
                // Apply cross fade and draw the current drawable
                currentDrawable.setAlpha(255 - alpha);
                currentDrawable.draw(canvas);
                currentDrawable.setAlpha(0xFF);
                if (alpha > 0) {
                    nextDrawable.setAlpha(alpha);
                    nextDrawable.draw(canvas);
                    nextDrawable.setAlpha(0xFF);
                }
                // If we have finished, move to the next transition
                if (done) {
                    currentDrawableIndex = nextDrawableIndex;
                    startTimeMillis = SystemClock.uptimeMillis();
                    transitionStatus = TransitionState.PAUSED;
                }
            }
            else
                getDrawable(currentDrawableIndex).draw(canvas);
            invalidateSelf();
        }
    }
}

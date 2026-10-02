package com.riffbuddy.desktop.tabs;

import alphaTab.AlphaTabApiBase;
import alphaTab.Environment;
import alphaTab.Settings;
import alphaTab.platform.android.JavaThreadAlphaSynthWorker;
import alphaTab.platform.android.JavaThreadAlphaTabRendererWorker;
import alphaTab.platform.worker.AlphaSynthWebWorkerApi;
import alphaTab.platform.worker.AlphaTabWorkerScoreRenderer;
import alphaTab.rendering.IScoreRenderer;
import alphaTab.synth.IAlphaSynth;
import alphaTab.synth.ISynthOutput;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/**
 * The parts of alphaTab a desktop host needs that alphaTab marks internal to
 * its own Kotlin module: its background threads for drawing and for sound,
 * the objects that talk to them, and the screen density.
 *
 * Its code on those threads finds its message channel through these two
 * worker classes and no others, so they are what the desktop has to use too;
 * they are plain Java threads with nothing Android in them. "Internal" binds
 * Kotlin callers but not Java ones, which is why this file is Java.
 */
public final class AlphaTabWorkers {
    private AlphaTabWorkers() {
    }

    /** The synthesizer, running on its own thread, playing into [output]. */
    public static IAlphaSynth synth(ISynthOutput output, Settings settings, Function1<? super Function0<Unit>, Unit> postToMain) {
        return new AlphaSynthWebWorkerApi(output, settings, new JavaThreadAlphaSynthWorker(postToMain));
    }

    /** The score renderer, laying out and drawing on its own thread. */
    public static <T> IScoreRenderer renderer(AlphaTabApiBase<T> api, Function1<? super Function0<Unit>, Unit> postToMain) {
        return new AlphaTabWorkerScoreRenderer<>(api, new JavaThreadAlphaTabRendererWorker(postToMain));
    }

    /** Pixels per dp: alphaTab draws its images at this sharpness. */
    public static void setDensity(double density) {
        Environment.Companion.setHighDpiFactor$android_release(density);
    }

    /**
     * Queues [action] on the alphaTab thread this is called from, if it is
     * one, and says whether it did. alphaTab expects work started on one of
     * its threads to stay there.
     */
    public static boolean postIfOnWorker(Function0<Unit> action) {
        JavaThreadAlphaSynthWorker synth = JavaThreadAlphaSynthWorker.Companion.getCurrentThreadWorker();
        if (synth != null) {
            synth.postToWorker(action);
            return true;
        }
        JavaThreadAlphaTabRendererWorker renderer = JavaThreadAlphaTabRendererWorker.Companion.getCurrentThreadWorker();
        if (renderer != null) {
            renderer.postToWorker(action);
            return true;
        }
        return false;
    }
}

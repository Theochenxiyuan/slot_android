package fyi.slot.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/** Original Slot PCM clips and cue leads; GPL-3.0-or-later, see THIRD_PARTY.md.
 * Adapted from crates/slot/src/audio/sfx.rs, 2026-10-09.
 */
final class CartridgeAudio {
    enum Effect {
        INSERT(97), EJECT(21);
        final int leadMillis;
        final float leadSeconds;
        Effect(int leadMillis) { this.leadMillis = leadMillis; this.leadSeconds = leadMillis / 1000f; }
    }
    private final SoundPool pool;
    private int insertSample, ejectSample;
    private volatile boolean insertReady, ejectReady;
    private volatile int playCount, lastStream;
    private volatile Effect lastEffect;
    private int insertStream, ejectStream;
    private boolean released;

    CartridgeAudio(Context context) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build();
        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (status != 0 || released) return;
            if (sampleId == insertSample) insertReady = true;
            if (sampleId == ejectSample) ejectReady = true;
        });
        insertSample = pool.load(context, R.raw.insert, 1);
        ejectSample = pool.load(context, R.raw.eject, 1);
    }

    int play(Effect effect) {
        if (released || !(effect == Effect.INSERT ? insertReady : ejectReady)) return 0;
        int stream = pool.play(effect == Effect.INSERT ? insertSample : ejectSample, 1, 1, 0, 0, 1);
        if (stream > 0) {
            if (effect == Effect.INSERT) insertStream = stream; else ejectStream = stream;
            lastEffect = effect; lastStream = stream; playCount++;
        }
        return stream;
    }

    void stop(Effect effect) {
        if (released) return;
        if (effect == Effect.INSERT) { pool.stop(insertStream); insertStream = 0; }
        else { pool.stop(ejectStream); ejectStream = 0; }
    }

    void stopAll() { stop(Effect.INSERT); stop(Effect.EJECT); }

    void release() {
        if (released) return;
        stopAll(); released = true; pool.release();
    }

    boolean ready() { return insertReady && ejectReady && !released; }
    int playCount() { return playCount; }
    int lastStream() { return lastStream; }
    Effect lastEffect() { return lastEffect; }
}

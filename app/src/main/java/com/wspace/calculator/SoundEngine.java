package com.wspace.calculator;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays the uploaded sounds through static AudioTracks (the PCM is decoded once, so playback starts
 * instantly and the playhead can drive animations):
 *  - key presses: short bursts cut from T R R R and re-pitched (digit0-3, op0-1, equals)
 *  - "trrr": the floating-character scene     - "kill": digit massacre and the final split
 *  - "s106": the special 106 sound
 * The looping background music and the error buzzer are still synthesised.
 */
final class SoundEngine {

    private static final String[] WAV_NAMES = {
            "digit0", "digit1", "digit2", "digit3", "op0", "op1", "equals", "trrr", "kill", "s106"};
    private static final int[] WAV_RES = {
            R.raw.key_digit0, R.raw.key_digit1, R.raw.key_digit2, R.raw.key_digit3,
            R.raw.key_op0, R.raw.key_op1, R.raw.key_equals,
            R.raw.sfx_trrr, R.raw.sfx_kill, R.raw.sfx_106};

    private final Context ctx;
    private final Map<String, AudioTrack> sfx = new HashMap<>();
    private AudioTrack music;

    private volatile boolean ready = false;
    private boolean muted = false;
    private boolean paused = false;
    private float musicVol = 0.35f;
    private float sfxVol = 1.0f;
    private float duck = 1f;

    SoundEngine(Context context) {
        ctx = context.getApplicationContext();
    }

    /** Builds everything on a background thread so start-up stays instant. */
    void start() {
        Thread t = new Thread(this::build, "wspace-audio");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void build() {
        Map<String, AudioTrack> tmp = new HashMap<>();
        // each sound is loaded on its own: one broken file must not silence the others
        for (int i = 0; i < WAV_NAMES.length; i++) {
            try (InputStream in = ctx.getResources().openRawResource(WAV_RES[i])) {
                WavData w = WavData.read(in);
                tmp.put(WAV_NAMES[i], track(w.pcm, w.rate, false));
            } catch (Throwable ignored) {
                // audio is optional: the calculator keeps working without it
            }
        }
        try {
            tmp.put("error", track(Synth.toPcm(Synth.error()), Synth.RATE, false));
        } catch (Throwable ignored) { }
        AudioTrack m = null;
        try {
            m = track(Synth.toPcm(Synth.music()), Synth.RATE, true);
        } catch (Throwable ignored) { }
        synchronized (this) {
            sfx.putAll(tmp);
            music = m;
            ready = true;
            applyVolumes();
            if (!paused && music != null) {
                try { music.play(); } catch (Exception ignored) { }
            }
        }
    }

    private static AudioTrack track(short[] pcm, int rate, boolean loop) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(pcm.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(pcm, 0, pcm.length);
        if (loop) t.setLoopPoints(0, pcm.length, -1);
        return t;
    }

    private void applyVolumes() {
        if (!ready) return;
        try {
            if (music != null) music.setVolume(muted ? 0f : musicVol * duck);
            for (AudioTrack t : sfx.values()) t.setVolume(muted ? 0f : sfxVol);
        } catch (Exception ignored) { }
    }

    private int variant = 0;

    /** Key sounds: "click" (digits and '.'), "op" and "func" (operators, %, C, B, +/-), "equals", "error". */
    synchronized void play(String name) {
        if (!ready || muted || sfxVol < 0.01f) return;
        if (name.equals("click")) {
            variant = (variant + 1 + (int) (Math.random() * 3)) % 4;
            name = "digit" + variant;
        } else if (name.equals("op") || name.equals("func")) {
            name = "op" + (int) (Math.random() * 2);
        }
        restart(sfx.get(name));
    }

    /**
     * Scene sounds ("trrr", "kill", "s106"). They always start, even while muted (the volume is 0
     * then), so the audio playhead keeps driving the animation.
     */
    synchronized boolean playScene(String name) {
        if (!ready) return false;
        AudioTrack t = sfx.get(name);
        if (t == null) return false;
        return restart(t);
    }

    private static boolean restart(AudioTrack t) {
        if (t == null) return false;
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) t.stop();
            t.reloadStaticData();
            t.play();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    synchronized void stop(String name) {
        AudioTrack t = sfx.get(name);
        if (t == null) return;
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) t.stop();
        } catch (Exception ignored) { }
    }

    synchronized void stopAllScenes() {
        stop("trrr");
        stop("kill");
        stop("s106");
    }

    /** Seconds the named sound has played, or -1 when it is not playing (or audio is unavailable). */
    synchronized float playheadSec(String name) {
        AudioTrack t = sfx.get(name);
        if (!ready || t == null) return -1f;
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) return -1f;
            return (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) / (float) t.getSampleRate();
        } catch (Exception e) {
            return -1f;
        }
    }

    AudioClock clock(String name) { return () -> playheadSec(name); }

    synchronized void setMuted(boolean m) { muted = m; applyVolumes(); }

    synchronized boolean isMuted() { return muted; }

    synchronized void setMusicVolume(float v) { musicVol = v; applyVolumes(); }

    synchronized void setSfxVolume(float v) { sfxVol = v; applyVolumes(); }

    synchronized float getMusicVolume() { return musicVol; }

    synchronized float getSfxVolume() { return sfxVol; }

    /** Lowers the music while a big scene plays. */
    synchronized void duckMusic(boolean on) { duck = on ? 0.3f : 1f; applyVolumes(); }

    synchronized void pause() {
        paused = true;
        if (!ready) return;
        try { if (music != null) music.pause(); } catch (Exception ignored) { }
        stopAllScenes();
    }

    synchronized void resume() {
        paused = false;
        if (!ready) return;
        try { if (music != null) music.play(); } catch (Exception ignored) { }
    }

    synchronized void release() {
        ready = false;
        try {
            if (music != null) { music.stop(); music.release(); }
            for (AudioTrack t : sfx.values()) { t.stop(); t.release(); }
        } catch (Exception ignored) { }
        sfx.clear();
    }
}

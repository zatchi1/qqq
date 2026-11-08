package com.wspace.calculator;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.HashMap;
import java.util.Map;

/** Plays the synthesised effects and the looping bass-heavy music through static AudioTracks. */
final class SoundEngine {

    private final Map<String, AudioTrack> sfx = new HashMap<>();
    private final Map<String, Float> gains = new HashMap<>();
    private AudioTrack music;

    private volatile boolean ready = false;
    private boolean muted = false;
    private boolean paused = false;
    private float musicVol = 0.35f;
    private float sfxVol = 1.0f;
    private float duck = 1f;

    /** Builds everything on a background thread so start-up stays instant. */
    void start() {
        Thread t = new Thread(this::build, "wspace-synth");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void build() {
        try {
            Map<String, AudioTrack> tmp = new HashMap<>();
            for (int i = 0; i < 3; i++) tmp.put("click" + i, track(Synth.click(i), false));
            for (int i = 0; i < 2; i++) tmp.put("op" + i, track(Synth.operator(i), false));
            tmp.put("func", track(Synth.func(), false));
            tmp.put("equals", track(Synth.equals(), false));
            tmp.put("error", track(Synth.error(), false));
            tmp.put("slice", track(Synth.slice(), false));
            tmp.put("elim", track(Synth.eliminate(), false));
            AudioTrack m = track(Synth.music(), true);
            synchronized (this) {
                sfx.putAll(tmp);
                for (String n : tmp.keySet()) gains.put(n, 1f);
                music = m;
                ready = true;
                applyVolumes();
                if (!paused) {
                    try { music.play(); } catch (Exception ignored) { }
                }
            }
        } catch (Throwable ignored) {
            // audio is optional: the calculator keeps working without it
        }
    }

    private static AudioTrack track(float[] samples, boolean loop) {
        short[] pcm = Synth.toPcm(samples);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Synth.RATE)
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
            music.setVolume(muted ? 0f : musicVol * duck);
            for (Map.Entry<String, AudioTrack> e : sfx.entrySet()) {
                Float g = gains.get(e.getKey());
                e.getValue().setVolume(muted ? 0f : sfxVol * (g == null ? 1f : g));
            }
        } catch (Exception ignored) { }
    }

    private int variant = 0;

    /** "click" and "op" pick one of several recorded-style variants, round robin. */
    synchronized void play(String name) {
        if (!ready || muted || sfxVol < 0.01f) return;
        if (name.equals("click")) {
            variant = (variant + 1 + (int) (Math.random() * 2)) % 3;
            name = "click" + variant;
        } else if (name.equals("op")) {
            name = "op" + (int) (Math.random() * 2);
        }
        AudioTrack t = sfx.get(name);
        if (t == null) return;
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) t.stop();
            t.reloadStaticData();
            t.play();
        } catch (Exception ignored) { }
    }

    synchronized void setMuted(boolean m) { muted = m; applyVolumes(); }

    synchronized boolean isMuted() { return muted; }

    synchronized void setMusicVolume(float v) { musicVol = v; applyVolumes(); }

    synchronized void setSfxVolume(float v) { sfxVol = v; applyVolumes(); }

    synchronized float getMusicVolume() { return musicVol; }

    synchronized float getSfxVolume() { return sfxVol; }

    /** Lowers the music while a big scene plays. */
    synchronized void duckMusic(boolean on) { duck = on ? 0.35f : 1f; applyVolumes(); }

    synchronized void pause() {
        paused = true;
        if (!ready) return;
        try { music.pause(); } catch (Exception ignored) { }
    }

    synchronized void resume() {
        paused = false;
        if (!ready) return;
        try { music.play(); } catch (Exception ignored) { }
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

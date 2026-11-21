package com.minigame.fpt;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

/** Chạy vòng lặp giả lập trên 1 thread riêng, âm thanh phát bằng AudioTrack (ghi chặn = đồng bộ tốc độ). */
public class EmuController {
    public interface Listener {
        void onLoaded();

        void onError(String message);
    }

    private final Object lock = new Object();
    private Thread thread;
    private volatile boolean running = false;
    private boolean paused = false; // bảo vệ bởi lock
    private volatile String currentPath;

    public boolean hasGame() {
        return running && thread != null;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public void start(final String core, final String game, final String sys, final String save,
                      final Listener listener) {
        stop();
        currentPath = game;
        running = true;
        synchronized (lock) {
            paused = false;
        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(core, game, sys, save, listener);
            }
        }, "emu-thread");
        thread.start();
    }

    public void stop() {
        running = false;
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
        Thread t = thread;
        if (t != null) {
            try {
                t.join(8000);
            } catch (InterruptedException ignored) {
            }
        }
        thread = null;
        currentPath = null;
    }

    public void pause() {
        synchronized (lock) {
            paused = true;
        }
    }

    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    private void loop(String core, String game, String sys, String save, Listener l) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        String err = Native.nativeLoad(core, game, sys, save);
        if (err != null) {
            running = false;
            l.onError(err);
            return;
        }

        double fps = Native.nativeGetFps();
        if (fps < 20 || fps > 120) fps = 60;
        int rate = (int) Math.round(Native.nativeGetSampleRate());
        if (rate < 8000) rate = 48000;

        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf, (rate / 8) * 4);
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufBytes,
                AudioTrack.MODE_STREAM);
        track.play();
        l.onLoaded();

        short[] audio = new short[16384];
        long period = (long) (1000000000.0 / fps);
        boolean audioPaused = false;

        while (running) {
            boolean isPaused;
            synchronized (lock) {
                isPaused = paused;
            }
            if (isPaused) {
                if (!audioPaused) {
                    track.pause();
                    track.flush();
                    Native.nativeSaveSram();
                    audioPaused = true;
                }
                synchronized (lock) {
                    while (paused && running) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            running = false;
                        }
                    }
                }
                continue;
            }
            if (audioPaused) {
                track.play();
                audioPaused = false;
            }

            long t0 = System.nanoTime();
            int n = Native.nativeRun(audio);
            if (n < 0) break;
            if (n > 0) {
                track.write(audio, 0, n);
            } else {
                long remain = period - (System.nanoTime() - t0);
                if (remain > 0) {
                    try {
                        Thread.sleep(remain / 1000000L, (int) (remain % 1000000L));
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        Native.nativeUnload();
        try {
            track.stop();
        } catch (IllegalStateException ignored) {
        }
        track.release();
        running = false;
    }
}

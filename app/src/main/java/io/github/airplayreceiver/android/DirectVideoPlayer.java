package io.github.airplayreceiver.android;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;

import io.github.airplayreceiver.protocol.VideoPlayback;

/** Owns an HTTP video session and its player on one thread. */
final class DirectVideoPlayer {
    interface Listener {
        void onSize(VideoPlayback video, int width, int height);
        void onEnded(VideoPlayback video, boolean failed);
    }

    private static final int MAX_RETRIES = 3;
    private static final int PREPARE_TIMEOUT_MS = 10000;
    private final HandlerThread thread = new HandlerThread("AirPlayDirectVideo");
    private final Handler handler;
    private final Listener listener;
    private final Runnable retry = this::prepare;
    private final Runnable timeout = this::retryVideo;
    private final Runnable tick = this::sample;

    private Surface surface;
    private VideoPlayback video;
    private MediaPlayer player;
    private boolean ready;
    private boolean buffering;
    private boolean play = true;
    private long savedPosition = -1;
    private int attempts;
    private int previousFrames = -1;
    private long previousSample;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    DirectVideoPlayer(Listener listener) {
        this.listener = listener;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    void start(VideoPlayback request) {
        handler.post(() -> {
            clear();
            video = request;
            play = true;
            savedPosition = -1;
            attempts = 0;
            prepare();
        });
    }

    void stop(VideoPlayback request) {
        handler.post(() -> {
            if (video == request) clear();
        });
    }

    void release() {
        handler.post(() -> {
            clear();
            thread.quitSafely();
        });
    }

    void setSurface(Surface next) {
        handler.post(() -> {
            surface = next;
            if (player != null) player.setSurface(next);
        });
    }

    void setPlaying(VideoPlayback request, boolean playing) {
        handler.post(() -> {
            if (video != request) return;
            play = playing;
            applyRate();
        });
    }

    void toggle(VideoPlayback request) {
        handler.post(() -> {
            if (video != request) return;
            play = !play;
            applyRate();
        });
    }

    void seek(VideoPlayback request, long positionMs, boolean relative) {
        handler.post(() -> {
            if (video != request) return;
            if (relative) {
                if (!ready || player.getDuration() <= 0) return;
                position(player.getCurrentPosition() + positionMs);
            } else {
                position(positionMs);
            }
        });
    }

    Snapshot snapshot() {
        return snapshot;
    }

    private void prepare() {
        if (video == null) return;
        MediaPlayer next = new MediaPlayer();
        player = next;
        buffering = true;
        sample();
        next.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        next.setOnPreparedListener(loaded -> {
            if (player != loaded) return;
            handler.removeCallbacks(timeout);
            ready = true;
            buffering = false;
            long start = savedPosition >= 0 ? savedPosition
                    : Math.round(video.start * (video.startIsSeconds ? 1000 : Math.max(0, loaded.getDuration())));
            if (start > 0) position(start);
            applyRate();
            listener.onSize(video, loaded.getVideoWidth(), loaded.getVideoHeight());
        });
        next.setOnVideoSizeChangedListener((loaded, width, height) -> {
            if (player == loaded && width > 0 && height > 0) listener.onSize(video, width, height);
        });
        next.setOnInfoListener((loaded, code, extra) -> {
            if (player != loaded) return false;
            if (code == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true;
            else if (code == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                buffering = false;
                if (ready) attempts = 0;
            } else return false;
            sample();
            return true;
        });
        next.setOnCompletionListener(completed -> {
            if (player == completed) end(false);
        });
        next.setOnErrorListener((failed, what, extra) -> {
            if (player != failed) return true;
            if (extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT
                    || what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) retryVideo();
            else end(true);
            return true;
        });
        try {
            next.setDataSource(video.url);
            next.setSurface(surface);
            next.prepareAsync();
            handler.postDelayed(timeout, PREPARE_TIMEOUT_MS);
        } catch (IOException brokenConnection) {
            retryVideo();
        } catch (IllegalArgumentException | SecurityException unsupportedSource) {
            end(true);
        }
    }

    private void applyRate() {
        if (ready) {
            if (play) player.start();
            else if (player.isPlaying()) player.pause();
        }
        sample();
    }

    private void position(long requested) {
        savedPosition = Math.max(0, requested);
        if (!ready) return;
        int duration = player.getDuration();
        if (duration > 0) {
            int target = (int) Math.min(savedPosition, Math.max(0, duration - 1));
            if (Build.VERSION.SDK_INT >= 26) player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            else player.seekTo(target);
        }
        sample();
    }

    private void sample() {
        handler.removeCallbacks(tick);
        if (video == null) return;
        long position = ready ? player.getCurrentPosition() : Math.max(savedPosition, 0);
        long duration = ready ? Math.max(player.getDuration(), 0) : snapshot.state.durationMs;
        if (ready && position > 0) savedPosition = position;
        float fps = -1;
        int drops = -1;
        if (ready && Build.VERSION.SDK_INT >= 26) {
            PersistableBundle metrics = player.getMetrics();
            if (metrics != null) {
                int frames = metrics.getInt(MediaPlayer.MetricsConstants.FRAMES, -1);
                drops = metrics.getInt(MediaPlayer.MetricsConstants.FRAMES_DROPPED, -1);
                long now = SystemClock.elapsedRealtime();
                if (previousFrames >= 0 && frames >= previousFrames && now > previousSample) {
                    fps = (frames - previousFrames) * 1000f / (now - previousSample);
                }
                previousFrames = frames;
                previousSample = now;
            }
        }
        snapshot = new Snapshot(video, new VideoPlayback.State(ready, ready && play, buffering, position, duration),
                ready ? player.getVideoWidth() : 0, ready ? player.getVideoHeight() : 0, fps, drops);
        handler.postDelayed(tick, 750);
    }

    private void retryVideo() {
        if (video == null) return;
        closePlayer();
        if (++attempts > MAX_RETRIES) {
            end(true);
            return;
        }
        buffering = true;
        sample();
        handler.postDelayed(retry, 700L * attempts);
    }

    private void end(boolean failed) {
        VideoPlayback ended = video;
        clear();
        listener.onEnded(ended, failed);
    }

    private void clear() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(tick);
        closePlayer();
        video = null;
        buffering = false;
        snapshot = Snapshot.EMPTY;
    }

    private void closePlayer() {
        handler.removeCallbacks(timeout);
        if (player != null) {
            player.release();
            player = null;
        }
        ready = false;
        previousFrames = -1;
    }

    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(null, VideoPlayback.State.IDLE, 0, 0, -1, -1);
        final VideoPlayback video;
        final VideoPlayback.State state;
        final int width;
        final int height;
        final float fps;
        final int drops;

        Snapshot(VideoPlayback video, VideoPlayback.State state, int width, int height, float fps, int drops) {
            this.video = video;
            this.state = state;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.drops = drops;
        }
    }
}

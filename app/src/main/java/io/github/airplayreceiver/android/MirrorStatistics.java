package io.github.airplayreceiver.android;

/** A one-second window of incoming encoded video, independent of display refresh. */
final class MirrorStatistics {
    private long window;
    private int frames;
    private long bytes;
    private long lastFrame;
    private float fps;
    private float megabits;

    synchronized void reset() {
        window = System.nanoTime();
        frames = 0;
        bytes = 0;
        lastFrame = 0;
        fps = 0;
        megabits = 0;
    }

    synchronized void frame(int size) {
        long now = System.nanoTime();
        if (window == 0 || now - lastFrame > 2_000_000_000L) {
            window = now;
            frames = 0;
            bytes = 0;
        }
        frames++;
        bytes += size;
        lastFrame = now;
        long elapsed = now - window;
        if (elapsed >= 1_000_000_000L) {
            fps = frames * 1_000_000_000f / elapsed;
            megabits = bytes * 8000f / elapsed;
            window = now;
            frames = 0;
            bytes = 0;
        }
    }

    synchronized Sample current() {
        if (System.nanoTime() - lastFrame > 2_000_000_000L) return new Sample(0, 0);
        return new Sample(fps, megabits);
    }

    static final class Sample {
        final float fps;
        final float megabits;

        Sample(float fps, float megabits) {
            this.fps = fps;
            this.megabits = megabits;
        }
    }
}

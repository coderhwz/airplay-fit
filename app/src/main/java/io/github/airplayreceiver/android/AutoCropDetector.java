package io.github.airplayreceiver.android;

import java.nio.ByteBuffer;

/** Finds stable dark borders while tolerating moving, sparse overlays in the preview. */
final class AutoCropDetector {
    static final int WIDTH = 192;
    static final int HEIGHT = 108;
    private static final int DARK = 18;
    private static final int MINIMUM_BORDER = 2;
    private static final int LOCK_AFTER_MS = 3000;
    private static final int MAX_SAMPLE_GAP_MS = 1000;
    private static final int SAMPLE_COUNT = 11;
    private static final int VOTES_REQUIRED = 7;
    private static final int UNKNOWN = -1;
    private static final int MIN_ROW_RUN = 5;
    private static final int MIN_COLUMN_RUN = 6;

    private final byte[] preview = new byte[WIDTH * HEIGHT];
    private final int[] rows = new int[HEIGHT];
    private final int[] rowChanges = new int[HEIGHT];
    private final int[] rowPicture = new int[HEIGHT];
    private final int[] columnPicture = new int[WIDTH];
    private final int[][] history = new int[SAMPLE_COUNT][4];
    private final long[] sampleTimes = new long[SAMPLE_COUNT];
    private int nextSample;
    private int sampleCount;
    private long lastSample = -1;
    private MirrorFraming.Crop crop = MirrorFraming.Crop.FULL;
    private volatile boolean locked;

    MirrorFraming.Crop currentCrop() {
        return crop;
    }

    boolean isLocked() {
        return locked;
    }

    void redetect() {
        sampleCount = 0;
        lastSample = -1;
        locked = false;
    }

    void update(ByteBuffer pixels, long now) {
        if (locked) return;
        boolean continuous = lastSample >= 0 && now - lastSample <= MAX_SAMPLE_GAP_MS;
        if (!continuous) sampleCount = 0;
        lastSample = now;

        int[] next = findBorders(pixels, continuous);
        if (next == null) {
            sampleCount = 0;
            return;
        }
        history[nextSample] = next;
        sampleTimes[nextSample] = now;
        nextSample = (nextSample + 1) % SAMPLE_COUNT;
        sampleCount = Math.min(SAMPLE_COUNT, sampleCount + 1);
        if (sampleCount < SAMPLE_COUNT || now - sampleTimes[nextSample] < LOCK_AFTER_MS) return;

        int left = stableEdge(0, next[0]);
        int top = stableEdge(1, next[1]);
        int right = stableEdge(2, next[2]);
        int bottom = stableEdge(3, next[3]);
        if (left < 0 || top < 0 || right < 0 || bottom < 0) return;
        if (left + top + right + bottom == 0) {
            crop = MirrorFraming.Crop.FULL;
            return;
        }
        crop = new MirrorFraming.Crop(left / (float) WIDTH, top / (float) HEIGHT,
                1 - right / (float) WIDTH, 1 - bottom / (float) HEIGHT);
        locked = true;
    }

    private int stableEdge(int edge, int current) {
        // A dark corner or a temporary overlay can obscure one edge without invalidating the others.
        for (int[] sample : history) {
            int boundary = sample[edge];
            if (boundary < 0 || (current >= 0 && Math.abs(current - boundary) > 1)) continue;
            int votes = 0;
            for (int[] other : history) {
                if (other[edge] >= boundary && other[edge] <= boundary + 1) votes++;
            }
            if (votes >= VOTES_REQUIRED) return boundary;
        }
        return UNKNOWN;
    }

    private int[] findBorders(ByteBuffer pixels, boolean compareMotion) {
        int total = 0;
        for (int y = 0; y < HEIGHT; y++) {
            int bright = 0;
            int changed = 0;
            int run = 0;
            int picture = 0;
            int offset = (HEIGHT - 1 - y) * WIDTH * 4;
            for (int x = 0; x < WIDTH; x++, offset += 4) {
                int value = (pixels.get(offset) & 255) > DARK || (pixels.get(offset + 1) & 255) > DARK
                        || (pixels.get(offset + 2) & 255) > DARK ? 1 : 0;
                int index = y * WIDTH + x;
                boolean moved = compareMotion && preview[index] != value;
                preview[index] = (byte) value;
                bright += value;
                if (value != 0) {
                    // Connected bright runs carry more picture evidence than individual letter strokes.
                    run++;
                    if (run == MIN_ROW_RUN) picture += run;
                    else if (run > MIN_ROW_RUN) picture++;
                } else {
                    run = 0;
                }
                if (moved) changed++;
            }
            rows[y] = bright;
            rowPicture[y] = picture;
            rowChanges[y] = changed;
            total += bright;
        }
        // Dark scenes and fades cannot establish the boundary between a bar and the picture.
        if (total < WIDTH * HEIGHT / 10) return null;

        for (int x = 0; x < WIDTH; x++) {
            int run = 0;
            int picture = 0;
            for (int y = 0; y < HEIGHT; y++) {
                if (preview[y * WIDTH + x] != 0) {
                    // Scrolling text crosses side bars in thin horizontal bands.
                    run++;
                    if (run == MIN_COLUMN_RUN) picture += run;
                    else if (run > MIN_COLUMN_RUN) picture++;
                } else {
                    run = 0;
                }
            }
            columnPicture[x] = picture;
        }

        // Require stronger support than the scattered pixels left by text or compression.
        int left = findBorder(columnPicture, 0, 1, 4, 12);
        int right = findBorder(columnPicture, WIDTH - 1, -1, 4, 12);
        int top = findBorder(rowPicture, 0, 1, 5, 19);
        int bottom = findBorder(rowPicture, HEIGHT - 1, -1, 5, 19);
        return new int[] {left, protectHorizontalOverlay(top, 0, 1),
                right, protectHorizontalOverlay(bottom, HEIGHT - 1, -1)};
    }

    private int protectHorizontalOverlay(int boundary, int start, int step) {
        for (int edge = 0; edge < boundary; edge++) {
            int line = start + edge * step;
            int bright = rows[line];
            if (bright > WIDTH / 3 || (bright > WIDTH / 50 && rowChanges[line] * 2 < bright)) {
                return UNKNOWN;
            }
        }
        return boundary;
    }

    private static int findBorder(int[] lines, int start, int step, int noise, int picture) {
        int maxBorder = lines.length / 3;
        for (int edge = 0; edge < maxBorder; edge++) {
            int index = start + edge * step;
            if (lines[index] <= noise) continue;

            int strong = 0;
            int occupied = 0;
            for (int i = 0; i < 8; i++) {
                int count = lines[index + i * step];
                if (count >= picture) strong++;
                if (count > noise) occupied++;
            }
            if (strong >= 3) return edge < MINIMUM_BORDER ? 0 : edge - 1;
            if (occupied >= 6 || lines[index] >= picture) return UNKNOWN;
        }
        return UNKNOWN;
    }
}

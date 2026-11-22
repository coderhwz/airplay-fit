package io.github.airplayreceiver.android;

/** Maps a source crop to a viewport in screen pixels. The first three mode IDs are persisted by v1. */
final class MirrorFraming {
    static final int FIT = 0;
    static final int CROP_TO_16_9 = 1;
    static final int MANUAL = 2;
    static final int AUTO = 3;
    static final int FILL = 4;
    static final int STRETCH = 5;
    static final int ACTUAL_SIZE = 6;
    static final int RATIO_4_3 = 7;
    static final int RATIO_14_9 = 8;
    static final int RATIO_16_9 = 9;
    static final int NONLINEAR = 10;
    static final int ZOOM_AND_PAN = 11;
    static final int MAX_EDGE_PERCENT = 40;

    final int mode;
    final int left;
    final int top;
    final int right;
    final int bottom;
    final int rotation;
    final int zoom;
    final int offsetX;
    final int offsetY;

    MirrorFraming(int mode, int left, int top, int right, int bottom, int rotation, int zoom,
                  int offsetX, int offsetY) {
        this.mode = mode;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.rotation = rotation;
        this.zoom = zoom;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    Frame resolve(int videoWidth, int videoHeight, int screenWidth, int screenHeight, Crop detected) {
        Crop crop = Crop.FULL;
        if (mode == MANUAL) {
            crop = new Crop(left / 100f, top / 100f, 1 - right / 100f, 1 - bottom / 100f);
        } else if (mode == AUTO) {
            crop = detected;
        } else if (mode == CROP_TO_16_9 && (long) videoWidth * 9 > (long) videoHeight * 16) {
            float side = (1 - (videoHeight * 16f / 9f) / videoWidth) / 2;
            crop = new Crop(side, 0, 1 - side, 1);
        }

        float width = videoWidth * (crop.right - crop.left);
        float height = videoHeight * (crop.bottom - crop.top);
        if (rotation % 2 != 0) {
            float previousWidth = width;
            width = height;
            height = previousWidth;
        }

        switch (mode) {
            case STRETCH:
                width = screenWidth;
                height = screenHeight;
                break;
            case RATIO_4_3:
                width = height * 4 / 3;
                break;
            case RATIO_14_9:
                width = height * 14 / 9;
                break;
            case RATIO_16_9:
                width = height * 16 / 9;
                break;
            default:
                break;
        }

        float fit = Math.min(screenWidth / width, screenHeight / height);
        float scale = fit;
        if (mode == FILL) {
            scale = Math.max(screenWidth / width, screenHeight / height);
        } else if (mode == ACTUAL_SIZE) {
            scale = 1;
        } else if (mode == ZOOM_AND_PAN) {
            scale *= zoom / 100f;
        }

        int outputWidth = Math.max(1, Math.round(width * scale));
        int outputHeight = Math.max(1, Math.round(height * scale));
        float warp = 0;
        if (mode == NONLINEAR && outputWidth < screenWidth) {
            int expandedWidth = Math.min(screenWidth, Math.round(outputWidth * 1.45f));
            warp = (float) expandedWidth / outputWidth - 1;
            outputWidth = expandedWidth;
        }

        int x = (screenWidth - outputWidth) / 2;
        int y = (screenHeight - outputHeight) / 2;
        if (mode == ZOOM_AND_PAN) {
            x += Math.round(screenWidth * offsetX / 100f);
            y += Math.round(screenHeight * offsetY / 100f);
        }
        return new Frame(crop, x, y, outputWidth, outputHeight, rotation, warp);
    }

    static final class Crop {
        static final Crop FULL = new Crop(0, 0, 1, 1);

        final float left;
        final float top;
        final float right;
        final float bottom;

        Crop(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    static final class Frame {
        final Crop crop;
        final int x;
        final int y;
        final int width;
        final int height;
        final int rotation;
        final float warp;

        Frame(Crop crop, int x, int y, int width, int height, int rotation, float warp) {
            this.crop = crop;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.warp = warp;
        }
    }
}

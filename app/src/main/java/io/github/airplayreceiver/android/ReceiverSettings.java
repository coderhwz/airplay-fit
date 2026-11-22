package io.github.airplayreceiver.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;


/** Persistent receiver identity and user preferences. */
final class ReceiverSettings {

    private static final String FILE = "receiver_settings";
    private static final String KEY_NAME = "name";
    private static final String KEY_DEVICE_IDENTIFIER = "device_identifier";
    private static final String KEY_IDENTITY_SEED = "identity_seed";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_VIDEO_HEIGHT = "video_height";
    private static final String KEY_MAX_FRAME_RATE = "max_frame_rate";
    private static final String KEY_FRAMING_MODE = "framing_mode";
    private static final String KEY_CROP_LEFT = "crop_left";
    private static final String KEY_CROP_TOP = "crop_top";
    private static final String KEY_CROP_RIGHT = "crop_right";
    private static final String KEY_CROP_BOTTOM = "crop_bottom";
    private static final String KEY_ROTATION = "framing_rotation";
    private static final String KEY_ZOOM = "framing_zoom";
    private static final String KEY_OFFSET_X = "framing_offset_x";
    private static final String KEY_OFFSET_Y = "framing_offset_y";

    private final Context context;
    private final SharedPreferences preferences;

    ReceiverSettings(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String name() {
        return preferences.getString(KEY_NAME, "AirPlay Fit");
    }

    void setName(String name) {
        preferences.edit().putString(KEY_NAME, name).apply();
    }

    boolean startOnBoot() {
        return preferences.getBoolean(KEY_START_ON_BOOT, false);
    }

    void setStartOnBoot(boolean enabled) {
        preferences.edit().putBoolean(KEY_START_ON_BOOT, enabled).apply();
    }

    /** The mirroring height senders are asked for: 720, 1080 (default) or 2160. */
    int videoHeight() {
        return preferences.getInt(KEY_VIDEO_HEIGHT, 1080);
    }

    void setVideoHeight(int height) {
        preferences.edit().putInt(KEY_VIDEO_HEIGHT, height).apply();
    }

    int maxFrameRate() {
        return preferences.getInt(KEY_MAX_FRAME_RATE, 30);
    }

    void setMaxFrameRate(int fps) {
        preferences.edit().putInt(KEY_MAX_FRAME_RATE, fps).apply();
    }

    MirrorFraming framing() {
        return new MirrorFraming(preferences.getInt(KEY_FRAMING_MODE, MirrorFraming.FIT),
                preferences.getInt(KEY_CROP_LEFT, 0), preferences.getInt(KEY_CROP_TOP, 0),
                preferences.getInt(KEY_CROP_RIGHT, 0), preferences.getInt(KEY_CROP_BOTTOM, 0),
                preferences.getInt(KEY_ROTATION, 0), preferences.getInt(KEY_ZOOM, 100),
                preferences.getInt(KEY_OFFSET_X, 0), preferences.getInt(KEY_OFFSET_Y, 0));
    }

    void setFraming(MirrorFraming framing) {
        preferences.edit()
                .putInt(KEY_FRAMING_MODE, framing.mode)
                .putInt(KEY_CROP_LEFT, framing.left)
                .putInt(KEY_CROP_TOP, framing.top)
                .putInt(KEY_CROP_RIGHT, framing.right)
                .putInt(KEY_CROP_BOTTOM, framing.bottom)
                .putInt(KEY_ROTATION, framing.rotation)
                .putInt(KEY_ZOOM, framing.zoom)
                .putInt(KEY_OFFSET_X, framing.offsetX)
                .putInt(KEY_OFFSET_Y, framing.offsetY)
                .apply();
    }

    /** A random, locally administered unicast MAC-style identifier, created once. */
    byte[] deviceIdentifier() {
        return readBytes(KEY_DEVICE_IDENTIFIER, 6);
    }

    /** The Ed25519 seed behind the advertised public key, created once. */
    byte[] identitySeed() {
        return readBytes(KEY_IDENTITY_SEED, 32);
    }

    private byte[] readBytes(String key, int length) {
        String stored = preferences.getString(key, null);
        if (stored != null) {
            byte[] decoded = Base64.decode(stored, Base64.NO_WRAP);
            if (decoded.length == length) {
                return decoded;
            }
        }
        byte[] value = new byte[length];
        new SecureRandom().nextBytes(value);
        if (length == 6) {
            value[0] = (byte) ((value[0] & 0xFC) | 0x02);
        }
        preferences.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).commit();
        return value;
    }
}

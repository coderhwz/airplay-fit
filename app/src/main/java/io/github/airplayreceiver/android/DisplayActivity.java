package io.github.airplayreceiver.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

import io.github.airplayreceiver.R;
import io.github.airplayreceiver.protocol.VideoPlayback;

/** Full screen output for mirrored video and photos. Back ends the session. */
public final class DisplayActivity extends Activity implements VideoRenderer.SizeListener {

    private static DisplayActivity current;

    private MirrorVideoView videoView;
    private ImageView photoView;
    private ReceiverSettings settings;
    private MirrorFraming framing;
    private Dialog framingDialog;
    private int videoWidth;
    private int videoHeight;
    private TextView statusView;
    private TextView controlsView;
    private boolean showStatistics;
    private boolean direct;
    private long controlsUntil;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable updateOverlay = this::updateOverlay;

    static void closeIfOpen() {
        if (current != null) {
            current.finish();
        }
    }

    static boolean refreshMedia() {
        if (current == null) return false;
        current.showMedia();
        return true;
    }

    static void updateVideoSize(int width, int height) {
        if (current != null) current.setVideoSize(width, height);
    }

    static void resetAutoCrop() {
        if (current != null) current.videoView.resetAutoCrop();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.display_activity);
        videoView = findViewById(R.id.video_surface);
        photoView = findViewById(R.id.photo_view);
        statusView = findViewById(R.id.cast_status);
        controlsView = findViewById(R.id.cast_controls);
        settings = new ReceiverSettings(this);
        framing = settings.framing();
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        ReceiverService service = ReceiverService.instance();
        if (service == null || !service.displayActive()) {
            finish();
            return;
        }
        VideoRenderer decoder = service.videoRenderer();
        videoView.setDecoder(service::setDisplaySurface);
        videoView.onResume();
        decoder.setSizeListener(this);
        if (service.isDirectPlayback()) {
            DirectVideoPlayer.Snapshot state = service.directStatistics();
            setVideoSize(state.width, state.height);
        }
        showMedia();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(updateOverlay);
        videoView.onPause();
        videoView.setDecoder(null);
        ReceiverService service = ReceiverService.instance();
        if (service != null) {
            service.videoRenderer().setSizeListener(null);
        }
        if (current == this) {
            current = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (framingDialog != null) {
            framingDialog.dismiss();
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isHandledOnKeyUp(keyCode)) return true;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (showStatistics) {
                showStatistics = false;
                updateOverlay();
                return true;
            }
            ReceiverService service = ReceiverService.instance();
            if (service != null) {
                service.disconnectClient();
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isHandledOnKeyUp(keyCode)) return super.onKeyUp(keyCode, event);
        if (event.isCanceled()) return true;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_INFO) {
            showStatistics = !showStatistics;
            updateOverlay();
            return true;
        }
        if (direct && keyCode != KeyEvent.KEYCODE_MENU) {
            ReceiverService service = ReceiverService.instance();
            if (service == null) return true;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    service.adjustPlaybackPosition(-10000);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    service.adjustPlaybackPosition(10000);
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    service.setPlaybackRunning(true);
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    service.setPlaybackRunning(false);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    break;
                default:
                    service.togglePlayback();
                    break;
            }
            revealControls();
        } else if (videoWidth > 0 && photoView.getVisibility() != View.VISIBLE) {
            framingDialog = new FramingDialog(this, framing, this::applyFraming,
                    videoView.isAutoCropLocked(), videoView::redetectAutoCrop).show();
        }
        return true;
    }

    private boolean isHandledOnKeyUp(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_INFO
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MENU) return true;
        return direct && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND);
    }

    private void applyFraming(MirrorFraming selected) {
        settings.setFraming(selected);
        framing = selected;
        videoView.setFraming(framing, videoWidth, videoHeight);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void showMedia() {
        ReceiverService service = ReceiverService.instance();
        boolean nextDirect = service != null && service.isDirectPlayback();
        if (nextDirect && !direct) controlsUntil = SystemClock.uptimeMillis() + 4500;
        direct = nextDirect;
        Bitmap photo = service == null ? null : service.currentPhoto();
        if (photo != null) {
            photoView.setImageBitmap(photo);
            photoView.setVisibility(View.VISIBLE);
        } else {
            photoView.setImageDrawable(null);
            photoView.setVisibility(View.GONE);
        }
        updateOverlay();
    }

    private void revealControls() {
        controlsUntil = SystemClock.uptimeMillis() + 4500;
        updateOverlay();
    }

    private void updateOverlay() {
        main.removeCallbacks(updateOverlay);
        ReceiverService service = ReceiverService.instance();
        if (service == null || this != current) return;
        DirectVideoPlayer.Snapshot playback = service.directStatistics();
        boolean reconnecting = service.isMirrorReconnecting();
        if (showStatistics || reconnecting) {
            if (reconnecting) {
                statusView.setText(R.string.cast_reconnecting);
            } else if (direct) {
                statusView.setText(getString(R.string.cast_statistics, getString(R.string.cast_direct),
                        playback.width, playback.height, fps(playback.fps), "—",
                        playback.drops < 0 ? "—" : String.valueOf(playback.drops)));
            } else {
                MirrorStatistics.Sample sample = service.mirrorStatistics();
                statusView.setText(getString(R.string.cast_statistics, getString(R.string.cast_mirror),
                        videoWidth, videoHeight, fps(sample.fps),
                        getString(R.string.cast_bitrate, sample.megabits), String.valueOf(service.droppedMirrorFrames())));
            }
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }

        VideoPlayback.State state = playback.state;
        boolean visibleControls = direct && (!state.ready || state.buffering || !state.playing
                || SystemClock.uptimeMillis() < controlsUntil);
        if (visibleControls) {
            String label = getString(!state.ready || state.buffering ? R.string.cast_buffering
                    : state.playing ? R.string.cast_playing : R.string.cast_paused);
            controlsView.setText(getString(R.string.cast_progress, label, time(state.positionMs),
                    state.durationMs > 0 ? time(state.durationMs) : "—", getString(R.string.cast_controls_hint)));
            controlsView.setVisibility(View.VISIBLE);
        } else {
            controlsView.setVisibility(View.GONE);
        }
        if (showStatistics || reconnecting || visibleControls) main.postDelayed(updateOverlay, 750);
    }

    private String fps(float value) {
        return value < 0 ? "—" : getString(R.string.cast_fps, value);
    }

    private static String time(long millis) {
        long seconds = millis / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600,
                    seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    @Override
    public void onVideoSize(int width, int height) {
        runOnUiThread(() -> {
            ReceiverService service = ReceiverService.instance();
            if (service != null && !service.isDirectPlayback()) setVideoSize(width, height);
        });
    }

    private void setVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        videoView.setFraming(framing, width, height);
    }
}

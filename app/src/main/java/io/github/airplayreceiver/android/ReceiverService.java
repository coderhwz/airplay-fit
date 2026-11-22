package io.github.airplayreceiver.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;
import android.view.Surface;
import android.widget.Toast;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import io.github.airplayreceiver.R;
import io.github.airplayreceiver.protocol.AirPlayServer;
import io.github.airplayreceiver.protocol.MulticastDnsResponder;
import io.github.airplayreceiver.protocol.ReceiverConfiguration;
import io.github.airplayreceiver.protocol.ReceiverListener;
import io.github.airplayreceiver.protocol.ReceiverLog;
import io.github.airplayreceiver.protocol.VideoPlayback;

/**
 * Foreground service that keeps the receiver discoverable, owns the network servers, and routes
 * media to the renderers and the full screen display.
 */
public final class ReceiverService extends Service implements ReceiverListener {

    private static final String TAG = "ReceiverService";
    private static final int NOTIFICATION_IDENTIFIER = 1;
    private static final String CHANNEL_IDENTIFIER = "receiver";
    private static final int CONTROL_PORT = 7000;
    private static final String ACTION_RESTART = "io.github.airplayreceiver.RESTART";

    /** What the status screen shows. */
    static final class Status {
        final boolean advertising;
        final String name;
        final String address;
        final String activity;

        Status(boolean advertising, String name, String address, String activity) {
            this.advertising = advertising;
            this.name = name;
            this.address = address;
            this.activity = activity;
        }
    }

    interface StatusObserver {
        void onStatusChanged(Status status);
    }

    private static volatile ReceiverService instance;
    private static volatile StatusObserver statusObserver;
    private static volatile Status status = new Status(false, "", "", "Starting");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;

    private final VideoRenderer videoRenderer = new VideoRenderer();
    private final AudioRenderer audioRenderer = new AudioRenderer();
    private final MirrorStatistics mirrorStatistics = new MirrorStatistics();
    private DirectVideoPlayer directPlayer;
    private Surface displaySurface;
    private boolean directSurface;
    private volatile VideoPlayback playback;
    private volatile boolean mirrorReconnecting;
    private volatile long initialDrops;
    private final Runnable reconnectExpired = () -> {
        mirrorReconnecting = false;
        updateActivityStatus();
        closeDisplayIfIdle();
    };

    private AirPlayServer server;
    private MulticastDnsResponder responder;
    private InetAddress advertisedAddress;

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean mirroring;
    private volatile boolean audioPlaying;
    private volatile boolean photoShowing;
    private volatile String clientName;
    private volatile Bitmap photo;

    static void start(Context context) {
        startWithIntent(context, new Intent(context, ReceiverService.class));
    }

    static void restart(Context context) {
        startWithIntent(context, new Intent(context, ReceiverService.class).setAction(ACTION_RESTART));
    }

    private static void startWithIntent(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static ReceiverService instance() {
        return instance;
    }

    static void setStatusObserver(StatusObserver observer) {
        statusObserver = observer;
        if (observer != null) {
            observer.onStatusChanged(status);
        }
    }

    VideoRenderer videoRenderer() {
        return videoRenderer;
    }

    Bitmap currentPhoto() {
        return photo;
    }

    boolean displayActive() {
        return mirroring || mirrorReconnecting || playback != null || photo != null;
    }

    boolean isDirectPlayback() {
        return playback != null;
    }

    boolean isMirrorReconnecting() {
        return mirrorReconnecting;
    }

    DirectVideoPlayer.Snapshot directStatistics() {
        return directPlayer.snapshot();
    }

    MirrorStatistics.Sample mirrorStatistics() {
        return mirrorStatistics.current();
    }

    long droppedMirrorFrames() {
        return Math.max(0, videoRenderer.droppedFrames() - initialDrops);
    }

    void setDisplaySurface(Surface surface) {
        displaySurface = surface;
        routeVideoSurface();
    }

    private void routeVideoSurface() {
        boolean useDirect = playback != null;
        if (useDirect != directSurface) {
            if (directSurface) directPlayer.setSurface(null);
            else if (displaySurface != null) videoRenderer.setSurface(null);
            directSurface = useDirect;
        }
        if (directSurface) directPlayer.setSurface(displaySurface);
        else videoRenderer.setSurface(displaySurface);
    }

    void togglePlayback() {
        VideoPlayback current = playback;
        if (current != null) directPlayer.toggle(current);
    }

    void setPlaybackRunning(boolean playing) {
        VideoPlayback current = playback;
        if (current != null) directPlayer.setPlaying(current, playing);
    }

    void adjustPlaybackPosition(long offsetMs) {
        VideoPlayback current = playback;
        if (current != null) directPlayer.seek(current, offsetMs, true);
    }

    /** Ends the current AirPlay session, as when the user presses Back on the display. */
    void disconnectClient() {
        mirroring = false;
        cancelMirrorGrace();
        VideoPlayback active = playback;
        if (active != null) onPlaybackStopped(active);
        final AirPlayServer current = server;
        if (current != null) {
            worker.post(new Runnable() {
                @Override
                public void run() {
                    current.disconnectStreamingClient();
                }
            });
        }
        photoShowing = false;
        photo = null;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        directPlayer = new DirectVideoPlayer(new DirectVideoPlayer.Listener() {
            @Override
            public void onSize(VideoPlayback video, int width, int height) {
                if (width > 0 && height > 0) mainHandler.post(() -> {
                    if (playback == video) DisplayActivity.updateVideoSize(width, height);
                });
            }

            @Override
            public void onEnded(VideoPlayback video, boolean failed) {
                mainHandler.post(() -> {
                    if (playback != video) return;
                    if (failed) Toast.makeText(ReceiverService.this, R.string.cast_video_error, Toast.LENGTH_LONG).show();
                    onPlaybackStopped(video);
                });
            }
        });
        ReceiverLog.setSink(new ReceiverLog.Sink() {
            @Override
            public void write(int level, String tag, String message, Throwable error) {
                String fullTag = "AirPlay." + tag;
                if (error != null) {
                    Log.println(level, fullTag, message + "\n" + Log.getStackTraceString(error));
                } else {
                    Log.println(level, fullTag, message);
                }
            }
        });

        startForeground(NOTIFICATION_IDENTIFIER, buildNotification("Starting"));

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("AirPlayReceiver");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirPlayReceiver");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirPlayReceiver:streaming");
        wakeLock.setReferenceCounted(false);

        workerThread = new HandlerThread("ReceiverWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        videoRenderer.start();
        worker.post(startReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            worker.post(new Runnable() {
                @Override
                public void run() {
                    stopReceiver();
                    startReceiver.run();
                }
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(connectivityReceiver);
        cancelMirrorGrace();
        directPlayer.release();
        worker.removeCallbacksAndMessages(null);
        worker.post(new Runnable() {
            @Override
            public void run() {
                stopReceiver();
            }
        });
        workerThread.quitSafely();
        videoRenderer.stop();
        audioRenderer.stop();
        if (multicastLock.isHeld()) multicastLock.release();
        if (wifiLock.isHeld()) wifiLock.release();
        if (wakeLock.isHeld()) wakeLock.release();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable startReceiver = new Runnable() {
        @Override
        public void run() {
            if (server != null && responder != null) {
                return;
            }
            ReceiverSettings settings = new ReceiverSettings(ReceiverService.this);
            NetworkAddresses network = NetworkAddresses.find();
            if (network == null) {
                publishStatus(false, settings.name(), "", "Waiting for a network connection");
                worker.postDelayed(this, 5000);
                return;
            }
            int frameRate = settings.maxFrameRate();
            Point size = videoSize(settings.videoHeight(), frameRate);
            ReceiverConfiguration configuration = new ReceiverConfiguration(settings.name(),
                    settings.deviceIdentifier(), settings.identitySeed(), size.x, size.y,
                    frameRate, CONTROL_PORT);
            try {
                if (server == null) {
                    AirPlayServer created = new AirPlayServer(configuration, ReceiverService.this);
                    created.start();
                    server = created;
                }
                List<MulticastDnsResponder.Service> services = new ArrayList<>();
                services.add(new MulticastDnsResponder.Service(configuration.name, "_airplay._tcp.local",
                        CONTROL_PORT, server.airPlayTxtRecord()));
                services.add(new MulticastDnsResponder.Service(configuration.raopInstanceName(), "_raop._tcp.local",
                        CONTROL_PORT, server.raopTxtRecord()));
                responder = new MulticastDnsResponder(network.address, network.networkInterface,
                        configuration.hostName(), services);
                responder.start();
                advertisedAddress = network.address;
                ReceiverLog.i(TAG, "Advertising \"" + configuration.name + "\" at " + network.address.getHostAddress()
                        + " on " + network.networkInterface.getName() + ", display " + size.x + "x" + size.y);
                publishStatus(true, configuration.name, network.address.getHostAddress(), "Ready");
            } catch (Exception error) {
                ReceiverLog.e(TAG, "Could not start the receiver", error);
                stopAdvertising();
                publishStatus(false, settings.name(), network.address.getHostAddress(),
                        "Could not start: " + error.getMessage() + ". Retrying.");
                worker.postDelayed(this, 5000);
            }
        }
    };

    private void stopAdvertising() {
        if (responder != null) {
            responder.stop();
            responder = null;
        }
        advertisedAddress = null;
    }

    private void stopReceiver() {
        stopAdvertising();
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            worker.postDelayed(new Runnable() {
                @Override
                public void run() {
                    NetworkAddresses network = NetworkAddresses.find();
                    InetAddress current = network == null ? null : network.address;
                    boolean changed = current == null ? advertisedAddress != null : !current.equals(advertisedAddress);
                    if (changed) {
                        ReceiverLog.i(TAG, "Network changed to " + current + ", restarting receiver");
                        stopAdvertising();
                        worker.removeCallbacks(startReceiver);
                        startReceiver.run();
                    }
                }
            }, 1500);
        }
    };

    private Point videoSize(int height, int frameRate) {
        if (height >= 2160 && supportsUltraHighDefinition(this, frameRate)) {
            return new Point(3840, 2160);
        }
        if (height <= 720) {
            return new Point(1280, 720);
        }
        return new Point(1920, 1080);
    }

    /** True when the display and hardware H.264 decoder support 4K at the requested rate. */
    static boolean supportsUltraHighDefinition(Context context, int frameRate) {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        android.view.Display.Mode mode = windowManager.getDefaultDisplay().getMode();
        if (Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()) < 3840) {
            return false;
        }
        for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder() || info.getName().startsWith("OMX.google") || info.getName().startsWith("c2.android")) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase("video/avc")
                        && info.getCapabilitiesForType(type).getVideoCapabilities()
                                .areSizeAndRateSupported(3840, 2160, frameRate)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ status

    private void publishStatus(boolean advertising, String name, String address, String activity) {
        final Status next = new Status(advertising, name, address, activity);
        status = next;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                StatusObserver observer = statusObserver;
                if (observer != null) {
                    observer.onStatusChanged(next);
                }
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                manager.notify(NOTIFICATION_IDENTIFIER, buildNotification(next.activity));
            }
        });
    }

    private synchronized void updateActivityStatus() {
        String activity;
        String client = clientName != null ? clientName : "a device";
        if (playback != null) {
            activity = getString(R.string.cast_direct);
        } else if (mirrorReconnecting) {
            activity = getString(R.string.cast_reconnecting);
        } else if (mirroring && audioPlaying) {
            activity = "Mirroring " + client + " with sound";
        } else if (mirroring) {
            activity = "Mirroring " + client;
        } else if (audioPlaying) {
            activity = "Playing audio from " + client;
        } else if (photoShowing) {
            activity = "Showing a photo";
        } else if (clientName != null) {
            activity = "Connected to " + client;
        } else {
            activity = "Ready";
        }
        Status current = status;
        publishStatus(current.advertising, current.name, current.address, activity);

        boolean streaming = mirroring || audioPlaying || playback != null;
        if (streaming && !wakeLock.isHeld()) {
            wakeLock.acquire();
        } else if (!streaming && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_IDENTIFIER,
                    getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL_IDENTIFIER);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        return builder.setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.notification_icon)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    // ------------------------------------------------------------------ display

    private void showDisplay() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(ReceiverService.this, DisplayActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                if (!DisplayActivity.refreshMedia()) startActivity(intent);
            }
        });
    }

    private void closeDisplayIfIdle() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!displayActive()) {
                    DisplayActivity.closeIfOpen();
                } else {
                    DisplayActivity.refreshMedia();
                }
            }
        });
    }

    // ------------------------------------------------------------------ ReceiverListener

    @Override
    public void onClientConnected(String name, String model) {
        clientName = name;
        updateActivityStatus();
    }

    @Override
    public void onClientDisconnected() {
        clientName = null;
        mirroring = false;
        audioPlaying = false;
        photoShowing = false;
        photo = null;
        videoRenderer.reset();
        audioRenderer.stop();
        updateActivityStatus();
        closeDisplayIfIdle();
    }

    @Override
    public void onMirroringStarted() {
        cancelMirrorGrace();
        initialDrops = videoRenderer.droppedFrames();
        mirrorStatistics.reset();
        mirroring = true;
        photoShowing = false;
        photo = null;
        videoRenderer.reset();
        updateActivityStatus();
        mainHandler.post(DisplayActivity::resetAutoCrop);
        showDisplay();
    }

    @Override
    public void onMirroringStopped() {
        cancelMirrorGrace();
        mirroring = false;
        videoRenderer.reset();
        updateActivityStatus();
        closeDisplayIfIdle();
    }

    @Override
    public void onMirroringInterrupted() {
        if (!mirroring) return;
        mirroring = false;
        mirrorReconnecting = true;
        videoRenderer.reset();
        mainHandler.removeCallbacks(reconnectExpired);
        mainHandler.postDelayed(reconnectExpired, 8000);
        updateActivityStatus();
        closeDisplayIfIdle();
    }

    private void cancelMirrorGrace() {
        mirrorReconnecting = false;
        mainHandler.removeCallbacks(reconnectExpired);
    }

    @Override
    public void onPlaybackStarted(VideoPlayback video) {
        playback = video;
        cancelMirrorGrace();
        photo = null;
        photoShowing = false;
        mainHandler.post(() -> {
            if (playback != video) return;
            DisplayActivity.resetAutoCrop();
            DisplayActivity.updateVideoSize(0, 0);
            routeVideoSurface();
            directPlayer.start(video);
            showDisplay();
        });
        updateActivityStatus();
    }

    @Override
    public void onPlaybackStopped(VideoPlayback video) {
        if (playback != video) return;
        playback = null;
        mainHandler.post(() -> {
            directPlayer.stop(video);
            if (playback == null) routeVideoSurface();
        });
        updateActivityStatus();
        closeDisplayIfIdle();
    }

    @Override
    public void onPlaybackRate(VideoPlayback video, boolean playing) {
        mainHandler.post(() -> {
            if (playback == video) directPlayer.setPlaying(video, playing);
        });
    }

    @Override
    public void onPlaybackSeek(VideoPlayback video, long positionMs) {
        mainHandler.post(() -> {
            if (playback == video) directPlayer.seek(video, positionMs, false);
        });
    }

    @Override
    public VideoPlayback.State playbackState(VideoPlayback video) {
        DirectVideoPlayer.Snapshot state = directPlayer.snapshot();
        return state.video == video ? state.state : VideoPlayback.State.IDLE;
    }

    @Override
    public void onVideoParameters(byte[] sequenceParameterSet, byte[] pictureParameterSet, int width, int height) {
        videoRenderer.onParameters(sequenceParameterSet, pictureParameterSet, width, height);
    }

    @Override
    public void onVideoFrame(byte[] accessUnit, long timestampNanoseconds, boolean keyFrame) {
        mirrorStatistics.frame(accessUnit.length);
        videoRenderer.onFrame(accessUnit, timestampNanoseconds, keyFrame);
    }

    @Override
    public void onAudioStarted(int compressionType, int samplesPerFrame, boolean screenAudio) {
        audioPlaying = true;
        audioRenderer.start(compressionType, samplesPerFrame, screenAudio);
        updateActivityStatus();
    }

    @Override
    public void onAudioFrame(byte[] frame, long rtpTimestamp) {
        audioRenderer.onFrame(frame);
    }

    @Override
    public void onAudioFlush() {
        audioRenderer.flush();
    }

    @Override
    public void onAudioStopped() {
        audioPlaying = false;
        audioRenderer.stop();
        updateActivityStatus();
    }

    @Override
    public void onVolume(float decibels) {
        ReceiverLog.i(TAG, "Volume " + decibels + " dB");
        audioRenderer.setVolume(decibels);
    }

    @Override
    public void onPhoto(byte[] image) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= 1920 || bounds.outHeight / (options.inSampleSize * 2) >= 1080) {
            options.inSampleSize *= 2;
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(image, 0, image.length, options);
        if (decoded == null) {
            ReceiverLog.w(TAG, "Could not decode a photo of " + image.length + " bytes");
            return;
        }
        ReceiverLog.i(TAG, "Photo " + decoded.getWidth() + "x" + decoded.getHeight());
        photo = decoded;
        photoShowing = true;
        updateActivityStatus();
        showDisplay();
    }

    @Override
    public void onPhotoStopped() {
        photoShowing = false;
        photo = null;
        updateActivityStatus();
        closeDisplayIfIdle();
    }
}

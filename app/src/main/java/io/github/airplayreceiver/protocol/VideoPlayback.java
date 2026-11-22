package io.github.airplayreceiver.protocol;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Parsed AirPlay video handoff and a snapshot of the receiver's player. */
public final class VideoPlayback {
    public final String url;
    public final double start;
    public final boolean startIsSeconds;

    private VideoPlayback(String url, double start, boolean startIsSeconds) {
        this.url = url;
        this.start = start;
        this.startIsSeconds = startIsSeconds;
    }

    static VideoPlayback parse(ControlMessage request) {
        Map<String, Object> body = new LinkedHashMap<>();
        String type = request.header("Content-Type");
        if (type != null && type.contains("apple-binary-plist")) {
            for (Map.Entry<String, Object> entry : BinaryPropertyList.parseDictionary(request.body).entrySet()) {
                body.put(entry.getKey().toLowerCase(Locale.US), entry.getValue());
            }
        } else {
            for (String line : new String(request.body, java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n")) {
                int separator = line.indexOf(':');
                if (separator > 0) {
                    body.put(line.substring(0, separator).trim().toLowerCase(Locale.US),
                            line.substring(separator + 1).trim());
                }
            }
        }

        Object location = body.get("content-location");
        if (location == null) location = request.header("Content-Location");
        if (!(location instanceof String)) throw new IllegalArgumentException("Missing video location");
        URI uri = URI.create((String) location);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new UnsupportedOperationException("Video source is not an HTTP URL");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid video location");
        }
        boolean seconds = body.containsKey("start-position-seconds");
        Object value = body.get(seconds ? "start-position-seconds" : "start-position");
        double start = value != null ? Double.parseDouble(String.valueOf(value)) : 0;
        if (Double.isNaN(start) || Double.isInfinite(start) || start < 0 || (!seconds && start > 1)) {
            throw new IllegalArgumentException("Invalid start position");
        }
        return new VideoPlayback(uri.toASCIIString(), start, seconds);
    }

    public static final class State {
        public static final State IDLE = new State(false, false, false, 0, 0);
        public final boolean ready;
        public final boolean playing;
        public final boolean buffering;
        public final long positionMs;
        public final long durationMs;

        public State(boolean ready, boolean playing, boolean buffering, long positionMs, long durationMs) {
            this.ready = ready;
            this.playing = playing;
            this.buffering = buffering;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }

        byte[] playbackInfo() {
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<plist version=\"1.0\"><dict><key>readyToPlay</key>");
            xml.append(ready ? "<true/>" : "<false/>");
            if (ready) {
                double duration = durationMs / 1000.0;
                xml.append("<key>duration</key><real>").append(duration)
                        .append("</real><key>position</key><real>").append(positionMs / 1000.0)
                        .append("</real><key>rate</key><real>").append(playing ? 1 : 0)
                        .append("</real><key>playbackBufferEmpty</key>").append(buffering ? "<true/>" : "<false/>")
                        .append("<key>playbackBufferFull</key>").append(buffering ? "<false/>" : "<true/>")
                        .append("<key>playbackLikelyToKeepUp</key>").append(buffering ? "<false/>" : "<true/>")
                        .append("<key>loadedTimeRanges</key><array/><key>seekableTimeRanges</key><array>");
                if (duration > 0) {
                    xml.append("<dict><key>start</key><real>0</real><key>duration</key><real>")
                            .append(duration).append("</real></dict>");
                }
                xml.append("</array>");
            }
            return xml.append("</dict></plist>").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}

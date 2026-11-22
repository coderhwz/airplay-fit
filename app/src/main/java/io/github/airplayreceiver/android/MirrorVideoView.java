package io.github.airplayreceiver.android;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Samples the hardware decoder's texture directly, keeping ordinary playback on the GPU. */
public final class MirrorVideoView extends GLSurfaceView {
    interface DecoderOutput {
        void setSurface(Surface surface);
    }

    private static final String TAG = "MirrorVideoView";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VideoRenderer renderer = new VideoRenderer();
    private DecoderOutput decoder;
    private VideoInput attached;
    private boolean resumed;
    private boolean windowReady;

    public MirrorVideoView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setEGLContextClientVersion(2);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    void setDecoder(DecoderOutput decoder) {
        this.decoder = decoder;
        attachInput();
    }

    void setFraming(MirrorFraming framing, int videoWidth, int videoHeight) {
        renderer.state = new VideoState(framing, videoWidth, videoHeight);
        requestRender();
    }

    boolean isAutoCropLocked() {
        return renderer.detector.isLocked();
    }

    void redetectAutoCrop() {
        queueEvent(() -> renderer.detector.redetect());
        requestRender();
    }

    void resetAutoCrop() {
        queueEvent(renderer::resetAutoCrop);
        requestRender();
    }

    @Override
    public void onResume() {
        resumed = true;
        super.onResume();
        attachInput();
    }

    @Override
    public void onPause() {
        resumed = false;
        attachInput();
        super.onPause();
        renderer.releaseInput();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        windowReady = true;
        attachInput();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        windowReady = false;
        attachInput();
        super.surfaceDestroyed(holder);
    }

    private void attachInput() {
        if (decoder == null) return;
        VideoInput next = resumed && windowReady ? renderer.input : null;
        if (next != attached) {
            if (attached != null) decoder.setSurface(null);
            attached = next;
            if (next != null) decoder.setSurface(next.surface);
        }
    }

    private static final class VideoState {
        final MirrorFraming framing;
        final int width;
        final int height;

        VideoState(MirrorFraming framing, int width, int height) {
            this.framing = framing;
            this.width = width;
            this.height = height;
        }
    }

    private static final class VideoInput {
        final int textureId;
        final SurfaceTexture texture;
        final Surface surface;
        final AtomicBoolean pending = new AtomicBoolean();

        VideoInput(int textureId) {
            this.textureId = textureId;
            texture = new SurfaceTexture(textureId);
            surface = new Surface(texture);
        }

        void release() {
            texture.setOnFrameAvailableListener(null);
            surface.release();
            texture.release();
        }
    }

    private final class VideoRenderer implements Renderer {
        private static final int DETECT_INTERVAL_MS = 300;
        private static final String VERTEX_SHADER =
                "attribute vec2 corner;\n"
                + "uniform vec4 viewport;\n"
                + "varying vec2 texCoord;\n"
                + "void main() {\n"
                + "  texCoord = corner;\n"
                + "  gl_Position = vec4(viewport.x + corner.x * viewport.z,"
                + "                    viewport.y - corner.y * viewport.w, 0.0, 1.0);\n"
                + "}\n";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                + "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n"
                + "#else\nprecision mediump float;\n#endif\n"
                + "uniform samplerExternalOES video;\n"
                + "uniform mat4 textureMatrix;\n"
                + "uniform vec4 crop;\n"
                + "uniform int rotation;\n"
                + "uniform float warp;\n"
                + "varying vec2 texCoord;\n"
                + "void main() {\n"
                + "  vec2 uv = texCoord;\n"
                + "  float x = uv.x * 2.0 - 1.0;\n"
                + "  uv.x += 0.5 * warp * x * (1.0 - x * x);\n"
                + "  if (rotation == 1) uv = vec2(uv.y, 1.0 - uv.x);\n"
                + "  else if (rotation == 2) uv = 1.0 - uv;\n"
                + "  else if (rotation == 3) uv = vec2(1.0 - uv.y, uv.x);\n"
                + "  uv = mix(crop.xy, crop.zw, uv);\n"
                + "  uv = (textureMatrix * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy;\n"
                + "  gl_FragColor = vec4(texture2D(video, uv).rgb, 1.0);\n"
                + "}\n";

        private volatile VideoState state;
        private volatile VideoInput input;

        private final FloatBuffer vertices = ByteBuffer.allocateDirect(8 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final float[] textureMatrix = new float[16];
        private final ByteBuffer sample = ByteBuffer.allocateDirect(
                AutoCropDetector.WIDTH * AutoCropDetector.HEIGHT * 4);
        private final int[] name = new int[1];

        private volatile AutoCropDetector detector = new AutoCropDetector();
        private int previousWidth;
        private int previousHeight;
        private int previousMode = -1;
        private int previousRotation;
        private long lastSample;
        private boolean frameAvailable;
        private int width;
        private int height;
        private int program;
        private int position;
        private int viewport;
        private int crop;
        private int rotation;
        private int warp;
        private int matrix;
        private int sampler;
        private int framebuffer;
        private boolean samplingUnavailable;

        VideoRenderer() {
            vertices.put(new float[] {0, 0, 0, 1, 1, 0, 1, 1}).position(0);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            frameAvailable = false;
            framebuffer = 0;
            samplingUnavailable = false;
            resetAutoCrop();
            program = createProgram();
            position = GLES20.glGetAttribLocation(program, "corner");
            viewport = GLES20.glGetUniformLocation(program, "viewport");
            crop = GLES20.glGetUniformLocation(program, "crop");
            rotation = GLES20.glGetUniformLocation(program, "rotation");
            warp = GLES20.glGetUniformLocation(program, "warp");
            matrix = GLES20.glGetUniformLocation(program, "textureMatrix");
            sampler = GLES20.glGetUniformLocation(program, "video");
            GLES20.glClearColor(0, 0, 0, 1);

            GLES20.glGenTextures(1, name, 0);
            int external = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
            GLES20.glBindTexture(external, name[0]);
            setTextureParameters(external);

            VideoInput previous = input;
            VideoInput created = new VideoInput(name[0]);
            input = created;
            created.texture.setOnFrameAvailableListener(texture -> {
                created.pending.set(true);
                requestRender();
            }, main);
            main.post(() -> {
                attachInput();
                if (previous != null) previous.release();
            });
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            VideoInput source = input;
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (source == null) return;

            boolean fresh = source.pending.getAndSet(false);
            if (fresh) {
                source.texture.updateTexImage();
                source.texture.getTransformMatrix(textureMatrix);
                frameAvailable = true;
            }

            VideoState video = state;
            if (!frameAvailable || video == null || video.width <= 0 || video.height <= 0
                    || width <= 0 || height <= 0) return;

            int mode = video.framing.mode;
            if (previousMode != mode || (mode == MirrorFraming.AUTO
                    && (previousWidth != video.width || previousHeight != video.height
                    || previousRotation != video.framing.rotation))) {
                resetAutoCrop();
            }
            previousMode = mode;
            previousWidth = video.width;
            previousHeight = video.height;
            previousRotation = video.framing.rotation;

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, source.textureId);
            GLES20.glUniform1i(sampler, 0);
            GLES20.glUniformMatrix4fv(matrix, 1, false, textureMatrix, 0);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glEnableVertexAttribArray(position);

            long now = SystemClock.uptimeMillis();
            if (mode == MirrorFraming.AUTO && !detector.isLocked()
                    && fresh && now - lastSample >= DETECT_INTERVAL_MS) {
                lastSample = now;
                sampleBlackBorders(now);
            }

            MirrorFraming.Frame frame = video.framing.resolve(video.width, video.height, width, height,
                    detector.currentCrop());
            draw(frame.crop, frame.rotation, frame.warp, 2f * frame.x / width - 1,
                    1 - 2f * frame.y / height, 2f * frame.width / width, 2f * frame.height / height);
        }

        private void resetAutoCrop() {
            detector = new AutoCropDetector();
            lastSample = 0;
        }

        private void sampleBlackBorders(long now) {
            if (samplingUnavailable) return;
            if (framebuffer == 0 && !createSamplingBuffer()) {
                samplingUnavailable = true;
                Log.w(TAG, "Automatic crop preview framebuffer could not be created");
                return;
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glViewport(0, 0, AutoCropDetector.WIDTH, AutoCropDetector.HEIGHT);
            draw(MirrorFraming.Crop.FULL, 0, 0, -1, 1, 2, 2);
            sample.position(0);
            GLES20.glReadPixels(0, 0, AutoCropDetector.WIDTH, AutoCropDetector.HEIGHT, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, sample);
            detector.update(sample, now);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, width, height);
        }

        private boolean createSamplingBuffer() {
            GLES20.glGenTextures(1, name, 0);
            int sampleTexture = name[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sampleTexture);
            setTextureParameters(GLES20.GL_TEXTURE_2D);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, AutoCropDetector.WIDTH,
                    AutoCropDetector.HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glGenFramebuffers(1, name, 0);
            framebuffer = name[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, sampleTexture, 0);
            boolean ready = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            return ready;
        }

        private void draw(MirrorFraming.Crop region, int turns, float nonlinear, float x, float y,
                          float w, float h) {
            GLES20.glUniform4f(viewport, x, y, w, h);
            GLES20.glUniform4f(crop, region.left, region.top, region.right, region.bottom);
            GLES20.glUniform1i(rotation, turns);
            GLES20.glUniform1f(warp, nonlinear);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        void releaseInput() {
            VideoInput source = input;
            input = null;
            if (source != null) source.release();
        }

        private void setTextureParameters(int target) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        private int createProgram() {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] != GLES20.GL_TRUE) {
                throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            }
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            return program;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] != GLES20.GL_TRUE) {
                throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
            }
            return shader;
        }
    }
}

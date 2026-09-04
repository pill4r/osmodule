package dev.pillar.osmodule.panorama.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PanoramaSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    private val sphere = SphereRenderer(::requestRender)
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            sphere.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f

    var onVideoSurface: ((SurfaceTexture) -> Unit)?
        get() = sphere.onVideoSurface
        set(value) { sphere.onVideoSurface = value }

    /** Maximum edge supported by this GL context; available when [onVideoSurface] fires. */
    val maxTextureSize: Int get() = sphere.maxTextureSize

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(sphere)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (!scale.isInProgress) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
                MotionEvent.ACTION_MOVE -> {
                    val density = resources.displayMetrics.density
                    sphere.rotateFromScreen(
                        (event.x - lastX) / density * 0.18f,
                        (event.y - lastY) / density * 0.18f,
                    )
                    lastX = event.x
                    lastY = event.y
                    requestRender()
                }
            }
        }
        return true
    }

    fun recenter() {
        sphere.recenter()
        requestRender()
    }

    /** Rotates the rendered panorama in screen space; positive values are counter-clockwise. */
    fun setRollDegrees(degrees: Float) {
        sphere.setRollDegrees(degrees)
        requestRender()
    }

    fun setCalibration(calibration: PanoramaCalibration) {
        queueEvent { sphere.setCalibration(calibration) }
    }

    fun setProjection(projection: PanoramaProjection) {
        queueEvent { sphere.setProjection(projection) }
    }

    fun release() {
        queueEvent { sphere.release() }
        onPause()
    }
}

enum class PanoramaProjection {
    /** Two side-by-side fisheye circles as carried by an Osmo 360 LRF proxy. */
    DUAL_FISHEYE,

    /** A stitched 360° × 180° JPEG with a 2:1 aspect ratio. */
    EQUIRECTANGULAR,
}

private class SphereRenderer(
    private val requestFrame: () -> Unit,
) : GLSurfaceView.Renderer {
    private val vertices: FloatBuffer = sphereVertices(64, 32)
    private val frameAvailable = AtomicBoolean(false)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    private val textureMatrix = FloatArray(16)
    private var program = 0
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var calibration: PanoramaCalibration? = null
    private var calibrationDirty = true
    private var projectionType = PanoramaProjection.DUAL_FISHEYE
    @Volatile var maxTextureSize = 0
        private set
    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f
    @Volatile private var fov = 75f
    @Volatile private var rollDegrees = 0f
    private var aspect = 1f
    var onVideoSurface: ((SurfaceTexture) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) surfaceTexture?.let(value)
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        surfaceTexture?.release()
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(texture).also { st ->
            st.setDefaultBufferSize(DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
            st.setOnFrameAvailableListener {
                frameAvailable.set(true)
                requestFrame()
            }
            val textureLimit = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, textureLimit, 0)
            maxTextureSize = textureLimit[0]
            onVideoSurface?.invoke(st)
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        Matrix.setIdentityM(textureMatrix, 0)
        calibrationDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        if (frameAvailable.compareAndSet(true, false)) {
            runCatching { st.updateTexImage(); st.getTransformMatrix(textureMatrix) }
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        Matrix.perspectiveM(projection, 0, fov, aspect, 0.1f, 20f)
        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        Matrix.rotateM(view, 0, rollDegrees, 0f, 0f, 1f)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, -pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, -yaw, 0f, 1f, 0f)
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        vertices.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, STRIDE, vertices)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uEquirectangular"),
            if (projectionType == PanoramaProjection.EQUIRECTANGULAR) 1f else 0f,
        )
        if (calibrationDirty) {
            bindCalibration()
            calibrationDirty = false
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertices.capacity() / 5)
    }

    fun rotate(dx: Float, dy: Float) {
        yaw = (yaw + dx) % 360f
        pitch = (pitch + dy).coerceIn(-85f, 85f)
    }

    /** Keeps drag directions screen-relative after applying a viewfinder roll. */
    fun rotateFromScreen(dx: Float, dy: Float) {
        val radians = rollDegrees / 180f * PI.toFloat()
        val cosine = cos(radians)
        val sine = sin(radians)
        rotate(
            dx = dx * cosine - dy * sine,
            dy = dx * sine + dy * cosine,
        )
    }

    fun zoom(scale: Float) {
        fov = (fov / scale).coerceIn(42f, 100f)
    }

    fun setRollDegrees(degrees: Float) {
        rollDegrees = degrees % 360f
    }

    fun recenter() { yaw = 0f; pitch = 0f; fov = 75f }

    fun setCalibration(value: PanoramaCalibration) {
        calibration = value
        calibrationDirty = true
    }

    fun setProjection(value: PanoramaProjection) {
        projectionType = value
    }

    private fun bindCalibration() {
        val current = calibration
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uUseCalibration"),
            if (current == null) 0f else 1f,
        )
        if (current == null) return
        current.lenses.forEachIndexed { index, lens ->
            val shader = lens.shaderValues
            GLES20.glUniform4fv(
                GLES20.glGetUniformLocation(program, "uQuat$index"),
                1,
                shader.quaternionXyzw,
                0,
            )
            GLES20.glUniform4fv(
                GLES20.glGetUniformLocation(program, "uLens$index"),
                1,
                shader.lens,
                0,
            )
            GLES20.glUniform4fv(
                GLES20.glGetUniformLocation(program, "uDist$index"),
                1,
                lens.distortion,
                0,
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(program, "uK5$index"),
                shader.k5,
            )
            GLES20.glUniform2fv(
                GLES20.glGetUniformLocation(program, "uTangential$index"),
                1,
                shader.tangential,
                0,
            )
        }
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
    }

    private companion object {
        const val STRIDE = 5 * 4
        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            varying vec3 vDirection;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vDirection = aPosition;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTexMatrix;
            uniform float uUseCalibration;
            uniform float uEquirectangular;
            // Quaternions are xyzw. Lens = normalized cx, cy, radial-x, radial-y.
            uniform vec4 uQuat0;
            uniform vec4 uQuat1;
            uniform vec4 uLens0;
            uniform vec4 uLens1;
            uniform vec4 uDist0;
            uniform vec4 uDist1;
            uniform float uK50;
            uniform float uK51;
            uniform vec2 uTangential0;
            uniform vec2 uTangential1;
            varying vec3 vDirection;

            // Fallback used only if an older/unknown file does not expose DJI's djmd calibration.
            vec2 idealDualFisheyeUv(vec3 direction) {
                vec3 d = normalize(direction);
                float radialLength = length(d.xy);
                float radius = acos(clamp(abs(d.z), 0.0, 1.0)) / 3.14159265359;
                vec2 radial = radialLength > 0.00001
                    ? radius * vec2(d.x, -d.y) / radialLength
                    : vec2(0.0);
                float u = d.z >= 0.0
                    ? 0.75 + 0.5 * radial.x
                    : 0.25 - 0.5 * radial.x;
                return vec2(u, 0.5 + radial.y);
            }

            vec3 rotateByQuaternion(vec4 q, vec3 v) {
                return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
            }

            float radialPolynomial(float theta, vec4 distortion, float k5) {
                float theta2 = theta * theta;
                return theta * (1.0 + theta2 * (
                    distortion.x + theta2 * (
                        distortion.y + theta2 * (
                            distortion.z + theta2 * (distortion.w + theta2 * k5)
                        )
                    )
                ));
            }

            // Returns packed-LRF uv plus this lens' feather weight and validity.
            vec4 calibratedLens(
                vec3 bodyDirection,
                vec4 quaternion,
                vec4 lens,
                vec4 distortion,
                float k5,
                vec2 tangential,
                float horizontalOffset
            ) {
                vec3 d = rotateByQuaternion(quaternion, bodyDirection);
                float theta = acos(clamp(d.z, -1.0, 1.0));
                float rho = max(length(d.xy), 0.000001);
                float radial = radialPolynomial(theta, distortion, k5);
                vec2 point = radial * d.xy / rho;
                float radius2 = dot(point, point);
                vec2 tangent = vec2(
                    2.0 * tangential.x * point.x * point.y
                        + tangential.y * (radius2 + 2.0 * point.x * point.x),
                    tangential.x * (radius2 + 2.0 * point.y * point.y)
                        + 2.0 * tangential.y * point.x * point.y
                );
                point += tangent;
                // DJMD principal points use a top-left, Y-down image origin.
                vec2 sourceUv = lens.xy + vec2(lens.z * point.x, -lens.w * point.y);
                float valid = step(theta, 1.6755160819)
                    * step(0.0, sourceUv.x) * step(sourceUv.x, 1.0)
                    * step(0.0, sourceUv.y) * step(sourceUv.y, 1.0);
                // Linear 10-degree overlap centred on each lens' 90-degree boundary.
                float weight = clamp((1.6580627894 - theta) / 0.1745329252, 0.0, 1.0) * valid;
                return vec4(horizontalOffset + sourceUv.x * 0.5, sourceUv.y, weight, valid);
            }

            void main() {
                if (uEquirectangular > 0.5) {
                    vec3 direction = normalize(vDirection);
                    float longitude = atan(direction.x, -direction.z);
                    float latitude = asin(clamp(direction.y, -1.0, 1.0));
                    vec2 sourceUv = vec2(
                        0.5 + longitude / 6.28318530718,
                        // SurfaceTexture's transform converts the producer's top-left image
                        // coordinates to GL texture coordinates, so north starts at source Y = 1.
                        0.5 + latitude / 3.14159265359
                    );
                    vec2 uv = (uTexMatrix * vec4(sourceUv, 0.0, 1.0)).xy;
                    gl_FragColor = texture2D(uTexture, uv);
                    return;
                }

                if (uUseCalibration < 0.5) {
                    // Keep the same upright screen convention as the calibrated OSV path.
                    vec3 uprightDirection = vec3(vDirection.x, -vDirection.y, vDirection.z);
                    vec2 uv = (uTexMatrix * vec4(idealDualFisheyeUv(uprightDirection), 0.0, 1.0)).xy;
                    gl_FragColor = texture2D(uTexture, uv);
                    return;
                }

                // Sphere coordinates are X right, Y up, -Z forward; DJMD's calibrated body frame
                // uses X right, Y up, +Z toward lens 0. The per-lens mount quaternions then rotate
                // this body ray into each lens' optical frame.
                vec3 bodyDirection = normalize(vec3(vDirection.x, vDirection.y, -vDirection.z));
                vec4 lens0 = calibratedLens(
                    bodyDirection, uQuat0, uLens0, uDist0, uK50, uTangential0, 0.0
                );
                vec4 lens1 = calibratedLens(
                    bodyDirection, uQuat1, uLens1, uDist1, uK51, uTangential1, 0.5
                );
                float weightSum = lens0.z + lens1.z;
                if (weightSum < 0.000001) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec2 uv0 = (uTexMatrix * vec4(lens0.xy, 0.0, 1.0)).xy;
                vec2 uv1 = (uTexMatrix * vec4(lens1.xy, 0.0, 1.0)).xy;
                vec4 color0 = texture2D(uTexture, uv0);
                vec4 color1 = texture2D(uTexture, uv1);
                gl_FragColor = mix(color0, color1, lens1.z / weightSum);
            }
        """
        const val DEFAULT_VIDEO_WIDTH = 2048
        const val DEFAULT_VIDEO_HEIGHT = 1024
    }
}

private fun sphereVertices(slices: Int, stacks: Int): FloatBuffer {
    val data = ArrayList<Float>(slices * stacks * 6 * 5)
    fun vertex(lat: Int, lon: Int) {
        val v = lat.toFloat() / stacks
        val u = lon.toFloat() / slices
        val phi = (v - 0.5f) * PI.toFloat()
        val theta = u * PI.toFloat() * 2f
        val radius = cos(phi)
        data += radius * sin(theta)
        data += sin(phi)
        data += -radius * cos(theta)
        data += 1f - u
        data += 1f - v
    }
    for (lat in 0 until stacks) {
        for (lon in 0 until slices) {
            vertex(lat, lon); vertex(lat + 1, lon); vertex(lat + 1, lon + 1)
            vertex(lat, lon); vertex(lat + 1, lon + 1); vertex(lat, lon + 1)
        }
    }
    return ByteBuffer.allocateDirect(data.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { data.forEach(::put); position(0) }
}

private fun linkProgram(vertex: String, fragment: String): Int {
    fun compile(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        require(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
    }
    val v = compile(GLES20.GL_VERTEX_SHADER, vertex)
    val f = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
    return GLES20.glCreateProgram().also { program ->
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        require(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
    }
}

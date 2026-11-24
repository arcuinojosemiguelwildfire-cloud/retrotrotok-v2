package com.example.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.example.model.RetroEffectType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * OpenGL ES 2.0 + EGL14 offscreen frame composition renderer for Android 6.0.1 (API 23)+.
 * Bakes portrait 9:16 center-crop, rotation alignment, optional mirroring, and permanent
 * retro visual shaders directly into the H.264 encoder input Surface.
 */
class GlEffectRenderer(
    private val encoderInputSurface: Surface,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val cropTransform: PortraitCropTransform,
    private val effect: RetroEffectType
) {

    companion object {
        private const val TAG = "GlEffectRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES

        private val QUAD_VERTICES = floatArrayOf(
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0f, 0f, 0f,
             1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f,  1.0f, 0f, 0f, 1f,
             1.0f,  1.0f, 0f, 1f, 1f
        )

        private const val VERTEX_SHADER = """
            uniform mat4 uSTMatrix;
            uniform mat4 uUserMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            varying vec2 vScreenCoord;
            void main() {
                gl_Position = aPosition;
                vScreenCoord = aTextureCoord.xy;
                vec4 transformed = uUserMatrix * vec4(aTextureCoord.xy, 0.0, 1.0);
                vTextureCoord = (uSTMatrix * transformed).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;

            varying vec2 vTextureCoord;
            varying vec2 vScreenCoord;

            uniform samplerExternalOES sTexture;
            uniform int uEffectMode;
            uniform float uTimeSec;
            uniform vec2 uTexelSize;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uFadeLift;
            uniform float uWarmth;
            uniform float uChromaShift;
            uniform float uGrainStrength;
            uniform float uTrackingStrength;
            uniform float uSoftness;

            float rand(vec2 co) {
                return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
            }

            vec3 adjustContrastSaturation(vec3 color, float contrast, float saturation) {
                float luma = dot(color, vec3(0.299, 0.587, 0.114));
                vec3 satColor = mix(vec3(luma), color, saturation);
                return clamp((satColor - 0.5) * contrast + 0.5, 0.0, 1.0);
            }

            void main() {
                vec2 uv = vTextureCoord;

                // Subtle analog tracking jitter for VHS Retro
                if (uTrackingStrength > 0.0001) {
                    float band = sin(vScreenCoord.y * 28.0 + uTimeSec * 9.0);
                    float pulse = step(0.92, sin(vScreenCoord.y * 6.0 - uTimeSec * 2.5));
                    uv.x += (band * 0.35 + pulse) * uTrackingStrength;
                }

                uv = clamp(uv, vec2(0.001), vec2(0.999));

                vec3 baseColor = texture2D(sTexture, uv).rgb;

                // Optional subtle horizontal softness (VHS / 90s camcorder)
                if (uSoftness > 0.01) {
                    vec3 leftTap = texture2D(sTexture, clamp(uv - vec2(uTexelSize.x * 1.5, 0.0), vec2(0.001), vec2(0.999))).rgb;
                    vec3 rightTap = texture2D(sTexture, clamp(uv + vec2(uTexelSize.x * 1.5, 0.0), vec2(0.001), vec2(0.999))).rgb;
                    vec3 softColor = baseColor * 0.5 + (leftTap + rightTap) * 0.25;
                    baseColor = mix(baseColor, softColor, uSoftness);
                }

                // Subtle chromatic aberration (VHS / 80s Neon / 90s Camcorder)
                if (uChromaShift > 0.0001) {
                    float rShift = texture2D(sTexture, clamp(uv + vec2(uChromaShift, 0.0), vec2(0.001), vec2(0.999))).r;
                    float bShift = texture2D(sTexture, clamp(uv - vec2(uChromaShift, 0.0), vec2(0.001), vec2(0.999))).b;
                    baseColor.r = mix(baseColor.r, rShift, 0.65);
                    baseColor.b = mix(baseColor.b, bShift, 0.65);
                }

                if (uEffectMode == 0) {
                    gl_FragColor = vec4(baseColor, 1.0);
                    return;
                }

                // Apply contrast & saturation grading
                vec3 graded = adjustContrastSaturation(baseColor, uContrast, uSaturation);

                // Apply mode-specific color personality
                if (uEffectMode == 1) {
                    // VHS_RETRO: slight magenta shadow tint & warm analog bias
                    graded += vec3(0.03, -0.01, 0.04) * (1.0 - dot(graded, vec3(0.333)));
                } else if (uEffectMode == 2) {
                    // NEON_80S: cyan shadows, vibrant magenta highlights
                    float lum = dot(graded, vec3(0.299, 0.587, 0.114));
                    vec3 shadowGlow = vec3(0.0, 0.12, 0.18) * (1.0 - lum);
                    vec3 highlightGlow = vec3(0.18, 0.0, 0.14) * lum;
                    graded = clamp(graded + shadowGlow + highlightGlow, 0.0, 1.0);
                } else if (uEffectMode == 3) {
                    // VINTAGE_70S: warm amber/sepia tint + lifted faded blacks
                    graded.r = clamp(graded.r + uWarmth * 0.45, 0.0, 1.0);
                    graded.g = clamp(graded.g + uWarmth * 0.20, 0.0, 1.0);
                    graded.b = clamp(graded.b - uWarmth * 0.30, 0.0, 1.0);
                } else if (uEffectMode == 4) {
                    // FILM_90S: cool green-cyan camcorder balance
                    graded.r = clamp(graded.r * 0.96, 0.0, 1.0);
                    graded.g = clamp(graded.g * 1.03, 0.0, 1.0);
                    graded.b = clamp(graded.b * 0.95 + 0.02, 0.0, 1.0);
                } else if (uEffectMode == 5) {
                    // BW_NOIR: crisp monochrome silver look
                    float mono = dot(graded, vec3(0.299, 0.587, 0.114));
                    graded = vec3(mono);
                }

                // Lifted film blacks (fade)
                if (uFadeLift > 0.001) {
                    graded = graded * (1.0 - uFadeLift) + vec3(uFadeLift);
                }

                // Subtle organic grain / analog noise
                if (uGrainStrength > 0.001) {
                    float noise = (rand(vScreenCoord + fract(uTimeSec * 1.37)) - 0.5) * uGrainStrength;
                    graded = clamp(graded + vec3(noise), 0.0, 1.0);
                }

                gl_FragColor = vec4(graded, 1.0);
            }
        """
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programId: Int = 0
    private var oesTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var decoderOutputSurface: Surface? = null

    private val frameSyncLock = Object()
    private var frameAvailable = false

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * FLOAT_SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    private val stMatrix = FloatArray(16)
    private val userMatrix = FloatArray(16)

    private var aPositionHandle = -1
    private var aTextureCoordHandle = -1
    private var uSTMatrixHandle = -1
    private var uUserMatrixHandle = -1
    private var uEffectModeHandle = -1
    private var uTimeSecHandle = -1
    private var uTexelSizeHandle = -1
    private var uContrastHandle = -1
    private var uSaturationHandle = -1
    private var uFadeLiftHandle = -1
    private var uWarmthHandle = -1
    private var uChromaShiftHandle = -1
    private var uGrainStrengthHandle = -1
    private var uTrackingStrengthHandle = -1
    private var uSoftnessHandle = -1

    private val effectSpec = EffectShaderConfig.getSpec(effect)

    fun initialize(): Surface {
        setupEgl()
        makeCurrent()
        setupGlProgram()
        setupExternalTextureAndSurface()
        return decoderOutputSurface ?: throw IllegalStateException("Failed to create decoder Surface")
    }

    fun awaitNewImage(timeoutMs: Long = 2500L): Boolean {
        synchronized(frameSyncLock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    return false
                }
                try {
                    frameSyncLock.wait(remaining)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            frameAvailable = false
        }
        surfaceTexture?.updateTexImage()
        return true
    }

    fun drawFrame(presentationTimeUs: Long) {
        val st = surfaceTexture ?: return
        st.getTransformMatrix(stMatrix)
        buildUserUvMatrix(stMatrix, userMatrix)

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val vpX = if (cropTransform.viewportWidth > 0) cropTransform.viewportX else 0
        val vpY = if (cropTransform.viewportHeight > 0) cropTransform.viewportY else 0
        val vpW = if (cropTransform.viewportWidth > 0) cropTransform.viewportWidth else outputWidth
        val vpH = if (cropTransform.viewportHeight > 0) cropTransform.viewportHeight else outputHeight
        GLES20.glViewport(vpX, vpY, vpW, vpH)

        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aPositionHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(
            aTextureCoordHandle, 2, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)

        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uUserMatrixHandle, 1, false, userMatrix, 0)
        GLES20.glUniform1i(uEffectModeHandle, effectSpec.shaderModeId)
        GLES20.glUniform1f(uTimeSecHandle, presentationTimeUs / 1_000_000.0f)
        GLES20.glUniform2f(uTexelSizeHandle, 1.0f / vpW.toFloat(), 1.0f / vpH.toFloat())
        GLES20.glUniform1f(uContrastHandle, effectSpec.contrast)
        GLES20.glUniform1f(uSaturationHandle, effectSpec.saturation)
        GLES20.glUniform1f(uFadeLiftHandle, effectSpec.fadeLift)
        GLES20.glUniform1f(uWarmthHandle, effectSpec.warmth)
        GLES20.glUniform1f(uChromaShiftHandle, effectSpec.chromaShift)
        GLES20.glUniform1f(uGrainStrengthHandle, effectSpec.grainStrength)
        GLES20.glUniform1f(uTrackingStrengthHandle, effectSpec.trackingStrength)
        GLES20.glUniform1f(uSoftnessHandle, effectSpec.softness)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeUs * 1000L)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun buildUserUvMatrix(currentStMatrix: FloatArray, outMatrix: FloatArray) {
        Matrix.setIdentityM(outMatrix, 0)
        // Center around (0.5, 0.5) in screen UV space
        Matrix.translateM(outMatrix, 0, 0.5f, 0.5f, 0f)

        // Check whether SurfaceTexture already embeds 90/270 rotation in stMatrix
        val stAlreadyRotated = abs(currentStMatrix[0]) < 0.1f && abs(currentStMatrix[1]) > 0.5f
        if (!stAlreadyRotated && cropTransform.rotationDegrees != 0) {
            Matrix.rotateM(outMatrix, 0, cropTransform.rotationDegrees.toFloat(), 0f, 0f, 1f)
        }

        // Apply center-crop UV scaling + optional horizontal mirroring
        val mirrorScaleX = if (cropTransform.needsMirroring) -1f else 1f
        Matrix.scaleM(
            outMatrix,
            0,
            cropTransform.uvScaleX * mirrorScaleX,
            cropTransform.uvScaleY,
            1f
        )

        Matrix.translateM(outMatrix, 0, -0.5f, -0.5f, 0f)
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL14")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("Unable to find RGB888+recordable EGL config")
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create OpenGL ES 2.0 EGLContext")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderInputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Failed to create EGLSurface from encoder input Surface")
        }
    }

    private fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun setupGlProgram() {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        programId = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("Shader program link failed: $log")
            }
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        uUserMatrixHandle = GLES20.glGetUniformLocation(programId, "uUserMatrix")
        uEffectModeHandle = GLES20.glGetUniformLocation(programId, "uEffectMode")
        uTimeSecHandle = GLES20.glGetUniformLocation(programId, "uTimeSec")
        uTexelSizeHandle = GLES20.glGetUniformLocation(programId, "uTexelSize")
        uContrastHandle = GLES20.glGetUniformLocation(programId, "uContrast")
        uSaturationHandle = GLES20.glGetUniformLocation(programId, "uSaturation")
        uFadeLiftHandle = GLES20.glGetUniformLocation(programId, "uFadeLift")
        uWarmthHandle = GLES20.glGetUniformLocation(programId, "uWarmth")
        uChromaShiftHandle = GLES20.glGetUniformLocation(programId, "uChromaShift")
        uGrainStrengthHandle = GLES20.glGetUniformLocation(programId, "uGrainStrength")
        uTrackingStrengthHandle = GLES20.glGetUniformLocation(programId, "uTrackingStrength")
        uSoftnessHandle = GLES20.glGetUniformLocation(programId, "uSoftness")
    }

    private fun setupExternalTextureAndSurface() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTextureId)
        st.setOnFrameAvailableListener {
            synchronized(frameSyncLock) {
                frameAvailable = true
                frameSyncLock.notifyAll()
            }
        }
        surfaceTexture = st
        decoderOutputSurface = Surface(st)
    }

    private fun compileShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed ($shaderType): $log")
        }
        return shader
    }

    fun release() {
        try {
            decoderOutputSurface?.release()
            decoderOutputSurface = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing decoder surface: ${e.message}")
        }
        try {
            surfaceTexture?.release()
            surfaceTexture = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing SurfaceTexture: ${e.message}")
        }
        try {
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
        } catch (_: Exception) {
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            } catch (_: Exception) {
            }
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}

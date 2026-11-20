package com.enweilo.fujirecipes.shoot

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The GPU program that turns a phone picture into a recipe look. Used both by the live viewfinder (camera frames,
 * [external] = true) and by the full-size photo processing (a bitmap texture).
 *
 * Must be created and used on a thread with a current GLES 3 context.
 */
class LookProgram(private val external: Boolean) {

    private val program: Int
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
    private val loc = HashMap<String, Int>()

    private var lutTex = 0
    private var lutKey: String? = null
    /** true when the loaded LUT is Fujifilm's own F-Log2 one (scene light in), false for the baked phone LUT */
    private var lutFlog = false
    private var lutAskedFlog = false
    private var curveTex = 0
    private var curveVersion = -1
    private var staticCurve: FloatArray? = null
    /** the curve and colour fix in use, for drawing */
    private var flogReady = false

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment(external))
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glBindAttribLocation(program, 0, "aPos")
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("link: " + GLES30.glGetProgramInfoLog(program))
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    private fun u(name: String): Int = loc.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    /**
     * Makes the 3D texture hold [key]'s LUT. Returns false when that LUT is not installed. Prefers Fujifilm's
     * F-Log2 LUT with the scene-light path (the same one RAW photos take); falls back to the baked phone LUT.
     */
    fun useLut(key: String?, store: LutStore): Boolean {
        if (key == null) return false
        val wantFlog = syncCurve(store)
        if (key == lutKey && lutTex != 0 && lutAskedFlog == wantFlog) return true
        val flogData = if (wantFlog) store.loadFlog(key) else null
        val data = flogData ?: store.load(key) ?: return false
        lutFlog = flogData != null
        lutAskedFlog = wantFlog
        if (lutTex == 0) {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            lutTex = t[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val n = LutStore.GRID
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, n, n, n, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, data,
        )
        lutKey = key
        return true
    }

    /** Forget the loaded LUT (after a new download). */
    fun resetLut() {
        lutKey = null
        staticCurve = null
        curveVersion = -1
    }

    /** Keeps the curve texture in step with [ToneCalib]. Returns true when the scene-light path can be used. */
    private fun syncCurve(store: LutStore): Boolean {
        if (staticCurve == null) staticCurve = store.phoneCurve()
        val c = ToneCalib.curve ?: staticCurve ?: run { flogReady = false; return false }
        val v = ToneCalib.version
        if (curveTex == 0) {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            curveTex = t[0]
            curveVersion = -1
        }
        if (v != curveVersion) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val b = ByteBuffer.allocateDirect(256 * 2).order(ByteOrder.nativeOrder())
            for (x in c) b.putShort(android.util.Half.toHalf(x))
            b.position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, 256, 1, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, b)
            curveVersion = v
        }
        flogReady = true
        return true
    }

    /**
     * Draws a full-viewport quad.
     * @param srcTex camera (external) texture or a 2D texture
     * @param texMat 4×4 matrix from SurfaceTexture, or identity
     * @param rot 3×3 column-major matrix mapping view coordinates to picture coordinates (rotation / mirroring)
     * @param crop part of the (rotated) picture shown: x, y, width, height in 0..1
     * @param tile part of the whole photo this draw covers, for the grain pattern
     * @param aspect height / width of the whole photo, so the grain stays round
     */
    fun draw(
        srcTex: Int, texMat: FloatArray, rot: FloatArray, crop: FloatArray, tile: FloatArray, aspect: Float,
        look: Look, hasLut: Boolean, original: Boolean,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, srcTex)
        GLES30.glUniform1i(u("uSrc"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, if (hasLut) lutTex else 0)
        GLES30.glUniform1i(u("uLut"), 1)
        val flog = hasLut && lutFlog && flogReady
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (flog) curveTex else 0)
        GLES30.glUniform1i(u("uCurve"), 2)
        GLES30.glUniform1f(u("uFlog"), if (flog) 1f else 0f)
        GLES30.glUniform3fv(u("uFix"), 1, ToneCalib.fix, 0)
        GLES30.glUniform3fv(u("uWb"), 1, look.wbAdapt, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        GLES30.glUniformMatrix4fv(u("uTexMat"), 1, false, texMat, 0)
        GLES30.glUniformMatrix3fv(u("uRot"), 1, false, rot, 0)
        GLES30.glUniform4fv(u("uCrop"), 1, crop, 0)
        GLES30.glUniform4fv(u("uTile"), 1, tile, 0)
        GLES30.glUniform1f(u("uAspect"), aspect)

        GLES30.glUniform1f(u("uOrig"), if (original) 1f else 0f)
        GLES30.glUniform1f(u("uHasLut"), if (hasLut) 1f else 0f)
        GLES30.glUniform3fv(u("uGain"), 1, look.gain, 0)
        GLES30.glUniform1f(u("uDr"), look.dr)
        GLES30.glUniform1f(u("uPreMono"), if (look.preMono) 1f else 0f)
        GLES30.glUniform3fv(u("uMonoW"), 1, look.monoW, 0)
        GLES30.glUniform1f(u("uPostMono"), if (look.postMono) 1f else 0f)
        GLES30.glUniform3fv(u("uTint"), 1, look.tint, 0)
        GLES30.glUniform1f(u("uContrast"), look.contrast)
        GLES30.glUniform1f(u("uWarmHi"), look.warmHi)
        GLES30.glUniform1f(u("uSat"), look.sat)
        GLES30.glUniform1f(u("uHi"), look.hi)
        GLES30.glUniform1f(u("uSh"), look.sh)
        GLES30.glUniform1f(u("uChrome"), look.chrome)
        GLES30.glUniform1f(u("uChromeBlue"), look.chromeBlue)
        GLES30.glUniform1f(u("uGrain"), look.grain)
        GLES30.glUniform1f(u("uCells"), look.grainCells)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
    }

    fun release() {
        if (lutTex != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTex), 0)
        if (curveTex != 0) GLES30.glDeleteTextures(1, intArrayOf(curveTex), 0)
        GLES30.glDeleteProgram(program)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) error("shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        val IDENTITY4 = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val IDENTITY3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val FULL = floatArrayOf(0f, 0f, 1f, 1f)

        /**
         * Column-major 3×3 matrix from view coordinates (origin bottom-left) to the camera picture, which must be
         * turned [degrees] clockwise to stand upright. [mirror] flips the view sideways (front camera).
         */
        fun rotation(degrees: Int, mirror: Boolean): FloatArray {
            // u, v as functions of x, y:   u = a*x + b*y + c,  v = d*x + e*y + f
            val (a, b, c, d, e, f) = when (((degrees % 360) + 360) % 360) {
                90 -> Six(0f, -1f, 1f, 1f, 0f, 0f)
                180 -> Six(-1f, 0f, 1f, 0f, -1f, 1f)
                270 -> Six(0f, 1f, 0f, -1f, 0f, 1f)
                else -> Six(1f, 0f, 0f, 0f, 1f, 0f)
            }
            // mirror: x -> 1 - x
            return if (!mirror) floatArrayOf(a, d, 0f, b, e, 0f, c, f, 1f)
            else floatArrayOf(-a, -d, 0f, b, e, 0f, c + a, f + d, 1f)
        }

        private data class Six(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)

        private const val VERTEX = """#version 300 es
in vec2 aPos;
uniform mat4 uTexMat;
uniform mat3 uRot;
uniform vec4 uCrop;
uniform vec4 uTile;
out vec2 vSrc;
out vec2 vFull;
void main() {
    vec2 q = uCrop.xy + aPos * uCrop.zw;
    vec2 b = (uRot * vec3(q, 1.0)).xy;
    vSrc = (uTexMat * vec4(b, 0.0, 1.0)).xy;
    vFull = uTile.xy + aPos * uTile.zw;
    gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private fun fragment(external: Boolean): String {
            val head = if (external) {
                "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision highp float;\nuniform samplerExternalOES uSrc;\n"
            } else {
                "#version 300 es\nprecision highp float;\nuniform sampler2D uSrc;\n"
            }
            return head + FRAG_BODY
        }

        private const val FRAG_BODY = """
precision highp sampler3D;
uniform sampler3D uLut;
uniform sampler2D uCurve;
uniform float uFlog;
uniform vec3 uFix, uWb;
uniform float uAspect, uOrig, uHasLut, uDr, uPreMono, uPostMono, uContrast, uWarmHi, uSat, uHi, uSh;
uniform float uChrome, uChromeBlue, uGrain, uCells;
uniform vec3 uGain, uMonoW, uTint;
in vec2 vSrc;
in vec2 vFull;
out vec4 o;

const vec3 W = vec3(0.2126, 0.7152, 0.0722);

vec3 dec(vec3 v) { return mix(v / 12.92, pow((v + 0.055) / 1.055, vec3(2.4)), step(0.04045, v)); }
vec3 enc(vec3 l) { l = max(l, 0.0); return mix(l * 12.92, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, l)); }

float flog2(float x) {
    return x >= 0.000889 ? 0.245281 * log(5.555556 * x + 0.064829) / log(10.0) + 0.384316 : 8.799461 * x + 0.092864;
}

float hash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    vec3 src = texture(uSrc, vSrc).rgb;
    if (uOrig > 0.5) { o = vec4(src, 1.0); return; }

    vec3 c;
    if (uFlog > 0.5) {
        // the RAW photo's path: back to scene light, then exactly what RAW does
        vec3 lin = dec(src);
        float y = dot(lin, W);
        float yd = clamp(enc(vec3(y)).g, 0.0, 1.0);
        float ys = texture(uCurve, vec2(yd * (255.0 / 256.0) + 0.5 / 256.0, 0.5)).r;
        vec3 scene = y > 1e-6 ? lin * (ys / y) : vec3(0.0);
        scene *= uFix * uWb * uGain;
        float ysc = dot(scene, W);
        if (uDr > 0.0 && ysc > 0.18) {
            float yc = 0.18 * pow(ysc / 0.18, 1.0 / (1.0 + 0.16 * uDr));
            scene *= yc / ysc;
        }
        if (uPreMono > 0.5) scene = vec3(dot(scene, uMonoW));
        vec3 fg = mat3(0.6274, 0.0691, 0.0164, 0.3293, 0.9195, 0.0880, 0.0433, 0.0114, 0.8956) * max(scene, 0.0);
        vec3 code = vec3(flog2(fg.r), flog2(fg.g), flog2(fg.b));
        c = texture(uLut, clamp(code, 0.0, 1.0) * (32.0 / 33.0) + 0.5 / 33.0).rgb;
    } else {
        // white balance shift, in linear light
        vec3 lin = dec(src) * uGain;
        // dynamic range 200 / 400: hold the highlights back a little
        float y = dot(lin, W);
        if (uDr > 0.0 && y > 0.0) {
            float yc = y / (1.0 + uDr * 0.22 * max(y - 0.18, 0.0));
            lin *= yc / y;
        }
        if (uPreMono > 0.5) lin = vec3(dot(lin, uMonoW));
        vec3 d = clamp(enc(lin), 0.0, 1.0);

        // film simulation
        c = d;
        if (uHasLut > 0.5) c = texture(uLut, d * (32.0 / 33.0) + 0.5 / 33.0).rgb;
    }

    if (uContrast != 0.0) c = clamp(mix(c, c * c * (3.0 - 2.0 * c), uContrast), 0.0, 1.0);

    // highlight / shadow tone (Fujifilm: + means harder)
    float L = dot(c, W);
    float hb = L * L * (1.0 - L) * 4.0;
    float sb = L * (1.0 - L) * (1.0 - L) * 4.0;
    float L2 = clamp(L + 0.07 * uHi * hb - 0.07 * uSh * sb, 0.0, 1.0);
    if (L > 0.0001) c *= L2 / L;

    // colour chrome: deeper tones in strongly coloured areas
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float sat = mx > 0.0001 ? (mx - mn) / mx : 0.0;
    c *= 1.0 - uChrome * 0.13 * sat * sat * smoothstep(0.15, 0.85, mx);
    float blue = clamp((c.b - max(c.r, c.g)) / max(mx, 0.001) * 2.0, 0.0, 1.0);
    c *= 1.0 - uChromeBlue * 0.16 * blue * sat;

    // colour (saturation)
    L = dot(c, W);
    c = mix(vec3(L), c, uSat);
    c += uWarmHi * smoothstep(0.35, 1.0, L) * vec3(0.045, 0.018, -0.045);

    if (uPostMono > 0.5) c = vec3(dot(c, W)) * uTint;

    // grain, strongest in the mid tones
    if (uGrain > 0.0) {
        vec2 g = vFull * vec2(uCells, uCells * uAspect);
        float n = vnoise(g) + vnoise(g * 1.9 + 17.0) * 0.5 - 0.75;
        L = dot(c, W);
        c += n * uGrain * (0.35 + 1.3 * L * (1.0 - L));
    }
    o = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}

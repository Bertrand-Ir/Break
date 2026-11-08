package app.breakpiano.learn

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectral noise reduction: learns the steady background (fan, hum, hiss, distant TV) from
 * the quietest moments of the recording and subtracts it from every frame, so pitch detection
 * only hears the voice. Short-time Fourier transform, 512-point Hann window, 75 % overlap.
 */
internal object Denoise {
    private const val N = 512
    private const val H = 128
    private const val BINS = N / 2 + 1

    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }

    /** @param strength how hard to subtract the noise profile (1.0 gentle … 2.0 strong). */
    fun apply(x: FloatArray, strength: Float): FloatArray {
        if (x.size < N * 4) return x
        val frames = (x.size - N) / H + 1
        val re = FloatArray(N); val im = FloatArray(N)

        // pass 1: the noise floor of each frequency (20th percentile of its magnitude over time)
        val step = max(1, frames / 1500)
        val sampled = (0 until frames step step).toList()
        val mags = Array(BINS) { FloatArray(sampled.size) }
        for ((si, f) in sampled.withIndex()) {
            frame(x, f * H, re, im)
            fft(re, im, false)
            for (k in 0 until BINS) mags[k][si] = sqrt(re[k] * re[k] + im[k] * im[k])
        }
        val noise = FloatArray(BINS) { k -> mags[k].sorted()[(sampled.size * 0.2).toInt()] }

        // pass 2: subtract, smooth the gains over time (avoids "musical noise"), resynthesise
        val out = FloatArray(x.size)
        val norm = FloatArray(x.size)
        val prevGain = FloatArray(BINS) { 1f }
        for (f in 0 until frames) {
            val o = f * H
            frame(x, o, re, im)
            fft(re, im, false)
            for (k in 0 until BINS) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                var g = if (m > 1e-9f) 1f - strength * noise[k] / m else 0f
                g = g.coerceIn(0.06f, 1f)
                g = 0.6f * g + 0.4f * prevGain[k]
                prevGain[k] = g
                re[k] *= g; im[k] *= g
                if (k in 1 until N / 2) { re[N - k] = re[k]; im[N - k] = -im[k] }
            }
            fft(re, im, true)
            for (j in 0 until N) {
                out[o + j] += re[j] * window[j]
                norm[o + j] += window[j] * window[j]
            }
        }
        for (i in out.indices) out[i] = if (norm[i] > 1e-6f) out[i] / norm[i] else 0f
        return out
    }

    private fun frame(x: FloatArray, o: Int, re: FloatArray, im: FloatArray) {
        for (j in 0 until N) { re[j] = (if (o + j < x.size) x[o + j] else 0f) * window[j]; im[j] = 0f }
    }

    /** In-place iterative radix-2 FFT (inverse scales by 1/N). */
    fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}

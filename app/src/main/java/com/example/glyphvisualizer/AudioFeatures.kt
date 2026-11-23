package com.example.glyphvisualizer

import kotlin.math.*

internal fun follow(value: Double, target: Double, dt: Double, tau: Double): Double =
    value + (target - value) * (1.0 - exp(-dt / max(0.001, tau)))
internal fun unit(v: Double) = v.coerceIn(0.0, 1.0)
internal fun ease(v: Double): Double { val t = unit(v); return t * t * (3.0 - 2.0 * t) }
internal fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t

/** Один экземпляр принадлежит одному audio/engine-потоку. Выход переиспользуется. */
class AudioFeatures {
    val energy = DoubleArray(3) // bass:35–250, mid:250–2500, high:2500–12000 Hz
    val flux = DoubleArray(3)   // положительное изменение спектра / текущая+предыдущая энергия
    var level = 0.0
    var flatness = 0.0
    var centroid = 0.0
    var novelty = 0.0
    // Low/mid spectral occupancy is independent of overall volume and of bright hi-hats.
    var bodyTexture = 0.0
    var bodyShare = 0.0
    var tonalContrast = 0.0
    var spectralContinuity = 0.0
    // Salient-spectrum log centre, a register/timbre proxy, not fundamental pitch.
    var leadRegister = 0.0
    val subEnergy = DoubleArray(6)
}

class SpectrumAnalyzer {
    val features = AudioFeatures()
    private var previous = DoubleArray(0)
    private val prevBands = DoubleArray(3)
    private val sums = DoubleArray(3)
    private val positive = DoubleArray(3)
    private val shape = DoubleArray(3)
    private val power = DoubleArray(6)
    private var initialized = false
    private var previousSampleRate = 0
    private var previousFftSize = 0

    fun reset() {
        previous.fill(0.0); prevBands.fill(0.0); initialized = false
        previousSampleRate = 0; previousFftSize = 0
    }

    /** magnitudes: линейные амплитуды; масштаб не влияет на доли/flux/flatness. */
    fun analyze(magnitudes: DoubleArray, sampleRate: Int, fftSize: Int): AudioFeatures {
        require(sampleRate > 0 && fftSize > 1 && magnitudes.size >= 2) { "Invalid FFT configuration" }
        // Identical bin indices correspond to different frequencies after an audio-route change.
        // Comparing them would invent a strong onset/novelty event on the first new frame.
        if (previous.size != magnitudes.size) previous = DoubleArray(magnitudes.size)
        if (sampleRate != previousSampleRate || fftSize != previousFftSize) {
            reset()
            previousSampleRate = sampleRate; previousFftSize = fftSize
        }
        sums.fill(0.0); positive.fill(0.0); power.fill(0.0)
        var tonal=0.0; var lead=0.0; var bandSum=0.0
        var dot=0.0; var nowSquare=0.0; var oldSquare=0.0
        var bodySum=0.0; var bodySquare=0.0; var bodyBins=0
        var sum = 0.0; var logSum = 0.0; var weighted = 0.0; var count = 0
        for (k in 1 until min(magnitudes.size, fftSize / 2)) {
            val hz = k.toDouble() * sampleRate / fftSize
            val m = max(0.0, magnitudes[k])
            if (hz >= 35.0 && hz < min(12000.0, sampleRate * 0.49)) {
                val b = if (hz < 250) 0 else if (hz < 2500) 1 else 2
                if(hz>300 && hz<6000 && k>=3 && k+3<magnitudes.size) {
                    val salient=max(0.0,m-(magnitudes[k-3]+magnitudes[k+3])*.5)
                    tonal+=salient; lead+=salient*ln(hz); bandSum+=m
                    dot+=m*previous[k];nowSquare+=m*m;oldSquare+=previous[k]*previous[k]
                }
                sums[b] += m
                val sub=when { hz<120 -> 0; hz<300 -> 1; hz<800 -> 2; hz<2500 -> 3; hz<6000 -> 4; else -> 5 }
                power[sub]+=m*m
                if(hz>=120 && hz<2500) { bodySum+=m; bodySquare+=m*m; bodyBins++ }
                positive[b] += max(0.0, m - previous[k])
                sum += m; weighted += m * hz; logSum += ln(max(1e-9, m)); count++
            }
            previous[k] = m
        }
        features.tonalContrast=if(bandSum>1e-12) unit(tonal/bandSum) else 0.0
        features.spectralContinuity=if(initialized && nowSquare*oldSquare>1e-24) unit(dot/sqrt(nowSquare*oldSquare)) else 0.0
        features.leadRegister=if(tonal>1e-12) lead/tonal else 0.0
        val powerSum=power.sum()
        for(b in 0..5) features.subEnergy[b]=if(powerSum>1e-16) power[b]/powerSum else 0.0
        features.bodyShare=(features.subEnergy[0]+features.subEnergy[1]+features.subEnergy[2]+features.subEnergy[3])
        val occupancy=bodySum*bodySum/max(1e-16,bodySquare*bodyBins)
        features.bodyTexture=ease((occupancy-.10)/.55)
        features.level = sum
        features.centroid = if (sum > 1e-8) weighted / sum else 0.0
        features.flatness = if (sum > 1e-8 && count > 0) unit(exp(logSum / count) / (sum / count)) else 0.0
        var novelty = 0.0
        for (b in 0..2) {
            val share = if (sum > 1e-8) sums[b] / sum else 0.0
            features.energy[b] = share
            features.flux[b] = if (initialized && sum > 1e-8)
                unit(positive[b] / max(sum * 0.025, (sums[b] + prevBands[b]) * 0.5)) else 0.0
            novelty += abs(share - shape[b])
            shape[b] = share; prevBands[b] = sums[b]
        }
        features.novelty = if (initialized) novelty * 0.5 else 0.0
        initialized = true
        return features
    }
}

/** Одинаковое окно/FFT для офлайн-калибровки и тестового PCM. */
class PcmSpectrum(val size: Int = 1024) {
    private val real = DoubleArray(size)
    private val imag = DoubleArray(size)
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }
    val magnitudes = DoubleArray(size / 2)
    init { require(size > 1 && size and (size - 1) == 0) }
    fun transform(pcm: DoubleArray): DoubleArray {
        for (i in 0 until size) { real[i] = pcm[i] * window[i]; imag[i] = 0.0 }
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val v = real[i]; real[i] = real[j]; real[j] = v
            }
        }
        var len = 2
        while (len <= size) {
            val wr = cos(-2.0 * PI / len); val wi = sin(-2.0 * PI / len)
            var start = 0
            while (start < size) {
                var ar = 1.0; var ai = 0.0
                for (k in 0 until len / 2) {
                    val p = start + k; val q = p + len / 2
                    val tr = ar * real[q] - ai * imag[q]; val ti = ar * imag[q] + ai * real[q]
                    real[q] = real[p] - tr; imag[q] = imag[p] - ti
                    real[p] += tr; imag[p] += ti
                    val nr = ar * wr - ai * wi; ai = ar * wi + ai * wr; ar = nr
                }
                start += len
            }
            len *= 2
        }
        for (k in magnitudes.indices) magnitudes[k] = hypot(real[k], imag[k]) / size
        return magnitudes
    }
}

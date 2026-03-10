package org.emerge.render.torus.shader

import kotlin.math.floor

object NoiseTexture {
    fun createTileable(size: Int): ByteArray {
        val data = ByteArray(size * size)
        for (y in 0 until size) {
            val v = y.toFloat() / size.toFloat()
            for (x in 0 until size) {
                val u = x.toFloat() / size.toFloat()
                val noise = fractalNoise(u, v)
                val centered = (noise * 0.85f + 0.075f).coerceIn(0f, 1f)
                data[y * size + x] = (centered * 255f + 0.5f).toInt().toByte()
            }
        }
        return data
    }

    private fun fractalNoise(u: Float, v: Float): Float {
        var value = 0f
        var amplitude = 1f
        var totalAmplitude = 0f
        var cells = 16
        repeat(4) {
            value += sampleTileableValueNoise(u * cells, v * cells, cells) * amplitude
            totalAmplitude += amplitude
            amplitude *= 0.5f
            cells *= 2
        }
        return value / totalAmplitude
    }

    private fun sampleTileableValueNoise(x: Float, y: Float, period: Int): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val tx = x - x0
        val ty = y - y0

        val v00 = hashToUnitFloat(x0, y0, period)
        val v10 = hashToUnitFloat(x0 + 1, y0, period)
        val v01 = hashToUnitFloat(x0, y0 + 1, period)
        val v11 = hashToUnitFloat(x0 + 1, y0 + 1, period)

        val sx = smoothstep(tx)
        val sy = smoothstep(ty)
        val ix0 = mix(v00, v10, sx)
        val ix1 = mix(v01, v11, sx)
        return mix(ix0, ix1, sy)
    }

    private fun hashToUnitFloat(x: Int, y: Int, period: Int): Float {
        val wrappedX = positiveMod(x, period)
        val wrappedY = positiveMod(y, period)
        var h = wrappedX * 0x1F1F1F1F xor wrappedY * 0x45D9F3B
        h = h xor (h ushr 16)
        h *= 0x7FEB352D
        h = h xor (h ushr 15)
        h *= 0x846CA68B.toInt()
        h = h xor (h ushr 16)
        return (h ushr 8 and 0x00FFFFFF) / 16777215f
    }

    private fun positiveMod(value: Int, modulus: Int): Int {
        val remainder = value % modulus
        return if (remainder < 0) remainder + modulus else remainder
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

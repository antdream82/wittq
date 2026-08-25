package com.fortq.wittq

import kotlin.math.sqrt

internal object SoftRunner17dIndicators {
    fun simpleMovingAverage(values: List<Double>, length: Int): List<Double?> {
        require(length > 0)
        val out = MutableList<Double?>(values.size) { null }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= length) sum -= values[i - length]
            if (i + 1 >= length) out[i] = sum / length
        }
        return out
    }

    /** Wilder RSI, matching Pine ta.rsi semantics closely. */
    fun rsiWilder(values: List<Double>, length: Int): List<Double?> {
        require(length > 0)
        val out = MutableList<Double?>(values.size) { null }
        if (values.size <= length) return out

        var gainSum = 0.0
        var lossSum = 0.0
        for (i in 1..length) {
            val delta = values[i] - values[i - 1]
            if (delta >= 0.0) gainSum += delta else lossSum -= delta
        }
        var avgGain = gainSum / length
        var avgLoss = lossSum / length
        out[length] = rsiFromAverages(avgGain, avgLoss)

        for (i in length + 1 until values.size) {
            val delta = values[i] - values[i - 1]
            val gain = if (delta > 0.0) delta else 0.0
            val loss = if (delta < 0.0) -delta else 0.0
            avgGain = (avgGain * (length - 1) + gain) / length
            avgLoss = (avgLoss * (length - 1) + loss) / length
            out[i] = rsiFromAverages(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiFromAverages(avgGain: Double, avgLoss: Double): Double = when {
        avgLoss == 0.0 && avgGain == 0.0 -> 50.0
        avgLoss == 0.0 -> 100.0
        else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    /** Population standard deviation of the latest [length] daily returns. */
    fun rollingReturnVolatility(values: List<Double>, length: Int): List<Double?> {
        require(length > 0)
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < length + 1) return out
        val returns = MutableList(values.size) { Double.NaN }
        for (i in 1 until values.size) returns[i] = values[i] / values[i - 1] - 1.0

        for (i in length until values.size) {
            var mean = 0.0
            for (j in i - length + 1..i) mean += returns[j]
            mean /= length
            var variance = 0.0
            for (j in i - length + 1..i) {
                val d = returns[j] - mean
                variance += d * d
            }
            out[i] = sqrt(variance / length)
        }
        return out
    }

    fun rollingLinearSlope(values: List<Double?>, length: Int): List<Double?> {
        require(length > 1)
        val out = MutableList<Double?>(values.size) { null }
        val sumX = (0 until length).sumOf { it.toDouble() }
        val sumX2 = (0 until length).sumOf { it.toDouble() * it.toDouble() }
        val denominator = length * sumX2 - sumX * sumX
        if (denominator == 0.0) return out

        for (i in length - 1 until values.size) {
            var sumY = 0.0
            var sumXY = 0.0
            var valid = true
            for (offset in 0 until length) {
                val y = values[i - length + 1 + offset]
                if (y == null || !y.isFinite()) {
                    valid = false
                    break
                }
                val x = offset.toDouble()
                sumY += y
                sumXY += x * y
            }
            if (valid) out[i] = (length * sumXY - sumX * sumY) / denominator
        }
        return out
    }
}

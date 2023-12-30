package com.lanlords.vertimeter

class Utils {
    companion object {
        fun findPeaks(data: List<Float>): List<Int> {
            val peaks = mutableListOf<Int>()
            for (i in 1 until data.size - 1) {
                if (data[i] > data[i-1] && data[i] > data[i+1]) {
                    peaks.add(i)
                }
            }
            return peaks
        }
    }
}
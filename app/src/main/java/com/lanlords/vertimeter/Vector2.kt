package com.lanlords.vertimeter

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Vector2(val x: Float, val y: Float) {

    operator fun plus(other: Vector2) = Vector2(x + other.x, y + other.y)

    operator fun minus(other: Vector2) = Vector2(x - other.x, y - other.y)

    operator fun times(scale: Float) = Vector2(x * scale, y * scale)

    operator fun div(scale: Number) = Vector2(x / scale.toFloat(), y / scale.toFloat())

    operator fun div(other: Vector2) = Vector2(x / other.x, y / other.y)

    override operator fun equals(other: Any?) = other is Vector2 && x == other.x && y == other.y

    fun dot(other: Vector2) = x * other.x + y * other.y

    fun length() = sqrt(x * x + y * y)

    fun normalized(): Vector2 {
        val len = length()
        return Vector2(x / len, y / len)
    }

    fun normalizedBy(other: Vector2) = Vector2(x / other.x, y / other.y)

    fun distanceTo(other: Vector2): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }

    fun distanceXTo(other: Vector2): Float {
        return abs(x - other.x)
    }

    fun distanceYTo(other: Vector2): Float {
        return abs(y - other.y)
    }

    fun toIntVector() = Vector2(x.roundToInt().toFloat(), y.roundToInt().toFloat())

    override fun toString() = "Vector2(x=$x, y=$y)"
}

package com.lanlords.vertimeter

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class JumpResult(
    val jumpHeight: Float,
    val jumpDuration: Float,
    val jumpData: MutableMap<Float, Float> = mutableMapOf(),
    val height: Int,
) : Parcelable

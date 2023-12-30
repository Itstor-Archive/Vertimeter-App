package com.lanlords.vertimeter

import android.os.CountDownTimer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

enum class AnalysisState {
    NOT_STARTED,
    WAITING_FOR_BODY,
    BODY_IN_FRAME,
    COUNTDOWN,
    JUMP,
    DONE
}

class MainViewModel : ViewModel() {
    private val _height = MutableLiveData<Int>() // Don't forget to remove this default value before release
    val height: LiveData<Int> = _height

    var instructionsCurrentStep = 0
    var skipInstructions = false

    private val _countdownTime = MutableLiveData<Int>()
    val countdownTime: LiveData<Int> = _countdownTime

    private var timer: CountDownTimer? = null
    private var totalTime = 0

    private val _analysisState = MutableLiveData(AnalysisState.NOT_STARTED)
    val analysisState: LiveData<AnalysisState> = _analysisState

    var _jumpResult: JumpResult? = null

    fun setHeight(height: Int) {
        _height.value = height
    }

    fun setJumpResult(height: Float, duration: Float, jumpData: MutableMap<Float, Float>) {
        _jumpResult = JumpResult(height, duration, jumpData, _height.value!!)
    }

    fun setAnalysisState(state: AnalysisState) {
        _analysisState.value = state
    }

    private fun startCountdown(time: Int, onFinishCallback: (() -> Unit)? = null) {
        totalTime = time
        timer?.cancel()

        timer = object : CountDownTimer(time.toLong() * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _countdownTime.value = (millisUntilFinished / 1000).toInt()
            }

            override fun onFinish() {
                _countdownTime.value = 0

                if (onFinishCallback != null) onFinishCallback()
            }
        }.start()
    }

    fun startJumpAnalysis() {
        _analysisState.value = AnalysisState.COUNTDOWN

        startCountdown(6) {
            _analysisState.value = AnalysisState.JUMP
        }
    }

    fun reset() {
        _analysisState.value = AnalysisState.NOT_STARTED
        _countdownTime.value = 0
        _jumpResult = null
        totalTime = 0
        timer?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        timer?.cancel()
    }
}
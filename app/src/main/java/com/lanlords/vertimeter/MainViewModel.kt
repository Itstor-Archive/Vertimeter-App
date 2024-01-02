package com.lanlords.vertimeter

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CountDownTimer
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class AnalysisState {
    NOT_STARTED,
    WAITING_FOR_BODY,
    BODY_IN_FRAME,
    COUNTDOWN,
    CAMERA_ANALYZING,
    VIDEO_ANALYZING,
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

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    var jumpResult: JumpResult? = null

    fun setHeight(height: Int) {
        _height.value = height
    }

    fun setJumpResult(height: Float, duration: Float, jumpData: MutableMap<Float, Float>) {
        jumpResult = JumpResult(height, duration, jumpData, _height.value!!)
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

    fun startJumpCameraAnalysis() {
        _analysisState.value = AnalysisState.COUNTDOWN

        startCountdown(6) {
            _analysisState.value = AnalysisState.CAMERA_ANALYZING
        }
    }

    fun startJumpVideoAnalysis(videoUri: Uri, context: Context) {
        viewModelScope.launch(context = Dispatchers.IO) {
            _analysisState.postValue(AnalysisState.VIDEO_ANALYZING)

            val mediaMetadataRetriever = MediaMetadataRetriever()
            mediaMetadataRetriever.setDataSource(context, videoUri)

            val duration = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()

            if (duration == null) {
                _analysisState.postValue(AnalysisState.NOT_STARTED)
                return@launch
            }

            val frameRate = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f

            val jumpVideoAnalyzer = JumpVideoAnalyzer(frameRate)

            var frameTime = 0L
            val frameInterval = (1000000L / frameRate).toLong()

            var frameCounter = 0

            while (frameTime < duration * 1000) {
                frameCounter++

                val bitmap = mediaMetadataRetriever.getFrameAtTime(frameTime, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue

                val inputImage = InputImage.fromBitmap(bitmap, 0)

                jumpVideoAnalyzer.poseDetector.process(inputImage)
                    .addOnSuccessListener { poses ->
                        if (frameCounter == 1 || jumpVideoAnalyzer.leftFootReference == null || jumpVideoAnalyzer.rightFootReference == null || jumpVideoAnalyzer.shoulderReference == null || jumpVideoAnalyzer.pixelToCentiScale == null) {
                            jumpVideoAnalyzer.calculatePixelToCentiScale(poses, _height.value!!)
                            jumpVideoAnalyzer.setLandmarkReference(poses)
                        } else {
                            jumpVideoAnalyzer.analyzeJump(
                                poses,
                                frameCounter
                            ) { maxJumpHeight, jumpDuration, jumpData ->
                                setJumpResult(maxJumpHeight, jumpDuration, jumpData)
                                _analysisState.postValue(AnalysisState.DONE)
                                Log.d("JumpVideoAnalyzer", "Jump height: $maxJumpHeight")
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        // Handle error
                    }

                frameTime += frameInterval
            }

            if (jumpResult == null) {
                _toastMessage.postValue("Can't detect jump. Please try again.")
                _analysisState.postValue(AnalysisState.NOT_STARTED)
            }

            mediaMetadataRetriever.release()
        }
    }

    fun reset() {
        _analysisState.value = AnalysisState.NOT_STARTED
        _countdownTime.value = 0
        jumpResult = null
        totalTime = 0
        timer?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        timer?.cancel()
    }
}
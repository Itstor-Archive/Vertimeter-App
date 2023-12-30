package com.lanlords.vertimeter

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt


class JumpAnalyzer(private val activity: MainActivity) : ImageAnalysis.Analyzer {
    private val poseLandmarks = listOf(
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW,
        PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST,
        PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_PINKY,
        PoseLandmark.RIGHT_PINKY,
        PoseLandmark.LEFT_INDEX,
        PoseLandmark.RIGHT_INDEX,
        PoseLandmark.LEFT_THUMB,
        PoseLandmark.RIGHT_THUMB,
        PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE,
        PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE,
        PoseLandmark.RIGHT_ANKLE,
        PoseLandmark.LEFT_HEEL,
        PoseLandmark.RIGHT_HEEL,
        PoseLandmark.LEFT_FOOT_INDEX,
        PoseLandmark.RIGHT_FOOT_INDEX,
    )

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private var bodyInFrameStartTime: Long? = null
    private val requiredDurationInFrame: Long = 2000 // 2 seconds

    private var pixelToCentiScale: Float? = null
    private var shoulderReference: Vector2? = null
    private var leftFootReference: Vector2? = null
    private var rightFootReference: Vector2? = null

    private var jumpStartTime: Long? = null
    private var startTime: Long? = null
    private var maxJumpHeight = 0f

    private var jumpData = mutableMapOf<Float, Float>()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            poseDetector.process(image)
                .addOnSuccessListener { poses ->
                    when (activity.viewModel.analysisState.value) {
                        AnalysisState.WAITING_FOR_BODY -> {
                            if (isBodyInFrame(poses)) {
                                handleBodyInFrame()
                            } else {
                                activity.setDebugText(message = "Body not in frame")
                                activity.viewModel.setAnalysisState(AnalysisState.WAITING_FOR_BODY)
                                bodyInFrameStartTime = null
                            }
                        }

                        AnalysisState.BODY_IN_FRAME -> handleBodyInFrame()
                        AnalysisState.COUNTDOWN -> handleCountdownState(poses)
                        AnalysisState.JUMP -> handleJumpState(poses)
                        else -> {}
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("PoseAnalysis", "Pose detection failed: $e")
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun handleJumpState(poses: Pose) {
        val leftShoulderLandmark = poses.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulderLandmark = poses.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftFootLandmark = poses.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX)
        val rightFootLandmark = poses.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX)

        if (startTime == null) startTime = System.currentTimeMillis()

        leftShoulderLandmark?.let { leftShoulder ->
            rightShoulderLandmark?.let { rightShoulder ->
                leftFootLandmark?.let { leftFoot ->
                    rightFootLandmark?.let { rightFoot ->
                        val currentLeftFootVec = Vector2(leftFoot.position.x, leftFoot.position.y)
                        val currentRightFootVec = Vector2(rightFoot.position.x, rightFoot.position.y)
                        val leftShoulderVec = Vector2(leftShoulder.position.x, leftShoulder.position.y)
                        val rightShoulderVec = Vector2(rightShoulder.position.x, rightShoulder.position.y)
                        val currentShoulderVec = (leftShoulderVec + rightShoulderVec) / 2

                        val jumpHeight = currentShoulderVec.distanceYTo(shoulderReference!!) * pixelToCentiScale!!

                        val currentTimeSec = (System.currentTimeMillis() - startTime!!).toFloat() / 1000f

                        jumpData[currentTimeSec] = jumpHeight

                        // When off the ground
                        if (currentLeftFootVec.y < leftFootReference!!.y && currentRightFootVec.y < rightFootReference!!.y) {
                            if (jumpStartTime == null) jumpStartTime = System.currentTimeMillis()

                            if (jumpHeight > maxJumpHeight) {
                                maxJumpHeight = jumpHeight
                            }

                            Log.d("PoseAnalysis", "Jump height: $jumpHeight")
                        }

                        // When back on the ground
                        if (currentLeftFootVec.y > leftFootReference!!.y && currentRightFootVec.y > rightFootReference!!.y && jumpStartTime != null) {
                            val jumpDuration = System.currentTimeMillis() - jumpStartTime!!
                            val jumpDurationSec = jumpDuration.toFloat() / 1000f
                            jumpStartTime = null

                            activity.viewModel.setJumpResult(maxJumpHeight, jumpDurationSec, jumpData)
                            activity.viewModel.setAnalysisState(AnalysisState.DONE)
                        }
                    }
                }
            }
        }
    }

    private fun handleBodyInFrame() {
        bodyInFrameStartTime = bodyInFrameStartTime ?: System.currentTimeMillis()
        val timeInFrame = System.currentTimeMillis() - bodyInFrameStartTime!!

        if (timeInFrame >= requiredDurationInFrame) {
            Log.d("PoseAnalysis", "Body in frame for 2 seconds")
            activity.setDebugText(message = "Body in frame for 2 seconds")
            activity.viewModel.startJumpAnalysis()
            bodyInFrameStartTime = null
        } else {
            Log.d("PoseAnalysis", "Body in frame for ${timeInFrame} milliseconds")
            activity.setDebugText(message = "Body in frame")
            activity.viewModel.setAnalysisState(AnalysisState.BODY_IN_FRAME)
        }
    }

    private fun handleCountdownState(poses: Pose) {
        if (pixelToCentiScale == null) {
            calculatePixelToCentiScale(poses)
            setLandmarkReference(poses)
        }
    }

    private fun calculatePixelToCentiScale(poses: Pose) {
        val noseLandmark = poses.getPoseLandmark(PoseLandmark.NOSE)
        val rightAnkleLandmark = poses.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
        val leftAnkleLandmark = poses.getPoseLandmark(PoseLandmark.LEFT_ANKLE)

        noseLandmark?.let { nose ->
            rightAnkleLandmark?.let { rightAnkle ->
                leftAnkleLandmark?.let { leftAnkle ->
                    val rightAnkleVec = Vector2(rightAnkle.position.x, rightAnkle.position.y)
                    val leftAnkleVec = Vector2(leftAnkle.position.x, leftAnkle.position.y)

                    val heelDistance = rightAnkleVec.distanceTo(leftAnkleVec) / 2

                    val hypotenuse = abs(rightAnkle.position.y - nose.position.y)

                    val heightPixel = sqrt(hypotenuse.pow(2) - heelDistance.pow(2))

                    pixelToCentiScale =
                        (activity.viewModel.height.value!! / heightPixel / 2.53).toFloat()
                    activity.setDebugText(pxToCmScale = pixelToCentiScale!!)
                }
            }
        }
    }

    private fun setLandmarkReference(pose: Pose) {
        val leftShoulderLandmark = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulderLandmark = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftFootLandmark = pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX)
        val rightFootLandmark = pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX)

        leftShoulderLandmark?.let { leftShoulder ->
            rightShoulderLandmark?.let { rightShoulder ->
                leftFootLandmark?.let { leftFoot ->
                    rightFootLandmark?.let { rightFoot ->
                        leftFootReference = Vector2(leftFoot.position.x, leftFoot.position.y)
                        rightFootReference = Vector2(rightFoot.position.x, rightFoot.position.y)

                        val leftShoulderVec = Vector2(leftShoulder.position.x, leftShoulder.position.y)
                        val rightShoulderVec = Vector2(rightShoulder.position.x, rightShoulder.position.y)
                        shoulderReference = (leftShoulderVec + rightShoulderVec) / 2
                    }
                }
            }
        }
    }

    private fun isBodyInFrame(poses: Pose): Boolean {
        var totalLikelihood = 0f
        var landmarksInFrame = 0

        for (landmarkType in poseLandmarks) {
            poses.getPoseLandmark(landmarkType)?.let { landmark ->
                totalLikelihood += landmark.inFrameLikelihood
                if (landmark.inFrameLikelihood > 0.8) {
                    landmarksInFrame++
                }
            }
        }

        val averageLikelihood = totalLikelihood / poseLandmarks.size
        val percentageInFrame = landmarksInFrame.toFloat() / poseLandmarks.size

        val likelihoodThreshold = 0.9f
        val percentageThreshold = 0.95f // IDK why one of the landmarks is always out of frame

        return averageLikelihood > likelihoodThreshold && percentageInFrame > percentageThreshold
    }
}
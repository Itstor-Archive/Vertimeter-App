package com.lanlords.vertimeter

import android.util.Log
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class JumpVideoAnalyzer(private val frameRate: Float) {
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

    val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    var pixelToCentiScale: Float? = null
    var shoulderReference: Vector2? = null
    var leftFootReference: Vector2? = null
    var rightFootReference: Vector2? = null

    private var jumpStartFrame: Int? = null
    private var maxJumpHeight = 0f

    private var jumpData = mutableMapOf<Float, Float>()

    fun analyzeJump(poses: Pose, frame: Int, onAnalyzeFinish: (maxJumpHeight: Float, jumpDuration: Float, jumpData: MutableMap<Float, Float>) -> Unit) {
        val leftShoulderLandmark = poses.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulderLandmark = poses.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftFootLandmark = poses.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX)
        val rightFootLandmark = poses.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX)

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

                        val currentTimeSec = (frame.toFloat() / frameRate)

                        jumpData[currentTimeSec] = jumpHeight

                        // When off the ground
                        if (currentLeftFootVec.y < leftFootReference!!.y && currentRightFootVec.y < rightFootReference!!.y) {
                            if (jumpStartFrame == null) jumpStartFrame = frame

                            if (jumpHeight > maxJumpHeight) {
                                maxJumpHeight = jumpHeight
                            }

                            Log.d("PoseAnalysis", "Jump height: $jumpHeight")
                        }

                        // When back on the ground
                        if (currentLeftFootVec.y > leftFootReference!!.y && currentRightFootVec.y > rightFootReference!!.y && jumpStartFrame != null) {
                            val jumpDuration = frame - jumpStartFrame!!
                            val jumpDurationSec = jumpDuration.toFloat() / frameRate
                            jumpStartFrame = null

                            Log.d("PoseAnalysis", "Jump duration: $jumpDurationSec")

                            onAnalyzeFinish(maxJumpHeight, jumpDurationSec, jumpData)
                        }
                    }
                }
            }
        }
    }

    fun calculatePixelToCentiScale(poses: Pose, height: Int) {
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
                        (height / heightPixel / 2.53).toFloat()

                    Log.d("PoseAnalysis", "Pixel to centi scale: $pixelToCentiScale")
                }
            }
        }
    }

    fun setLandmarkReference(pose: Pose) {
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
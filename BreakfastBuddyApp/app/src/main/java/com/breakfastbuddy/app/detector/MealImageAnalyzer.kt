package com.breakfastbuddy.app.detector

import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 結合 CameraX + ML Kit Face Contours + ML Kit Pose Detection + 嘴部區域亮度變化分析，
 * 即時擷取咀嚼與進食特徵。
 */
class MealImageAnalyzer(
    private val onObservation: (FrameObservation) -> Unit
) : ImageAnalysis.Analyzer {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
    )

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private var lastAnalyzedTimeMs: Long = 0L
    private var previousMouthMeanLuma: Float? = null
    private var lastMouthRectNormalized: FloatArray? = null // [left, top, right, bottom] in 0..1

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val nowMs = System.currentTimeMillis()
        // 限制分析頻率約 10~12 FPS，兼顧省電、平板發熱與流暢度
        if (nowMs - lastAnalyzedTimeMs < 85L) {
            imageProxy.close()
            return
        }
        lastAnalyzedTimeMs = nowMs

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        // 先在 imageProxy 關閉前計算嘴部 ROI 的平均亮度變化 (偵測湯匙/食物靠近造成的遮擋)
        val mouthLumaDelta = computeMouthRoiLumaDelta(imageProxy)

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        val imgWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.height else imageProxy.width
        val imgHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.width else imageProxy.height

        val faceTask = faceDetector.process(inputImage)
        val poseTask = poseDetector.process(inputImage)

        Tasks.whenAllComplete(faceTask, poseTask)
            .addOnCompleteListener {
                try {
                    val faces = if (faceTask.isSuccessful) faceTask.result else emptyList()
                    val pose = if (poseTask.isSuccessful) poseTask.result else null

                    val observation = buildObservation(
                        nowMs = nowMs,
                        face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() },
                        pose = pose,
                        imgWidth = imgWidth.coerceAtLeast(1),
                        imgHeight = imgHeight.coerceAtLeast(1),
                        mouthLumaDelta = mouthLumaDelta
                    )
                    onObservation(observation)
                } finally {
                    imageProxy.close()
                }
            }
    }

    private fun buildObservation(
        nowMs: Long,
        face: Face?,
        pose: Pose?,
        imgWidth: Int,
        imgHeight: Int,
        mouthLumaDelta: Float
    ): FrameObservation {
        if (face == null) {
            return FrameObservation(
                timestampMs = nowMs,
                faceDetected = false,
                mouthOpenRatio = 0f,
                jawRelativeY = 0f,
                handNearMouth = false,
                mouthVisualChange = 0f
            )
        }

        val box: Rect = face.boundingBox
        val faceHeight = box.height().toFloat().coerceAtLeast(1f)
        val faceWidth = box.width().toFloat().coerceAtLeast(1f)

        val upperLipBottom = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points.orEmpty()
        val lowerLipTop = face.getContour(FaceContour.LOWER_LIP_TOP)?.points.orEmpty()
        val faceOval = face.getContour(FaceContour.FACE)?.points.orEmpty()
        val noseBridge = face.getContour(FaceContour.NOSE_BRIDGE)?.points.orEmpty()

        // 計算嘴唇開合比率
        val mouthOpenRatio = if (upperLipBottom.isNotEmpty() && lowerLipTop.isNotEmpty()) {
            val upperY = upperLipBottom.map { it.y }.average().toFloat()
            val lowerY = lowerLipTop.map { it.y }.average().toFloat()
            ((lowerY - upperY).coerceAtLeast(0f)) / faceHeight
        } else {
            0f
        }

        // 計算下顎相對鼻樑的起伏比率 (即使閉著嘴巴咀嚼也能偵測)
        val jawRelativeY = if (faceOval.isNotEmpty() && noseBridge.isNotEmpty()) {
            val chinY = faceOval.maxOf { it.y }
            val noseTopY = noseBridge.minOf { it.y }
            ((chinY - noseTopY).coerceAtLeast(0f)) / faceHeight
        } else {
            0f
        }

        // 嘴部中心座標
        val mouthCenterX = if (upperLipBottom.isNotEmpty()) {
            upperLipBottom.map { it.x }.average().toFloat()
        } else {
            box.exactCenterX()
        }
        val mouthCenterY = if (upperLipBottom.isNotEmpty() && lowerLipTop.isNotEmpty()) {
            ((upperLipBottom.map { it.y }.average() + lowerLipTop.map { it.y }.average()) / 2.0).toFloat()
        } else {
            box.top + faceHeight * 0.75f
        }

        // 更新下一幀 ROI 查詢區域 (正規化 0..1)
        val roiHalfW = faceWidth * 0.28f
        val roiHalfH = faceHeight * 0.20f
        lastMouthRectNormalized = floatArrayOf(
            ((mouthCenterX - roiHalfW) / imgWidth).coerceIn(0f, 1f),
            ((mouthCenterY - roiHalfH) / imgHeight).coerceIn(0f, 1f),
            ((mouthCenterX + roiHalfW) / imgWidth).coerceIn(0f, 1f),
            ((mouthCenterY + roiHalfH) / imgHeight).coerceIn(0f, 1f)
        )

        // 檢查左手或右手 (手腕/食指) 是否接近嘴巴
        val handNearMouth = isHandNearMouth(
            pose = pose,
            mouthX = mouthCenterX,
            mouthY = mouthCenterY,
            thresholdPx = faceWidth * 0.85f
        )

        return FrameObservation(
            timestampMs = nowMs,
            faceDetected = true,
            mouthOpenRatio = mouthOpenRatio,
            jawRelativeY = jawRelativeY,
            handNearMouth = handNearMouth,
            mouthVisualChange = mouthLumaDelta
        )
    }

    private fun isHandNearMouth(
        pose: Pose?,
        mouthX: Float,
        mouthY: Float,
        thresholdPx: Float
    ): Boolean {
        if (pose == null) return false
        val handLandmarks = listOfNotNull(
            pose.getPoseLandmark(PoseLandmark.LEFT_INDEX),
            pose.getPoseLandmark(PoseLandmark.RIGHT_INDEX),
            pose.getPoseLandmark(PoseLandmark.LEFT_WRIST),
            pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        )
        return handLandmarks.any { lm ->
            lm.inFrameLikelihood >= 0.45f &&
                hypot(lm.position.x - mouthX, lm.position.y - mouthY) <= thresholdPx
        }
    }

    /**
     * 從 YUV 影格的 Y 平面取樣嘴部周圍平均亮度，計算前後影格變化量。
     * 當湯匙、麵包或食物進入嘴部前方時，會產生明顯的亮度變化。
     */
    private fun computeMouthRoiLumaDelta(imageProxy: ImageProxy): Float {
        val rectNorm = lastMouthRectNormalized ?: return 0f
        val yPlane = imageProxy.planes.firstOrNull() ?: return 0f
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) return 0f

        val startX = (rectNorm[0] * width).toInt().coerceIn(0, width - 1)
        val startY = (rectNorm[1] * height).toInt().coerceIn(0, height - 1)
        val endX = (rectNorm[2] * width).toInt().coerceIn(startX + 1, width)
        val endY = (rectNorm[3] * height).toInt().coerceIn(startY + 1, height)

        var sum = 0L
        var count = 0
        val step = 4
        val limit = buffer.limit()

        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val index = y * rowStride + x * pixelStride
                if (index in 0 until limit) {
                    sum += (buffer.get(index).toInt() and 0xFF)
                    count++
                }
                x += step
            }
            y += step
        }

        if (count == 0) return 0f
        val currentMean = sum.toFloat() / count
        val prev = previousMouthMeanLuma
        previousMouthMeanLuma = currentMean
        return if (prev == null) 0f else abs(currentMean - prev)
    }

    fun close() {
        faceDetector.close()
        poseDetector.close()
    }
}

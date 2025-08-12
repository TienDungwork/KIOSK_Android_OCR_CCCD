package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import com.surendramaran.yolov8tflite.MetaData.extractNamesFromLabelFile
import com.surendramaran.yolov8tflite.MetaData.extractNamesFromMetadata
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String?,
    private val detectorListener: DetectorListener,
    private val message: (String) -> Unit
) {

    private var interpreter: Interpreter
    private val labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    // Biến trạng thái để theo dõi thẻ
    private var lastCardState: BooleanArray? = null // Trạng thái thẻ trước đó (hasChip, hasIdNumber)
    private var thongBaoRut = false // Cờ thông báo rút thẻ
    private var thongBaoDat = false // Cờ thông báo đặt thẻ
    private var cardDetectedTime: Long? = null // Thời gian phát hiện thẻ
    private var recognizedFrontId: String? = null // ID mặt trước
    private var recognizedBackId: String? = null // ID mặt sau
    private var scanButtonPressed = false // Biến theo dõi trạng thái nút quét

    init {
        val compatList = CompatibilityList()

        val options = Interpreter.Options().apply {
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                this.addDelegate(GpuDelegate(delegateOptions))
            } else {
                this.setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        labels.addAll(extractNamesFromMetadata(model))
        if (labels.isEmpty()) {
            if (labelPath == null) {
                message("Model not contains metadata, provide LABELS_PATH in Constants.kt")
                labels.addAll(MetaData.TEMP_CLASSES)
            } else {
                labels.addAll(extractNamesFromLabelFile(context, labelPath))
            }
        }

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]

            // If in case input shape is in format of [1, 3, ..., ...]
            if (inputShape[1] == 3) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }

        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }
    }

    fun restart(isGpu: Boolean) {
        interpreter.close()

        val options = if (isGpu) {
            val compatList = CompatibilityList()
            Interpreter.Options().apply {
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                } else {
                    this.setNumThreads(4)
                }
            }
        } else {
            Interpreter.Options().apply {
                this.setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
    }

    fun close() {
        interpreter.close()
    }

    fun detect(frame: Bitmap) {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
            return
        }

        val inferenceTime = SystemClock.uptimeMillis()

        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

        val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
        tensorImage.load(resizedBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        println("numChannel: $numChannel")
        println("numElements: $numElements")

        val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter.run(imageBuffer, output.buffer)

        val bestBoxes = bestBox(output.floatArray)
        val finalInferenceTime = SystemClock.uptimeMillis() - inferenceTime

        if (bestBoxes == null) {
            detectorListener.onEmptyDetect()
            return
        }

        // Thêm logic xử lý thẻ CCCD
        processCardDetection(bestBoxes, frame, finalInferenceTime)
    }

    private fun processCardDetection(boxes: List<BoundingBox>, frame: Bitmap, inferenceTime: Long) {
        // Xác định các đối tượng
        var hasChip = false
        var hasIdNumber = false
        var hasQr = false
        var hasImage = false

        for (box in boxes) {
            val clsName = box.clsName
            if (clsName.contains("Chip")) hasChip = true
            if (clsName.contains("ID_number")) hasIdNumber = true
            if (clsName.contains("QR")) hasQr = true
            if (clsName.contains("Image")) hasImage = true
        }

        // Trạng thái thẻ hiện tại
        val currentCardState = booleanArrayOf(hasChip, hasIdNumber)

        // Phát hiện thẻ bị rút ra
        if (!(hasChip || hasIdNumber) && lastCardState != null && (lastCardState!![0] || lastCardState!![1]) && !thongBaoRut) {
            message("Thẻ đã được rút ra, reset trạng thái.")
            cardDetectedTime = null
            recognizedFrontId = null
            recognizedBackId = null
            thongBaoRut = true
            thongBaoDat = false // Reset để in lại khi đặt thẻ mới
            scanButtonPressed = false // Reset trạng thái nút quét khi thẻ được rút ra
            Log.d("Detector", "RESET: Đã reset recognizedFrontId và recognizedBackId")
        }

        // Cập nhật trạng thái thẻ nếu không có chip hoặc ID_number
        if (!(hasChip || hasIdNumber)) {
            lastCardState = currentCardState
            thongBaoRut = false // Reset để in "Thẻ đã được rút ra" ở lần rút tiếp theo
            detectorListener.onEmptyDetect()
            return
        }

        // Phát hiện thẻ mới được đặt vào
        if ((hasChip || hasIdNumber) && (lastCardState == null || !(lastCardState!![0] || lastCardState!![1])) && !thongBaoDat) {
            message("Phát hiện thẻ CCCD! Nhấn nút quét để bắt đầu quét thẻ.")
            thongBaoDat = true
        }

        // Cập nhật trạng thái thẻ
        lastCardState = currentCardState

        // Chỉ xử lý OCR khi nút quét được nhấn
        if (scanButtonPressed && thongBaoDat) {
            Log.d("Detector", "OCR_CHECK: scanButtonPressed=$scanButtonPressed, thongBaoDat=$thongBaoDat, hasChip=$hasChip, hasIdNumber=$hasIdNumber")
            Log.d("Detector", "OCR_CHECK: recognizedFrontId=$recognizedFrontId, recognizedBackId=$recognizedBackId")
            
            // Xử lý và in thông tin về loại thẻ, mặt thẻ, và chiều thẻ
            val cardSide = if (hasChip) "Mặt sau" else "Mặt trước"
            var cardType = "Không xác định"
            var cardOrientation = "Không xác định"
            var isFlipped = false

            if (!hasChip && hasIdNumber) {
                cardType = if (hasQr && hasImage) "Thẻ người lớn cũ" else "Thẻ người lớn mới"
                val flagBox = boxes.find { it.clsName.contains("Flag") }
                val idNumberBox = boxes.find { it.clsName.contains("ID_number") }

                if (flagBox != null && idNumberBox != null) {
                    val flagY = flagBox.y1
                    val idNumberY = idNumberBox.y1
                    isFlipped = flagY > idNumberY
                    cardOrientation = if (isFlipped) "Mặt trước ngược" else "Mặt trước xuôi"
                }
            } else if (hasChip) {
                cardType = if (hasQr) "Thẻ mới" else "Thẻ cũ"
                val chipBox = boxes.find { it.clsName.contains("Chip") }
                val idBox = boxes.find { it.clsName.contains("ID") }

                if (chipBox != null && idBox != null) {
                    val chipCenterY = (chipBox.y1 + chipBox.y2) / 2
                    val idCenterY = (idBox.y1 + idBox.y2) / 2
                    isFlipped = chipCenterY > idCenterY
                    cardOrientation = if (isFlipped) "Mặt sau ngược" else "Mặt sau xuôi"
                } else if (chipBox != null) {
                    val chipCenterX = (chipBox.x1 + chipBox.x2) / 2
                    isFlipped = chipCenterX > (frame.width / 2)
                    cardOrientation = if (isFlipped) "Mặt sau ngược" else "Mặt sau xuôi"
                }
            }

            if (!hasChip && hasIdNumber && !hasImage) {
                cardType = "Thẻ trẻ em"
            }

            // Xoay frame nếu thẻ bị ngược
            val processedFrame = if (isFlipped) {
                rotateBitmap(frame, 180f)
            } else {
                frame
            }

            // Gửi thông tin về loại thẻ, mặt thẻ, và chiều thẻ qua message
            message("Card Side: $cardSide, Type: $cardType, Orientation: $cardOrientation")

            // OCR mặt trước (ID_number) - Chạy khi chưa có recognizedFrontId và có ID_number box
            if (recognizedFrontId == null && boxes.any { it.clsName.contains("ID_number") }) {
                Log.d("Detector", "OCR_FRONT: Bắt đầu OCR mặt trước - recognizedFrontId=null, hasID_number=${boxes.any { it.clsName.contains("ID_number") }}")
                val idNumberBox = boxes.find { it.clsName.contains("ID_number") }
                if (idNumberBox == null) {
                    message("Không tìm thấy hộp giới hạn cho ID_number. Bỏ qua OCR.")
                    Log.d("Detector", "No bounding box found for ID_number. Skipping OCR.")
                } else {
                    // Dùng tọa độ từ hộp giới hạn
                    val x1 = idNumberBox.x1 * frame.width
                    val y1 = idNumberBox.y1 * frame.height
                    val x2 = idNumberBox.x2 * frame.width
                    val y2 = idNumberBox.y2 * frame.height
                    val (x, y, w, h) = if (isFlipped) {
                        val x = frame.width - x2
                        val y = frame.height - y2
                        val w = x2 - x1
                        val h = y2 - y1
                        Log.d("Detector", "Detected ROI for ID_number: x=$x, y=$y, w=$w, h=$h")
                        intArrayOf(x.toInt(), y.toInt(), w.toInt(), h.toInt())
                    } else {
                        Log.d("Detector", "Detected ROI for ID_number: x=$x1, y=$y1, w=${x2-x1}, h=${y2-y1}")
                        intArrayOf(x1.toInt(), y1.toInt(), (x2 - x1).toInt(), (y2 - y1).toInt())
                    }

                    // Đảm bảo kích thước vùng ROI ít nhất là 32x32
                    val adjustedW = maxOf(w, 32)
                    val adjustedH = maxOf(h, 32)
                    // Điều chỉnh x, y để vùng ROI không vượt ra ngoài frame
                    val adjustedX = maxOf(0, minOf(x, frame.width - adjustedW))
                    val adjustedY = maxOf(0, minOf(y, frame.height - adjustedH))
                    Log.d("Detector", "Adjusted ROI for ID_number: x=$adjustedX, y=$adjustedY, w=$adjustedW, h=$adjustedH")

                    // Cắt vùng ROI và thực hiện OCR
                    val ocrRoi = Bitmap.createBitmap(processedFrame, adjustedX, adjustedY, adjustedW, adjustedH)
                    Log.d("Detector", "=== OCR PROCESS START (ID_number) ===")
                    Log.d("Detector", "ROI Size: ${adjustedW}x${adjustedH}")
                    Log.d("Detector", "ROI Position: ($adjustedX, $adjustedY)")
                    Log.d("Detector", "Frame Size: ${frame.width}x${frame.height}")
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val ocrResult = OcrHelper.ocr(ocrRoi)
                            Log.d("Detector", "OCR Result for ID_number: '$ocrResult'")
                            val idNumber = OcrHelper.extractFrontIdNumber(ocrResult)
                            if (idNumber != null) {
                                recognizedFrontId = idNumber
                                cardDetectedTime?.let { detectedTime ->
                                    val totalTime = (System.currentTimeMillis() - detectedTime)
                                    message("Thời gian từ khi đặt thẻ đến khi hiển thị ID_number: $totalTime ms")
                                }
                                message("ID_number: $idNumber")
                                Log.d("Detector", "Successfully extracted ID_number: $idNumber")
                                Log.d("Detector", "Sending message: ID_number: $idNumber")
                            } else {
                                Log.d("Detector", "Failed to extract ID_number from OCR result")
                            }
                        } catch (e: Exception) {
                            message("OCR Error (ID_number): ${e.message}")
                            Log.e("Detector", "OCR Error (ID_number): ${e.message}")
                        }
                    }
                }
            } else {
                Log.d("Detector", "OCR_FRONT: Bỏ qua OCR mặt trước - recognizedFrontId=${recognizedFrontId}, hasID_number=${boxes.any { it.clsName.contains("ID_number") }}")
            }

            // OCR mặt sau (ID) - Chạy khi chưa có recognizedBackId và có ID box
            if (recognizedBackId == null && boxes.any { it.clsName.contains("ID") }) {
                Log.d("Detector", "OCR_BACK: Bắt đầu OCR mặt sau - recognizedBackId=null, hasID=${boxes.any { it.clsName.contains("ID") }}")
                val idBox = boxes.find { it.clsName.contains("ID") }
                if (idBox == null) {
                    message("Không tìm thấy hộp giới hạn cho ID (mặt sau). Bỏ qua OCR.")
                    Log.d("Detector", "No bounding box found for ID (Back). Skipping OCR.")
                } else {
                    // Dùng tọa độ từ hộp giới hạn
                    val x1 = idBox.x1 * frame.width
                    val y1 = idBox.y1 * frame.height
                    val x2 = idBox.x2 * frame.width
                    val y2 = idBox.y2 * frame.height
                    val (x, y, w, h) = if (isFlipped) {
                        val x = frame.width - x2
                        val y = frame.height - y2
                        val w = x2 - x1
                        val h = y2 - y1
                        Log.d("Detector", "Detected ROI for ID: x=$x, y=$y, w=$w, h=$h")
                        intArrayOf(x.toInt(), y.toInt(), w.toInt(), h.toInt())
                    } else {
                        Log.d("Detector", "Detected ROI for ID: x=$x1, y=$y1, w=${x2-x1}, h=${y2-y1}")
                        intArrayOf(x1.toInt(), y1.toInt(), (x2 - x1).toInt(), (y2 - y1).toInt())
                    }

                    // Đảm bảo kích thước vùng ROI ít nhất là 32x32
                    val adjustedW = maxOf(w, 32)
                    val adjustedH = maxOf(h, 32)
                    // Điều chỉnh x, y để vùng ROI không vượt ra ngoài frame
                    val adjustedX = maxOf(0, minOf(x, frame.width - adjustedW))
                    val adjustedY = maxOf(0, minOf(y, frame.height - adjustedH))
                    Log.d("Detector", "Adjusted ROI for ID: x=$adjustedX, y=$adjustedY, w=$adjustedW, h=$adjustedH")

                    // Cắt vùng ROI và thực hiện OCR
                    val ocrRoi = Bitmap.createBitmap(processedFrame, adjustedX, adjustedY, adjustedW, adjustedH)
                    Log.d("Detector", "=== OCR PROCESS START (ID - Back) ===")
                    Log.d("Detector", "ROI Size: ${adjustedW}x${adjustedH}")
                    Log.d("Detector", "ROI Position: ($adjustedX, $adjustedY)")
                    Log.d("Detector", "Frame Size: ${frame.width}x${frame.height}")
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val ocrResult = OcrHelper.ocr(ocrRoi)
                            Log.d("Detector", "OCR Result for ID (Back): '$ocrResult'")
                            val idNumber = OcrHelper.extractIdNumber(ocrResult)
                            if (idNumber != null) {
                                recognizedBackId = idNumber
                                cardDetectedTime?.let { detectedTime ->
                                    val totalTime = (System.currentTimeMillis() - detectedTime)
                                    message("Thời gian từ khi đặt thẻ đến khi hiển thị ID: $totalTime ms")
                                }
                                message("ID: $idNumber")
                                Log.d("Detector", "Successfully extracted ID (Back): $idNumber")
                                Log.d("Detector", "Sending message: ID: $idNumber")
                            } else {
                                Log.d("Detector", "Failed to extract ID (Back) from OCR result")
                            }
                        } catch (e: Exception) {
                            message("OCR Error (ID): ${e.message}")
                            Log.e("Detector", "OCR Error (ID): ${e.message}")
                        }
                    }
                }
            } else {
                Log.d("Detector", "OCR_BACK: Bỏ qua OCR mặt sau - recognizedBackId=${recognizedBackId}, hasID=${boxes.any { it.clsName.contains("ID") }}")
            }
        } else {
            Log.d("Detector", "OCR_CHECK: Bỏ qua OCR - scanButtonPressed=$scanButtonPressed, thongBaoDat=$thongBaoDat")
        }

        // Gửi kết quả phát hiện cho listener
        detectorListener.onDetect(boxes, inferenceTime)
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun bestBox(array: FloatArray): List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()

        for (c in 0 until numElements) {
            var maxConf = CONFIDENCE_THRESHOLD
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            while (j < numChannel) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > CONFIDENCE_THRESHOLD) {
                val clsName = labels[maxIdx]
                val cx = array[c] // 0
                val cy = array[c + numElements] // 1
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                val x1 = cx - (w / 2F)
                val y1 = cy - (h / 2F)
                val x2 = cx + (w / 2F)
                val y2 = cy + (h / 2F)
                if (x1 < 0F || x1 > 1F) continue
                if (y1 < 0F || y1 > 1F) continue
                if (x2 < 0F || x2 > 1F) continue
                if (y2 < 0F || y2 > 1F) continue

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, cls = maxIdx, clsName = clsName
                    )
                )
            }
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.removeAt(0)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = max(box1.x1, box2.x1)
        val y1 = max(box1.y1, box2.y1)
        val x2 = min(box1.x2, box2.x2)
        val y2 = min(box1.y2, box2.y2)
        val intersectionArea = max(0F, x2 - x1) * max(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    // Thêm hàm để set trạng thái nút quét
    fun setScanButtonPressed(pressed: Boolean) {
        scanButtonPressed = pressed
        if (pressed) {
            // Reset các biến trạng thái khi bắt đầu quét mới
            cardDetectedTime = null
            recognizedFrontId = null
            recognizedBackId = null
            // KHÔNG reset thongBaoDat để OCR có thể chạy ngay lập tức
            thongBaoRut = false
            Log.d("Detector", "SCAN_BUTTON: Đã bật quét và reset ID, giữ nguyên thongBaoDat=$thongBaoDat")
        } else {
            Log.d("Detector", "SCAN_BUTTON: Đã tắt quét")
        }
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.5F
        private const val IOU_THRESHOLD = 0.5F
    }
}
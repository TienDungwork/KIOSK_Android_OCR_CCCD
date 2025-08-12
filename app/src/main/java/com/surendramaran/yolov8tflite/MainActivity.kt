package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import yolov8tflite.R
import yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var mSocket: Socket

    // Add flag variables
    private var messageFlag = 0
    private var idNumberFlag = 0
    private var isFullScreenMode = false
    
    // Add counters for logging
    private var emptyDetectCount = 0
    private var lastLogTime = 0L
    
    // Add detection control flag
    private var isDetectionEnabled = false
    
    // Add timeout for ID reading
    private var idReadingStartTime = 0L
    private val ID_READING_TIMEOUT = 10000L // 10 seconds

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    init {
        try {
            // Đổi địa chỉ IP nếu bạn dùng thiết bị thật
            mSocket = IO.socket("http://192.168.5.1:8000")
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Socket URI error: ", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                updateCardInfo(it)
            }
        }

        setupSocket()
        setupToggleButton()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupSocket() {
        // Khi kết nối thành công
        mSocket.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "Connected to Socket.IO server")
            runOnUiThread {
                binding.deviceStatus.text = "Đã kết nối thiết bị"
                binding.deviceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
            }
            mSocket.emit("/info_set", "Hello from Android")
        }

        // Khi mất kết nối
        mSocket.on(Socket.EVENT_DISCONNECT) {
            Log.d(TAG, "Disconnected from Socket.IO server")
            runOnUiThread {
                binding.deviceStatus.text = "Mất kết nối thiết bị"
                binding.deviceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
            }
        }

        // Nhận dữ liệu từ sự kiện "/info"
        mSocket.on("/info") { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                val json = args[0] as JSONObject
                val version = json.optString("version", "N/A")
                val serial = json.optString("serial_nfc", "N/A")
                val vendor = json.optString("vendor_name", "N/A")
                val desc = json.optString("description", "N/A")

                val displayInfo = "Thiết bị đã kết nối:\n" +
                        "Version: $version\n" +
                        "Serial: $serial\n" +
                        "Vendor: $vendor\n" +
                        "Mô tả: $desc"

                Log.d(TAG, "INFO received: $displayInfo")
                runOnUiThread {
                    binding.deviceStatus.text = "Thiết bị đã kết nối"
                    binding.deviceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                }
            } else {
                Log.w(TAG, "Invalid /info data")
            }
        }

        // Nhận dữ liệu từ sự kiện "/event"
        mSocket.on("/event") { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                val json = args[0] as JSONObject
                val id = json.optInt("id", -1)
                val message = json.optString("message", "No message")
                val data = json.optJSONObject("data")

                if (data != null) {
                    runOnUiThread {
                        if (id == 4) {
                            // Xử lý ảnh base64
                            val base64Image = data.optString("img_data", "")
                            if (base64Image.isNotEmpty()) {
                                try {
                                    val decodedBytes = android.util.Base64.decode(base64Image, android.util.Base64.DEFAULT)
                                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                                    binding.cardImageView.setImageBitmap(bitmap)
                                    Log.d(TAG, "EVENT: Đã cập nhật ảnh thẻ từ sự kiện ID: 4")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error decoding base64 image: ", e)
                                }
                            }
                        } else {
                            // Cập nhật thông tin thẻ ID cho tất cả ID khác 4
                            val idCode = data.optString("idCode", "")
                            val personName = data.optString("personName", "")
                            val dateOfBirth = data.optString("dateOfBirth", "")
                            val gender = data.optString("gender", "")
                            val nationality = data.optString("nationality", "")
                            val originPlace = data.optString("originPlace", "")
                            val issueDate = data.optString("issueDate", "")
                            val expiryDate = data.optString("expiryDate", "")
                            
                            // Cập nhật thông tin nếu có dữ liệu
                            if (idCode.isNotEmpty() || personName.isNotEmpty() || dateOfBirth.isNotEmpty()) {
                                binding.idCode.text = idCode
                                binding.personName.text = personName
                                binding.dateOfBirth.text = dateOfBirth
                                binding.gender.text = gender
                                binding.nationality.text = nationality
                                binding.originPlace.text = originPlace
                                binding.issueDate.text = issueDate
                                binding.expiryDate.text = expiryDate
                                Log.d(TAG, "EVENT: Đã cập nhật thông tin thẻ từ sự kiện ID: $id")
                            } else {
                                Log.d(TAG, "EVENT: Sự kiện ID: $id có data nhưng không có thông tin hợp lệ")
                            }
                        }
                    }
                }

                val displayEvent = "Sự kiện:\nID: $id\nTin nhắn: $message"
                Log.d(TAG, "EVENT received: $displayEvent")

                runOnUiThread {
                    // Chỉ xử lý sự kiện có ID: 1
                    if (id == 1) {
                        Log.d(TAG, "EVENT: Xử lý sự kiện ID: 1 với message: $message")
                        // Chỉ reset thông tin khi đặt thẻ mới vào
                        if (message == "new card!") {
                            Log.d(TAG, "Phát hiện thẻ mới - Reset thông tin")
                            // Reset tất cả thông tin khi phát hiện thẻ mới
                            binding.idNumber.text = "ID: None"
                            binding.idCode.text = ""
                            binding.personName.text = ""
                            binding.dateOfBirth.text = ""
                            binding.gender.text = ""
                            binding.nationality.text = ""
                            binding.originPlace.text = ""
                            binding.issueDate.text = ""
                            binding.expiryDate.text = ""
                            binding.cardImageView.setImageBitmap(null)
                            
                            binding.cardStatus.text = "Đã phát hiện thẻ mới"
                            binding.cardStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_dark))
                            messageFlag = 1
                            
                            // Chỉ gọi sendDataWhenReady nếu detection đã được bật
                            if (isDetectionEnabled) {
                                Log.d(TAG, "EVENT: Detection đã bật - Bắt đầu kiểm tra ID")
                                // Kiểm tra ID ngay lập tức
                                val idText = binding.idNumber.text.toString()
                                if (idText.startsWith("ID: ")) {
                                    val idNumber = idText.substring(4).trim()
                                    if (idNumber != "None") {
                                        idNumberFlag = 1
                                        Log.d(TAG, "Đã có ID hợp lệ: $idNumber")
                                    }
                                }
                                sendDataWhenReady()
                            } else {
                                Log.d(TAG, "EVENT: Detection chưa bật - Chờ người dùng bật quét")
                                binding.cardStatus.text = "Đã phát hiện thẻ mới - Vui lòng bật quét"
                            }
                        } else if (message == "card removed!") {
                            // Chỉ reset cờ khi thẻ được rút ra, KHÔNG reset thông tin
                            messageFlag = 0
                            idNumberFlag = 0
                            binding.idNumber.text = "ID: None"
                            
                            // Tự động tắt detection khi thẻ được rút ra
                            isDetectionEnabled = false
                            updateScanButtonState()
                            
                            binding.cardStatus.text = "Thẻ đã được rút ra"
                            binding.cardStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                            Log.d(TAG, "Thẻ đã được rút ra - Chỉ reset cờ, giữ nguyên thông tin và tắt detection")
                        } else {
                            Log.d(TAG, "EVENT: Sự kiện ID: 1 với message không xác định: '$message' - Không reset thông tin")
                        }
                    } else {
                        Log.d(TAG, "EVENT: Bỏ qua sự kiện ID: $id với message: '$message' - Không phải ID: 1")
                    }
                }
            } else {
                Log.w(TAG, "Invalid /event data")
            }
        }

        mSocket.connect()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            // Chỉ chạy detection khi được bật
            if (!isDetectionEnabled) {
                imageProxy.close()
                return@setAnalyzer
            }
            
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            // Bind preview use case to viewFinder
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            
            // Cấu hình camera để có góc nhìn rộng hơn
            camera?.let { cam ->
                // Set zoom level để có góc nhìn rộng hơn (zoom < 1.0 = wide angle)
                cam.cameraControl.setZoomRatio(0.2f)
                
                // Hoặc có thể set linear zoom (0.0 = wide, 1.0 = tele)
                // cam.cameraControl.setLinearZoom(0.0f)
                
                // Cấu hình thêm để tối ưu hiển thị
                cam.cameraInfo.zoomState.observe(this) { zoomState ->
                    // Có thể thêm logic xử lý zoom state nếu cần
                }
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    // Cập nhật: Phương thức để cập nhật các TextView riêng biệt
    private fun updateCardInfo(message: String) {
        // Debug: Log tất cả message từ OCR
        Log.d(TAG, "OCR_DEBUG: Nhận message từ OCR: '$message'")
        
        // Chỉ xử lý OCR khi detection được bật
        if (!isDetectionEnabled) {
            Log.d(TAG, "OCR: Detection chưa bật - Bỏ qua message: $message")
            return
        }
        
        Log.d(TAG, "OCR: Nhận message: $message")
        runOnUiThread {
            when {
                message.contains("Card Side") -> {
                    // Trích xuất thông tin từ message
                    val cardSide = message.substringAfter("Card Side: ").substringBefore(",").trim()
                    val cardType = message.substringAfter("Type: ").substringBefore(",").trim()
                    val cardOrientation = message.substringAfter("Orientation: ").trim()
                    Log.d(TAG, "OCR: Phát hiện thẻ - Side: $cardSide, Type: $cardType, Orientation: $cardOrientation")

//                    binding.cardSide.text = "Side: $cardSide"
//                    binding.cardType.text = "Type: $cardType"
//                    binding.cardOrientation.text = "Orientation: $cardOrientation"
                }
                message.contains("Thẻ đã được rút ra") -> {
                    Log.d(TAG, "OCR: Thẻ đã được rút ra")
//                    binding.cardType.text = "Type: None"
//                    binding.cardSide.text = "Side: None"
//                    binding.cardOrientation.text = "Orientation: None"
                    binding.idNumber.text = "ID: None"
                }
                message.contains("Phát hiện thẻ CCCD") -> {
                    Log.d(TAG, "OCR: Phát hiện thẻ CCCD - Đang chờ OCR đọc ID")
                    // Không cần làm gì, chỉ log để biết OCR đang hoạt động
                }
                message.contains("ID_number:") -> {
                    val idNumber = message.removePrefix("ID_number: ").trim()
                    binding.idNumber.text = "ID: $idNumber"
                    Log.d(TAG, "OCR: Đọc được ID_number: $idNumber")
                }
                message.contains("ID:") -> {
                    val idNumber = message.removePrefix("ID: ").trim()
                    binding.idNumber.text = "ID: $idNumber"
                    Log.d(TAG, "OCR: Đọc được ID: $idNumber")
                }
                // Thêm pattern để tìm số ID trong message
                message.matches(Regex(".*\\d{9,12}.*")) -> {
                    // Tìm số có 9-12 chữ số trong message
                    val idMatch = Regex("\\d{9,12}").find(message)
                    if (idMatch != null) {
                        val idNumber = idMatch.value
                        binding.idNumber.text = "ID: $idNumber"
                        Log.d(TAG, "OCR: Tìm thấy ID trong message: $idNumber")
                    }
                }
                else -> {
                    Log.d(TAG, "OCR: Message không khớp với pattern nào: $message")
                    // Thêm log để xem có phải OCR đang trả về raw text không
                    if (message.length > 10) {
                        Log.d(TAG, "OCR: Có thể đây là raw OCR text, kiểm tra xem có số ID không")
                        val digitString = message.replace(Regex("[^0-9]"), "")
                        if (digitString.length >= 9) {
                            val idNumber = digitString.take(12)
                            binding.idNumber.text = "ID: $idNumber"
                            Log.d(TAG, "OCR: Tìm thấy ID từ raw text: $idNumber")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
        mSocket.disconnect()
        mSocket.off()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onEmptyDetect() {
        // Chỉ log khi detection được bật
        if (!isDetectionEnabled) return
        
        // Log khi không phát hiện thẻ
        emptyDetectCount++
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLogTime > 1000) {
            Log.d(TAG, "DETECT: Không phát hiện thẻ - Count: $emptyDetectCount lần trong 1 giây")
            emptyDetectCount = 0
            lastLogTime = currentTime
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        // Chỉ log khi detection được bật
        if (!isDetectionEnabled) return
        
        // Log khi phát hiện đối tượng
        Log.d(TAG, "DETECT: Phát hiện ${boundingBoxes.size} đối tượng, thời gian: ${inferenceTime}ms")
        for (i in boundingBoxes.indices) {
            val box = boundingBoxes[i]
            Log.d(TAG, "DETECT: Đối tượng $i - Class: ${box.clsName}, Confidence: ${box.cnf}")
        }
        
        // Kiểm tra xem có phát hiện thẻ không
        val cardDetected = boundingBoxes.any { it.clsName.contains("card", ignoreCase = true) || it.clsName.contains("id", ignoreCase = true) }
        if (cardDetected) {
            Log.d(TAG, "DETECT: Đã phát hiện thẻ - OCR sẽ được kích hoạt")
        } else {
            Log.d(TAG, "DETECT: Không phát hiện thẻ trong frame này")
        }
    }

    private fun sendDataWhenReady() {
        // Chỉ chạy khi detection được bật
        if (!isDetectionEnabled) {
            Log.d(TAG, "DATA_FLOW: Detection chưa bật - Bỏ qua kiểm tra ID")
            return
        }
        
        val handler = android.os.Handler(mainLooper)
        handler.post(object : Runnable {
            override fun run() {
                // Kiểm tra lại detection state trong mỗi lần chạy
                if (!isDetectionEnabled) {
                    Log.d(TAG, "DATA_FLOW: Detection đã tắt - Dừng kiểm tra ID")
                    return
                }
                
                // Kiểm tra timeout
                val currentTime = System.currentTimeMillis()
                if (currentTime - idReadingStartTime > ID_READING_TIMEOUT) {
                    Log.w(TAG, "DATA_FLOW: Timeout đọc ID sau ${ID_READING_TIMEOUT/1000}s - Dừng kiểm tra")
                    messageFlag = 0
                    idNumberFlag = 0
                    binding.cardStatus.text = "Timeout đọc ID - Vui lòng thử lại"
                    binding.cardStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_dark))
                    return
                }
                
                val idText = binding.idNumber.text.toString()
                if (idText.startsWith("ID: ")) {
                    val idNumber = idText.substring(4).trim()
                    Log.d(TAG, "DATA_FLOW: Kiểm tra ID: $idNumber, messageFlag: $messageFlag, idNumberFlag: $idNumberFlag")
                    
                    // Cập nhật cờ dựa trên trạng thái hiện tại
                    if (messageFlag == 1 && idNumber != "None") {
                        idNumberFlag = 1
                        Log.d(TAG, "DATA_FLOW: Đã cập nhật idNumberFlag = 1")
                    }
                    
                    // Gửi dữ liệu nếu có đủ cả hai cờ và ID hợp lệ
                    if (messageFlag == 1 && idNumberFlag == 1 && idNumber != "None" && idNumber.length >= 6) {
                        val lastSixDigits = idNumber.takeLast(6)
                        val inputData = JSONObject().apply {
                            put("idCode", lastSixDigits)
                        }
                        mSocket.emit("/input_data", inputData)
                        Log.d(TAG, "DATA_FLOW: Đã gửi dữ liệu thành công: $inputData")

                        // Reset cờ sau khi gửi thành công
                        messageFlag = 0
                        idNumberFlag = 0
                        Log.d(TAG, "DATA_FLOW: Đã reset cờ sau khi gửi thành công")
                    } else {
                        // Nếu có thông báo thẻ mới nhưng chưa có ID hợp lệ, tiếp tục kiểm tra
                        if (messageFlag == 1) {
                            Log.d(TAG, "DATA_FLOW: Đang chờ ID hợp lệ... (ID hiện tại: '$idNumber', thời gian: ${(currentTime - idReadingStartTime)/1000}s)")
                            handler.postDelayed(this, 100)
                        }
                    }
                } else {
                    // Nếu không có ID, reset cờ
                    messageFlag = 0
                    idNumberFlag = 0
                    Log.d(TAG, "DATA_FLOW: Không tìm thấy ID, đã reset cờ")
                }
            }
        })
    }

    private fun setupToggleButton() {
        binding.toggleCameraMode.setOnClickListener {
            isFullScreenMode = !isFullScreenMode
            updateCameraLayout()
        }
        
        // Setup scan button
        binding.scanCardButton.setOnClickListener {
            isDetectionEnabled = !isDetectionEnabled
            updateScanButtonState()
            Log.d(TAG, "SCAN: Detection ${if (isDetectionEnabled) "enabled" else "disabled"}")
            
            // Gọi detector để set trạng thái nút quét
            detector?.setScanButtonPressed(isDetectionEnabled)
            
            // Nếu bật detection và đã có thẻ mới (messageFlag = 1), bắt đầu kiểm tra ID
            if (isDetectionEnabled && messageFlag == 1) {
                Log.d(TAG, "SCAN: Detection được bật và có thẻ mới - Bắt đầu kiểm tra ID")
                binding.cardStatus.text = "Đã phát hiện thẻ mới"
                binding.cardStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_dark))
                idReadingStartTime = System.currentTimeMillis()
                sendDataWhenReady()
            }
        }
    }

    private fun updateCameraLayout() {
        if (isFullScreenMode) {
            // Chế độ toàn màn hình
            binding.viewFinder.layoutParams = (binding.viewFinder.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).apply {
                height = 0
                width = 0
                topToBottom = binding.statusLayout.id
                bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = 0
                leftMargin = 0
                rightMargin = 0
            }
            binding.mainInfoLayout.visibility = android.view.View.GONE
            binding.toggleCameraMode.text = "Hiển thị thông tin"
        } else {
            // Chế độ bình thường - Camera 50% màn hình
            binding.viewFinder.layoutParams = (binding.viewFinder.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).apply {
                height = 0
                width = 0
                topToBottom = binding.statusLayout.id
                bottomToTop = binding.mainInfoLayout.id
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = 4
                leftMargin = 4
                rightMargin = 4
                matchConstraintPercentHeight = 0.5f
            }
            binding.mainInfoLayout.visibility = android.view.View.VISIBLE
            binding.toggleCameraMode.text = "Toàn màn hình"
        }
    }

    private fun updateScanButtonState() {
        if (isDetectionEnabled) {
            binding.scanCardButton.text = "Dừng quét"
            binding.scanCardButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336")))
        } else {
            binding.scanCardButton.text = "Quét thẻ"
            binding.scanCardButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50")))
        }
    }
}
package com.surendramaran.yolov8tflite

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object OcrHelper {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Hàm OCR bất đồng bộ trả về văn bản từ Bitmap
    suspend fun ocr(bitmap: Bitmap): String? = suspendCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.textBlocks.joinToString("\n") { block ->
                    block.text
                }.trim()
                // Ghi log kết quả OCR để debug
                Log.d("OcrHelper", "=== OCR RESULT START ===")
                Log.d("OcrHelper", "Raw OCR Text: '$text'")
                Log.d("OcrHelper", "Text length: ${text.length}")
                Log.d("OcrHelper", "Text blocks count: ${visionText.textBlocks.size}")
                
                // Log từng block text riêng biệt
                visionText.textBlocks.forEachIndexed { index, block ->
                    Log.d("OcrHelper", "Block $index: '${block.text}'")
                }
                Log.d("OcrHelper", "=== OCR RESULT END ===")
                
                continuation.resume(text)
            }
            .addOnFailureListener { e ->
                Log.e("OcrHelper", "OCR Error: ${e.message}")
                continuation.resumeWithException(e)
            }
    }

    // Hàm trích xuất số ID từ văn bản (12 chữ số) - Dùng cho mặt sau
    fun extractIdNumber(text: String?): String? {
        if (text.isNullOrEmpty()) {
            Log.d("OcrHelper", "extractIdNumber: Text is null or empty")
            return null
        }
        // Trích xuất tất cả chữ số
        val digitString = text.replace(Regex("[^0-9]"), "")
        // Ghi log để debug
        Log.d("OcrHelper", "=== EXTRACT ID (BACK) ===")
        Log.d("OcrHelper", "Original text: '$text'")
        Log.d("OcrHelper", "Extracted digits: '$digitString'")
        Log.d("OcrHelper", "Digits length: ${digitString.length}")
        // Kiểm tra nếu có ít nhất 12 chữ số, lấy 12 chữ số cuối
        return if (digitString.length >= 12) {
            val result = digitString.takeLast(12)
            Log.d("OcrHelper", "Final ID (back): '$result'")
            Log.d("OcrHelper", "=== EXTRACT ID (BACK) END ===")
            result
        } else {
            Log.d("OcrHelper", "Not enough digits (need 12, got ${digitString.length})")
            Log.d("OcrHelper", "=== EXTRACT ID (BACK) END ===")
            null
        }
    }

    // Hàm trích xuất số ID từ mặt trước (CCCD)
    fun extractFrontIdNumber(text: String?): String? {
        if (text.isNullOrEmpty()) {
            Log.d("OcrHelper", "extractFrontIdNumber: Text is null or empty")
            return null
        }
        // Trích xuất tất cả chữ số từ văn bản
        val digitString = text.replace(Regex("[^0-9]"), "")
        // Ghi log để debug
        Log.d("OcrHelper", "=== EXTRACT ID (FRONT) ===")
        Log.d("OcrHelper", "Original text: '$text'")
        Log.d("OcrHelper", "Extracted digits: '$digitString'")
        Log.d("OcrHelper", "Digits length: ${digitString.length}")
        // Kiểm tra nếu có ít nhất 12 chữ số, lấy 12 chữ số đầu tiên
        return if (digitString.length >= 12) {
            val result = digitString.take(12)
            Log.d("OcrHelper", "Final ID (front): '$result'")
            Log.d("OcrHelper", "=== EXTRACT ID (FRONT) END ===")
            result
        } else {
            Log.d("OcrHelper", "Not enough digits (need 12, got ${digitString.length})")
            Log.d("OcrHelper", "=== EXTRACT ID (FRONT) END ===")
            null
        }
    }
}
# Hướng dẫn tích hợp thư viện ScanID (.aar) vào dự án Android khác

## 1. Giới thiệu
Thư viện ScanID cung cấp các tính năng nhận diện đối tượng (YOLOv8 + TensorFlow Lite) và nhận diện ký tự quang học (OCR) cho Android. Bạn đã có sẵn file `.aar` được build từ dự án này.

---

## 2. Tích hợp file AAR vào project Android của bạn

### Bước 1: Thêm file AAR vào project
- Copy file `.aar` (ví dụ: `scanid-release.aar`) vào thư mục `libs` của module app trong project Android bạn muốn sử dụng. Nếu chưa có thư mục `libs`, hãy tạo mới.

### Bước 2: Khai báo thư viện trong build.gradle
Mở file `build.gradle` (hoặc `build.gradle.kts`) của module app và thêm:

```gradle
repositories {
    flatDir {
        dirs 'libs'
    }
}

dependencies {
    implementation(name: 'scanid-release', ext: 'aar')
    // Khai báo các dependencies phụ thuộc bên dưới
}
```

### Bước 3: Khai báo dependencies phụ thuộc
Thư viện ScanID sử dụng nhiều thư viện khác. Bạn **bắt buộc** phải khai báo lại các dependencies này trong file `build.gradle` của project sử dụng:

```gradle
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("com.google.android.material:material:1.12.0")
implementation("androidx.constraintlayout:constraintlayout:2.1.4")
implementation("org.tensorflow:tensorflow-lite:2.16.1")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
implementation("com.google.mlkit:text-recognition:16.0.0")
implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
implementation("org.tensorflow:tensorflow-lite-api:2.16.1")
implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1")
implementation("org.tensorflow:tensorflow-lite-metadata:0.4.4")
implementation("com.github.jiangdongguo:AndroidUSBCamera:2.3.4")
implementation("io.socket:socket.io-client:2.0.1")
```

### Bước 4: Copy tài nguyên cần thiết
- Copy các file model `.tflite`, file `labels.txt` và các file assets khác từ thư mục `app/src/main/assets` của dự án ScanID sang thư mục `assets` của project sử dụng.
- Nếu sử dụng các layout, drawable, values... tuỳ chỉnh, hãy copy sang project sử dụng nếu bị thiếu khi build.

### Bước 5: Khai báo quyền trong AndroidManifest.xml
Thêm các quyền sau vào file `AndroidManifest.xml` của project sử dụng:

```xml
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
```

---

## 3. Sử dụng các class chính của thư viện

### Khởi tạo Detector
```kotlin
val detector = Detector(
    assetManager = context.assets,
    modelPath = "model.tflite", // Đường dẫn tới file model trong assets
    labelPath = "labels.txt",   // Đường dẫn tới file labels trong assets
    isQuantized = false          // true nếu model là quantized, false nếu float
)
```

### Nhận diện đối tượng từ ảnh
```kotlin
val results: List<BoundingBox> = detector.detect(bitmap)
```

### Overlay kết quả lên ảnh
**Trong layout XML:**
```xml
<com.surendramaran.yolov8tflite.OverlayView
    android:id="@+id/overlayView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"/>
```
**Trong code:**
```kotlin
overlayView.setResults(results)
```

### Nhận diện ký tự (OCR)
```kotlin
val ocrHelper = OcrHelper(context)
val text = ocrHelper.recognizeText(bitmap)
```

### Sử dụng WebSocketServer (nếu cần)
```kotlin
val server = WebSocketServer(port = 8080)
server.start()
```

---

## 4. Lưu ý
- **Bắt buộc phải khai báo lại dependencies** như trên, nếu không sẽ lỗi khi build hoặc runtime.
- Đảm bảo copy đầy đủ file model, labels, assets cần thiết.
- Đảm bảo cấp quyền camera, storage khi chạy app.
- Nếu thiếu resource (layout, drawable, values,...) thì copy từ dự án ScanID sang.

---

## 5. Hỗ trợ
Nếu gặp vấn đề khi tích hợp hoặc sử dụng, vui lòng liên hệ nhóm phát triển hoặc tham khảo mã nguồn dự án ScanID.

---

**Chúc bạn tích hợp thành công!** 
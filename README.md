# Hướng Dẫn Sử Dụng Thư Viện Nhận Diện Đối Tượng YOLOv8 Cho Ứng Dụng Android

## 1. Thư viện này là gì?
Đây là **thư viện nhận diện đối tượng bằng AI (YOLOv8)** dành cho các ứng dụng Android. Thư viện được đóng gói dưới dạng file `.aar` để dễ dàng tích hợp vào bất kỳ app Android nào mà không cần phải biết lập trình chuyên sâu.

## 2. Bạn sẽ nhận được gì?
- File thư viện: `yolov8-detection.aar`
- File mô hình AI: `.tflite` (ví dụ: `model.tflite`)
- File nhãn đối tượng: `labels.txt`
- Hướng dẫn sử dụng (chính là tài liệu này)

## 3. Cách tích hợp vào ứng dụng Android

### Bước 1: Gửi file cho lập trình viên Android
Bạn chỉ cần gửi các file sau cho lập trình viên:
- `yolov8-detection.aar`
- `model.tflite`
- `labels.txt`
- Hướng dẫn này

### Bước 2: Lập trình viên thực hiện các bước sau

#### 2.1. Thêm file thư viện vào project
- Copy file `yolov8-detection.aar` vào thư mục `app/libs/` của dự án Android.
- Copy file `model.tflite` và `labels.txt` vào thư mục `app/src/main/assets/`.

#### 2.2. Cấu hình project để nhận diện đối tượng
- Thêm dòng sau vào file `build.gradle`:

```gradle
dependencies {
    implementation files('libs/yolov8-detection.aar')
    // Thêm các thư viện phụ thuộc nếu cần (TensorFlow Lite, CameraX...)
}
```
- Đảm bảo đã cấp quyền sử dụng camera và bộ nhớ trong file `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

#### 2.3. Sử dụng thư viện trong code
- Lập trình viên chỉ cần gọi các hàm có sẵn để nhận diện đối tượng từ camera hoặc ảnh.
- Có thể sử dụng sẵn giao diện mẫu hoặc tích hợp vào giao diện của app hiện tại.

**Ví dụ sử dụng nhanh:**
```kotlin
// Khởi tạo detector
val detector = Detector(context)
detector.initialize()

// Nhận diện đối tượng từ ảnh
val results = detector.detect(bitmap)

// Hiển thị kết quả lên giao diện
overlayView.setResults(results)
```

## 4. Thay đổi mô hình AI hoặc nhãn đối tượng
- Nếu muốn nhận diện đối tượng khác, chỉ cần thay file `model.tflite` và `labels.txt` trong thư mục `assets/`.
- Không cần thay đổi code.

## 5. Câu hỏi thường gặp

### 5.1. Thư viện này có cần internet không?
- Không bắt buộc. Chỉ cần internet nếu sử dụng tính năng WebSocket hoặc cập nhật từ xa.

### 5.2. Có cần kiến thức AI để sử dụng không?
- Không cần. Thư viện đã đóng gói sẵn, chỉ cần tích hợp vào app.

### 5.3. Có thể nhận diện những đối tượng nào?
- Tùy vào file `model.tflite` và `labels.txt` bạn cung cấp. Có thể nhận diện người, xe, vật thể, ...

### 5.4. Có thể dùng cho nhiều app khác nhau không?
- Hoàn toàn được. Chỉ cần copy file `.aar` và các file model vào app mới.

## 6. Hỗ trợ kỹ thuật
Nếu gặp khó khăn khi tích hợp hoặc sử dụng, vui lòng liên hệ:
- Email/Zalo: [Điền thông tin liên hệ của bạn ở đây]
- Hoặc gửi câu hỏi qua [GitHub Issues](https://github.com/surendramaran/Machine-Learning-in-Mobile/issues/new)

---

**Lưu ý:**
- Tài liệu này dành cho khách hàng không chuyên lập trình. Nếu bạn là lập trình viên, hãy xem phần hướng dẫn chi tiết trong code hoặc liên hệ để nhận tài liệu kỹ thuật nâng cao.
- Đảm bảo test thử trên thiết bị thật trước khi triển khai chính thức.

---

**Cảm ơn bạn đã sử dụng thư viện nhận diện đối tượng YOLOv8!**

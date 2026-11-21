# Mini Game Box (NES + Neo Geo) cho Android 6.0.1

APK rất nhẹ, KHÔNG chứa core và BIOS. Bạn tự chép vào thư mục của app trên box.

## Build
Upload toàn bộ lên GitHub -> Actions -> Build APK -> tải artifact `MiniGameBox-apk`.

## Cài core và BIOS
Mở app lần đầu, app tự tạo thư mục và hiện đường dẫn ở màn hình bên trái, thường là:

    /sdcard/MiniGameBox/cores   <- core: fceumm (NES), fbneo (Neo Geo)
    /sdcard/MiniGameBox/bios    <- neogeo.zip (và disksys.rom nếu chơi FDS)

Cũng nhận thư mục MiniGameBox ở gốc USB. Tên core chỉ cần bắt đầu bằng `fceumm` hoặc `fbneo`
(vd `fceumm_libretro_android.so`), có thể để nguyên file `.so.zip`, app tự giải nén.
Chép xong quay lại app (hoặc mở lại), màn hình trái sẽ báo CÓ / THIẾU.
Core là bản Android armeabi-v7a (32-bit).

## Dùng
- NES: file .nes/.fds/.unf. Neo Geo: file .zip romset (đúng phiên bản FBNeo).
- Đang chơi: BACK hoặc nút Mode = tạm dừng, về màn chia đôi. "Tiếp tục" để chơi tiếp.
- "Cài đặt phím": chọn nút, bấm phím trên tay cầm để gán. Hỗ trợ 2 tay cầm.

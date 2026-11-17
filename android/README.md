# ChatSystem – Real-time Messaging Demo

Gồm 2 phần như thiết kế: **A. ứng dụng Android** (chạy 2–3 emulator) và **B. bảng điều khiển hệ thống** (web, chạy trên máy tính).

## 1. Chạy backend
```
cd backend   # thư mục backend/ nằm trong project này
docker compose up -d                  # PostgreSQL + Redis
pip install -r requirements.txt
python manage.py makemigrations chat
python manage.py migrate
python manage.py seed_demo            # tài khoản Phu / An / Binh, mật khẩu 123456
daphne -b 0.0.0.0 -p 8000 chatsystem.asgi:application
python manage.py run_presence_sweeper # terminal riêng: "presence server" đánh offline khi hết heartbeat
```
Không có Docker/PostgreSQL: đặt `USE_SQLITE=1` (Redis vẫn bắt buộc).
Mở **http://localhost:8000/monitor/** để xem dashboard (số liệu và Event Log lấy từ backend thật).
Nếu số WebSocket/online bị kẹt sau khi tắt server đột ngột: `redis-cli FLUSHALL`.

## 2. Mở app Android (đã cấu hình sẵn trong project này)
1. Android Studio → Open thư mục `Chat/` này → Sync Gradle (không cần chép file hay sửa manifest nữa).
2. Chạy trên 2–3 emulator. Địa chỉ server mặc định `http://10.0.2.2:8000/` (localhost của máy tính); đổi được ở màn hình Login.
3. Package/namespace: `com.example.chatsystem`; theme: `Theme.ChatSystem`.
4. Nếu báo thiếu `HorizontalDivider`, dùng `Divider` (Material3 bản cũ).

## 3. Kịch bản demo 6 tình huống
| # | Thao tác | Quan sát |
|---|----------|----------|
| 1 | Emulator A đăng nhập Phu, B đăng nhập An. Phu nhắn An | An nhận ngay; Event Log hiện received → validated → saved → dispatched → delivered; tin của Phu từ ✓ sang ✓✓ |
| 2 | Trên B: menu ⋮ → **Ngắt kết nối**. Phu gửi tin | "Pending sync" trên dashboard tăng; Phu chỉ có ✓ |
| 3 | Trên B: **Kết nối lại** | B nhận banner "Đã đồng bộ N tin"; Pending về 0; Phu thấy ✓✓ |
| 4 | Phu bấm + → Nhóm → chọn An, Binh | An/Binh thấy nhóm trong danh sách, cả 3 cùng nhắn; header nhóm hiện ai online |
| 5 | Đăng nhập cùng tài khoản trên cả A và B | Tin nhắn hiện ở cả hai thiết bị (WS connections = 2 cho 1 user) |
| 6 | Trên B: **Treo heartbeat** (giữ socket nhưng ngừng gửi) | Sau `timeout` giây, Event Log: "User offline – no heartbeat"; A thấy chấm xám |

Gợi ý: trên dashboard chọn preset **Demo 5s/15s** để tình huống 6 xảy ra sau ~15 giây; dùng **Cân bằng 15s/45s** khi thực tế. Mọi thiết bị đổi ngay, không cần build lại.
"Ngắt kết nối" đóng socket nên offline ngay lập tức; "Treo heartbeat" mới minh hoạ cơ chế timeout.

## 4. Đã làm / chưa làm
- Có: đăng ký/đăng nhập (token), chat 1-1, nhóm (thêm thành viên qua `POST /api/conversations/<id>/members/`), lịch sử (PostgreSQL + cache Room), trạng thái ✓/✓✓, typing, presence + heartbeat tuỳ chỉnh, đồng bộ offline theo id tin cuối, nhiều thiết bị, tự kết nối lại, gửi lại tin chờ không trùng (client_id), Monitor + Event Log.
- Notification chỉ mô phỏng bằng banner trong app (chưa dùng FCM/NotificationManager). Màn hình tạo nhóm chưa có giao diện "thêm thành viên sau" (API có sẵn).
- Bảo mật mức demo: PUT cấu hình heartbeat và API monitor không cần đăng nhập; không có HTTPS/E2EE.
- Backend đã được chạy thử (SQLite + Redis): login, WebSocket, ACK, đồng bộ offline, tạo nhóm, monitor. App Android chưa được biên dịch trong môi trường tạo mã (không có Android SDK). Khi build/chạy báo lỗi, gửi lại nội dung lỗi để sửa.

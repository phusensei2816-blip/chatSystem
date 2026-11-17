# ChatSystem – Real-time Messaging Demo

Gồm 2 phần như thiết kế: **A. ứng dụng Android** (chạy 2–3 emulator) và **B. bảng điều khiển hệ thống** (web, chạy trên máy tính).

## 1. Chạy backend
```
cd backend
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

import time
from django.core.management.base import BaseCommand
from chat import services as S


class Command(BaseCommand):
    help = "Presence server: đánh dấu offline khi hết heartbeat. Chạy ở terminal riêng."

    def handle(self, *a, **k):
        while True:
            S.sweep()
            time.sleep(max(1, S.get_config()["interval"] / 2))   # đọc lại cấu hình mỗi vòng

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand


class Command(BaseCommand):
    help = "Tạo 3 tài khoản demo: Phu, An, Binh (mật khẩu 123456)"

    def handle(self, *a, **k):
        U = get_user_model()
        for n in ("Phu", "An", "Binh"):
            if not U.objects.filter(username=n).exists():
                U.objects.create_user(username=n, password="123456")
        self.stdout.write("OK: Phu / An / Binh, mật khẩu 123456")

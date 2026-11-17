import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
SECRET_KEY = "demo-only-not-for-production"
DEBUG = True
ALLOWED_HOSTS = ["*"]
INSTALLED_APPS = [
    "daphne", "django.contrib.auth", "django.contrib.contenttypes",
    "rest_framework", "rest_framework.authtoken", "channels", "chat",
]
MIDDLEWARE = ["django.middleware.common.CommonMiddleware"]
ROOT_URLCONF = "chatsystem.urls"
ASGI_APPLICATION = "chatsystem.asgi.application"
TEMPLATES = [{"BACKEND": "django.template.backends.django.DjangoTemplates",
              "DIRS": [], "APP_DIRS": True, "OPTIONS": {"context_processors": []}}]

if os.getenv("USE_SQLITE"):   # chạy nhanh không cần PostgreSQL
    DATABASES = {"default": {"ENGINE": "django.db.backends.sqlite3", "NAME": BASE_DIR / "db.sqlite3"}}
else:
    DATABASES = {"default": {
        "ENGINE": "django.db.backends.postgresql", "NAME": os.getenv("PG_DB", "chat"),
        "USER": os.getenv("PG_USER", "chat"), "PASSWORD": os.getenv("PG_PASSWORD", "chat"),
        "HOST": os.getenv("PG_HOST", "localhost"), "PORT": os.getenv("PG_PORT", "5432")}}

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")
CHANNEL_LAYERS = {"default": {"BACKEND": "channels_redis.core.RedisChannelLayer",
                              "CONFIG": {"hosts": [REDIS_URL]}}}
REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": ["rest_framework.authentication.TokenAuthentication"],
    "DEFAULT_PERMISSION_CLASSES": ["rest_framework.permissions.IsAuthenticated"],
    "DEFAULT_RENDERER_CLASSES": ["rest_framework.renderers.JSONRenderer"],
}
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
USE_TZ = True

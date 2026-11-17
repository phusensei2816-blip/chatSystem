import os
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "chatsystem.settings")
from django.core.asgi import get_asgi_application
django_app = get_asgi_application()

from channels.routing import ProtocolTypeRouter, URLRouter
from chat.auth_ws import TokenAuthMiddleware
from chat.routing import websocket_urlpatterns

application = ProtocolTypeRouter({
    "http": django_app,
    "websocket": TokenAuthMiddleware(URLRouter(websocket_urlpatterns)),
})

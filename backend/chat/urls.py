from django.urls import path
from . import views as v

urlpatterns = [
    path("auth/register/", v.register), path("auth/login/", v.login),
    path("users/", v.users),
    path("conversations/", v.conversations),
    path("conversations/<int:cid>/members/", v.add_members),
    path("conversations/<int:cid>/messages/", v.history),
    path("sync/", v.sync),
    path("presence/config/", v.presence_config),
    path("monitor/stats/", v.monitor_stats), path("monitor/events/", v.monitor_events),
]

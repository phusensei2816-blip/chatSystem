from django.urls import path, include
from django.views.generic import TemplateView

urlpatterns = [
    path("api/", include("chat.urls")),
    path("monitor/", TemplateView.as_view(template_name="monitor.html")),
]

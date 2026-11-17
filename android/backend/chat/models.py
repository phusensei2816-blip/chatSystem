from django.conf import settings
from django.db import models

U = settings.AUTH_USER_MODEL


class Conversation(models.Model):
    kind = models.CharField(max_length=10, default="direct")          # direct | group
    name = models.CharField(max_length=100, blank=True)
    direct_key = models.CharField(max_length=40, unique=True, null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)


class Member(models.Model):
    conversation = models.ForeignKey(Conversation, related_name="members", on_delete=models.CASCADE)
    user = models.ForeignKey(U, on_delete=models.CASCADE)

    class Meta:
        unique_together = ("conversation", "user")


class Message(models.Model):
    conversation = models.ForeignKey(Conversation, related_name="messages", on_delete=models.CASCADE)
    sender = models.ForeignKey(U, on_delete=models.CASCADE)
    text = models.TextField()
    client_id = models.CharField(max_length=64)       # để gửi lại không tạo tin trùng
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = ("sender", "client_id")
        indexes = [models.Index(fields=["conversation", "id"])]


class Delivery(models.Model):
    """Mỗi người nhận một dòng. delivered_at null = tin đang chờ đồng bộ."""
    message = models.ForeignKey(Message, related_name="deliveries", on_delete=models.CASCADE)
    user = models.ForeignKey(U, on_delete=models.CASCADE)
    delivered_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        unique_together = ("message", "user")

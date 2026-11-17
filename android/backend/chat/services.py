"""Toàn bộ logic nghiệp vụ (đồng bộ). Consumer gọi qua database_sync_to_async."""
import json, time, uuid
import redis
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from django.conf import settings
from django.db import connection
from django.utils import timezone

r = redis.Redis.from_url(settings.REDIS_URL, decode_responses=True)

PRESETS = {"demo": (5, 15), "balanced": (15, 45), "battery": (30, 90)}
CFG_KEY, ONLINE_SET, DEV_ZSET = "presence:config", "presence:online_users", "presence:devices"
NAMES, LAST_SEEN, WS_SET = "presence:names", "presence:last_seen", "ws:connections"
GROUP_UPDATES, GROUP_CONFIG = "presence_updates", "presence_config"


def group_send(group, payload):
    async_to_sync(get_channel_layer().group_send)(group, payload)


# ---------- Event log (hiển thị trên Monitor) ----------
def log_event(step, detail=""):
    eid = r.incr("events:seq")
    r.lpush("events", json.dumps({"id": eid, "ts": time.time(), "step": step, "detail": detail}))
    r.ltrim("events", 0, 199)


def events_since(since=0):
    items = [json.loads(x) for x in r.lrange("events", 0, 199)]
    return sorted([e for e in items if e["id"] > since], key=lambda e: e["id"])


# ---------- Cấu hình heartbeat ----------
def get_config():
    d = r.hgetall(CFG_KEY)
    return {"interval": int(d["interval"]), "timeout": int(d["timeout"])} if d else {"interval": 15, "timeout": 45}


def set_config(interval, timeout):
    if not 1 <= interval <= 120:
        raise ValueError("interval phải trong khoảng 1-120 giây")
    if timeout < interval * 2 or timeout > 600:
        raise ValueError("timeout phải >= 2 x interval và <= 600")
    r.hset(CFG_KEY, mapping={"interval": interval, "timeout": timeout})
    log_event("Heartbeat config changed", f"interval={interval}s | timeout={timeout}s")
    group_send(GROUP_CONFIG, {"type": "config.update", "interval": interval, "timeout": timeout})
    return {"interval": interval, "timeout": timeout}


# ---------- Presence ----------
def _presence_event(uid, online, ts=None):
    group_send(GROUP_UPDATES, {"type": "presence.event", "user_id": uid, "online": online, "last_seen": ts})


def beat(uid, username, did):
    r.hset(NAMES, str(uid), username)
    r.zadd(DEV_ZSET, {f"{uid}:{did}": time.time()})
    if r.sadd(ONLINE_SET, str(uid)):
        log_event("User online", username)
        _presence_event(uid, True)


def _go_offline(uid, reason):
    if r.srem(ONLINE_SET, str(uid)):
        ts = time.time()
        r.hset(LAST_SEEN, str(uid), ts)
        log_event("User offline", f"{r.hget(NAMES, str(uid))} | {reason}")
        _presence_event(uid, False, ts)


def _alive_users(timeout):
    alive = r.zrangebyscore(DEV_ZSET, time.time() - timeout, "+inf")
    return {m.split(":", 1)[0] for m in alive}


def drop_device(uid, did):
    r.zrem(DEV_ZSET, f"{uid}:{did}")
    if str(uid) not in _alive_users(get_config()["timeout"]):
        _go_offline(uid, "WebSocket disconnected")


def sweep():
    timeout = get_config()["timeout"]
    alive = _alive_users(timeout)
    for uid in r.smembers(ONLINE_SET):
        if uid not in alive:
            _go_offline(uid, f"no heartbeat for {timeout}s")
    r.zremrangebyscore(DEV_ZSET, 0, time.time() - timeout * 10)


def ws_add(channel, username, did):
    r.sadd(WS_SET, channel)
    log_event("WebSocket connected", f"{username} | device {did[:8]}")


def ws_remove(channel, username, did):
    r.srem(WS_SET, channel)
    log_event("WebSocket disconnected", f"{username} | device {did[:8]}")


def online_ids():
    return {int(x) for x in r.smembers(ONLINE_SET)}


# ---------- Tin nhắn ----------
def msg_dict(m):
    pending = any(d.delivered_at is None for d in m.deliveries.all())
    return {"id": m.id, "conversation_id": m.conversation_id, "sender_id": m.sender_id,
            "sender_name": m.sender.username, "text": m.text, "client_id": m.client_id,
            "created_at": int(m.created_at.timestamp() * 1000),
            "status": "sent" if pending else "delivered"}


def member_ids(conv_id):
    from chat.models import Member
    return list(Member.objects.filter(conversation_id=conv_id).values_list("user_id", flat=True))


def store_message(user, conv_id, text, client_id):
    from chat.models import Delivery, Message
    from django.contrib.auth import get_user_model
    log_event("Message received", f"Sender: {user.username} | Conversation: {conv_id}")
    text = (text or "").strip()
    ids = member_ids(conv_id)
    if not text or user.id not in ids:
        log_event("Message rejected", f"{user.username} | not a member or empty")
        return None
    others = get_user_model().objects.filter(id__in=ids).exclude(id=user.id)
    log_event("Message validated", "Recipients: " + ", ".join(u.username for u in others))
    m, created = Message.objects.get_or_create(
        sender=user, client_id=client_id or uuid.uuid4().hex,
        defaults={"conversation_id": conv_id, "text": text})
    if created:
        Delivery.objects.bulk_create([Delivery(message=m, user_id=u) for u in ids if u != user.id])
        log_event("Message saved", f"PostgreSQL | Message ID: {m.id}")
    return msg_dict(m), ids, created


def mark_delivered(user, message_ids, via, each=True):
    from chat.models import Delivery
    n = 0
    qs = Delivery.objects.filter(user=user, delivered_at__isnull=True,
                                 message_id__in=message_ids).select_related("message")
    for d in qs:
        d.delivered_at = timezone.now()
        d.save(update_fields=["delivered_at"])
        n += 1
        if each:
            log_event("Message delivered", f"Recipient: {user.username} | {via}")
        group_send(f"user_{d.message.sender_id}",
                   {"type": "chat.receipt", "message_id": d.message_id, "user_id": user.id})
    return n


# ---------- Monitor ----------
def stats():
    from chat.models import Delivery, Message
    pg = redis_ok = True
    try:
        connection.ensure_connection()
    except Exception:
        pg = False
    try:
        r.ping()
    except Exception:
        redis_ok = False
    names = [r.hget(NAMES, u) or u for u in r.smembers(ONLINE_SET)] if redis_ok else []
    return {
        "online_users": sorted(names),
        "ws_connections": r.scard(WS_SET) if redis_ok else 0,
        "total_messages": Message.objects.count() if pg else 0,
        "pending_sync": Delivery.objects.filter(delivered_at__isnull=True).count() if pg else 0,
        "redis": redis_ok, "postgres": pg, "heartbeat": get_config(),
    }

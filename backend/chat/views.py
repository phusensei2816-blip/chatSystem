from django.contrib.auth import authenticate, get_user_model
from django.db import IntegrityError
from rest_framework.authtoken.models import Token
from rest_framework.decorators import api_view, permission_classes
from rest_framework.permissions import AllowAny
from rest_framework.response import Response

from . import services as S
from .models import Conversation, Member, Message

User = get_user_model()


def _auth_resp(user):
    token, _ = Token.objects.get_or_create(user=user)
    return Response({"token": token.key, "user_id": user.id, "username": user.username})


@api_view(["POST"])
@permission_classes([AllowAny])
def register(request):
    u, p = request.data.get("username", "").strip(), request.data.get("password", "")
    if not u or len(p) < 4:
        return Response({"error": "Cần username và mật khẩu >= 4 ký tự"}, status=400)
    try:
        user = User.objects.create_user(username=u, password=p)
    except IntegrityError:
        return Response({"error": "Username đã tồn tại"}, status=400)
    return _auth_resp(user)


@api_view(["POST"])
@permission_classes([AllowAny])
def login(request):
    user = authenticate(username=request.data.get("username"), password=request.data.get("password"))
    if not user:
        return Response({"error": "Sai username hoặc mật khẩu"}, status=400)
    return _auth_resp(user)


@api_view(["GET"])
def users(request):
    on = S.online_ids()
    return Response([{"id": u.id, "username": u.username, "online": u.id in on}
                     for u in User.objects.exclude(id=request.user.id).order_by("username")])


def conv_dict(c, me):
    on = S.online_ids()
    ms = [m.user for m in c.members.select_related("user")]
    name = c.name if c.kind == "group" else next((u.username for u in ms if u.id != me.id), "?")
    last = c.messages.select_related("sender").prefetch_related("deliveries").order_by("-id").first()
    return {"id": c.id, "kind": c.kind, "name": name,
            "members": [{"id": u.id, "username": u.username, "online": u.id in on} for u in ms],
            "last_message": S.msg_dict(last) if last else None}


@api_view(["GET", "POST"])
def conversations(request):
    me = request.user
    if request.method == "GET":
        cs = Conversation.objects.filter(members__user=me).distinct()
        data = sorted((conv_dict(c, me) for c in cs),
                      key=lambda d: (d["last_message"] or {}).get("created_at", 0), reverse=True)
        return Response(data)
    if request.data.get("kind") == "group":
        ids = {int(i) for i in request.data.get("member_ids", [])} | {me.id}
        c = Conversation.objects.create(kind="group", name=request.data.get("name") or "Nhóm mới")
        Member.objects.bulk_create([Member(conversation=c, user_id=i) for i in ids])
        S.log_event("Group created", f"{c.name} | {len(ids)} members | by {me.username}")
        for i in ids - {me.id}:
            S.group_send(f"user_{i}", {"type": "chat.conversation", "conversation_id": c.id})
        return Response(conv_dict(c, me))
    other = int(request.data["user_id"])
    key = f"{min(me.id, other)}-{max(me.id, other)}"
    c, new = Conversation.objects.get_or_create(direct_key=key, defaults={"kind": "direct"})
    if new:
        Member.objects.bulk_create([Member(conversation=c, user_id=me.id), Member(conversation=c, user_id=other)])
        S.group_send(f"user_{other}", {"type": "chat.conversation", "conversation_id": c.id})
    return Response(conv_dict(c, me))


@api_view(["POST"])
def add_members(request, cid):
    c = Conversation.objects.get(id=cid, kind="group", members__user=request.user)
    for i in request.data.get("user_ids", []):
        Member.objects.get_or_create(conversation=c, user_id=int(i))
        S.group_send(f"user_{i}", {"type": "chat.conversation", "conversation_id": c.id})
    return Response(conv_dict(c, request.user))


@api_view(["GET"])
def history(request, cid):
    if not Member.objects.filter(conversation_id=cid, user=request.user).exists():
        return Response({"error": "forbidden"}, status=403)
    qs = Message.objects.filter(conversation_id=cid).select_related("sender").prefetch_related("deliveries")
    return Response([S.msg_dict(m) for m in qs.order_by("-id")[:200][::-1]])


@api_view(["GET"])
def sync(request):
    """Trả các tin có id > after (cur_max_message_id của thiết bị) trong mọi hội thoại của user."""
    after = int(request.GET.get("after", 0))
    msgs = list(Message.objects.filter(conversation__members__user=request.user, id__gt=after)
                .select_related("sender").prefetch_related("deliveries").order_by("id")[:500])
    S.mark_delivered(request.user, [m.id for m in msgs if m.sender_id != request.user.id], "sync", each=False)
    if msgs:
        S.log_event("Offline messages synced", f"{request.user.username} | {len(msgs)} messages | after id {after}")
    return Response([S.msg_dict(m) for m in msgs])


@api_view(["GET", "PUT"])
@permission_classes([AllowAny])   # demo: dashboard đổi heartbeat không cần đăng nhập
def presence_config(request):
    if request.method == "PUT":
        try:
            if "preset" in request.data:
                i, t = S.PRESETS[request.data["preset"]]
            else:
                i, t = int(request.data["interval"]), int(request.data["timeout"])
            return Response(S.set_config(i, t))
        except (KeyError, ValueError) as e:
            return Response({"error": str(e)}, status=400)
    return Response({**S.get_config(), "presets": {k: {"interval": v[0], "timeout": v[1]} for k, v in S.PRESETS.items()}})


@api_view(["GET"])
@permission_classes([AllowAny])
def monitor_stats(request):
    return Response(S.stats())


@api_view(["GET"])
@permission_classes([AllowAny])
def monitor_events(request):
    return Response(S.events_since(int(request.GET.get("since", 0))))

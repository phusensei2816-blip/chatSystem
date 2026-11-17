import time
from channels.db import database_sync_to_async as db
from channels.generic.websocket import AsyncJsonWebsocketConsumer
from . import services as S


class ChatConsumer(AsyncJsonWebsocketConsumer):
    async def connect(self):
        self.user = self.scope["user"]
        if self.user.is_anonymous:
            return await self.close(code=4401)
        self.did = self.scope["device_id"]
        self.joined = [f"user_{self.user.id}", S.GROUP_UPDATES, S.GROUP_CONFIG]
        for g in self.joined:
            await self.channel_layer.group_add(g, self.channel_name)
        await self.accept()
        await db(S.ws_add)(self.channel_name, self.user.username, self.did)
        await self.send_json({"type": "config", **await db(S.get_config)()})
        await db(S.beat)(self.user.id, self.user.username, self.did)

    async def disconnect(self, code):
        if not hasattr(self, "joined"):
            return
        for g in self.joined:
            await self.channel_layer.group_discard(g, self.channel_name)
        await db(S.ws_remove)(self.channel_name, self.user.username, self.did)
        await db(S.drop_device)(self.user.id, self.did)

    async def receive_json(self, c, **kw):
        t = c.get("type")
        if t == "heartbeat":
            await db(S.beat)(self.user.id, self.user.username, self.did)
            await self.send_json({"type": "heartbeat_ack", "server_time": time.time()})
        elif t == "send_message":
            res = await db(S.store_message)(self.user, c.get("conversation_id"), c.get("text"), c.get("client_id"))
            if res is None:
                return await self.send_json({"type": "error", "client_id": c.get("client_id")})
            msg, ids, created = res
            await self.send_json({"type": "message_sent", "client_id": msg["client_id"], "message": msg})
            if created:   # gửi lại cùng client_id thì không phát lại
                for uid in ids:
                    await self.channel_layer.group_send(f"user_{uid}", {"type": "chat.message", "message": msg})
                await db(S.log_event)("Message dispatched", f"Redis Channel Layer | {len(ids)} members")
        elif t == "ack":
            await db(S.mark_delivered)(self.user, c.get("message_ids", []), "WebSocket")
        elif t == "typing":
            cid = c.get("conversation_id")
            ids = await db(S.member_ids)(cid)
            if self.user.id in ids:
                for uid in ids:
                    if uid != self.user.id:
                        await self.channel_layer.group_send(f"user_{uid}", {
                            "type": "chat.typing", "conversation_id": cid, "username": self.user.username})

    # ---- handler cho group_send: chuyển tiếp xuống WebSocket của thiết bị ----
    async def chat_message(self, e):
        await self.send_json({"type": "new_message", "message": e["message"]})

    async def chat_receipt(self, e):
        await self.send_json({"type": "receipt", "message_id": e["message_id"], "user_id": e["user_id"]})

    async def chat_typing(self, e):
        await self.send_json({"type": "typing", "conversation_id": e["conversation_id"], "username": e["username"]})

    async def chat_conversation(self, e):
        await self.send_json({"type": "conversation", "conversation_id": e["conversation_id"]})

    async def presence_event(self, e):
        await self.send_json({"type": "presence", "user_id": e["user_id"],
                              "online": e["online"], "last_seen": e["last_seen"]})

    async def config_update(self, e):
        await self.send_json({"type": "config", "interval": e["interval"], "timeout": e["timeout"]})

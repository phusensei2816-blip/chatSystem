package com.example.chatsystem

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID

class AppVM(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val dao = Room.databaseBuilder(app, AppDb::class.java, "chat.db").fallbackToDestructiveMigration().build().dao()
    private val gson = Gson()
    private var api: Api? = null
    private var rt: Realtime? = null
    private var lastTyping = 0L

    var ready by mutableStateOf(false); private set
    var session by mutableStateOf<Session?>(null); private set
    var error by mutableStateOf<String?>(null)
    var convs by mutableStateOf<List<Conv>>(emptyList()); private set
    var users by mutableStateOf<List<UserDto>>(emptyList()); private set
    val online = mutableStateMapOf<Long, Boolean>()
    val typing = mutableStateMapOf<Long, String>()
    var connected by mutableStateOf(false); private set
    var disconnected by mutableStateOf(false); private set   // ngắt chủ động (đóng WebSocket)
    var frozen by mutableStateOf(false); private set          // ngừng heartbeat, giữ socket
    var banner by mutableStateOf<String?>(null)               // mô phỏng thông báo
    var hb by mutableStateOf(HbCfg(15, 45)); private set
    var stats by mutableStateOf<Stats?>(null); private set
    var events by mutableStateOf<List<EventDto>>(emptyList()); private set
    var openConv: Long? = null

    init {
        viewModelScope.launch { store.load()?.let { enter(it) }; ready = true }
    }

    private fun buildApi(s: Session): Api {
        val client = OkHttpClient.Builder().addInterceptor { c ->
            val b = c.request().newBuilder()
            if (s.token.isNotEmpty()) b.header("Authorization", "Token ${s.token}")
            c.proceed(b.build())
        }.build()
        return Retrofit.Builder().baseUrl(s.baseUrl).client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java)
    }

    fun login(url: String, user: String, pass: String, register: Boolean) {
        viewModelScope.launch {
            try {
                error = null
                val base = url.trim().let { if (it.endsWith("/")) it else "$it/" }
                val tmp = Session(base, "", 0, "", store.deviceId())
                val body = mapOf("username" to user.trim(), "password" to pass)
                val a = buildApi(tmp)
                val r = if (register) a.register(body) else a.login(body)
                val s = tmp.copy(token = r.token, userId = r.userId, username = r.username)
                store.save(s)
                enter(s)
            } catch (e: Exception) { error = e.message ?: "Lỗi kết nối" }
        }
    }

    private suspend fun enter(s: Session) {
        session = s; api = buildApi(s)
        startRealtime(s)
        refresh()
    }

    private fun startRealtime(s: Session) {
        rt?.stop()
        val wsUrl = s.baseUrl.replaceFirst("http", "ws") + "ws/chat/?token=${s.token}&device_id=${s.deviceId}"
        rt = Realtime(viewModelScope, { j -> viewModelScope.launch { handle(j) } },
            { up -> viewModelScope.launch { onState(up) } }).also { it.frozen = frozen; it.start(wsUrl) }
    }

    suspend fun refresh() {
        runCatching {
            convs = api!!.convs(); users = api!!.users()
            convs.forEach { c -> c.members.forEach { online[it.id] = it.online } }
        }
    }

    private suspend fun onState(up: Boolean) {
        connected = up
        if (!up) return
        // Đồng bộ tin còn thiếu: gửi last_synced_message_id, nhận các tin mới hơn
        val after = dao.maxServerId() ?: 0L
        runCatching { api!!.sync(after) }.onSuccess { list ->
            list.forEach { saveServer(it) }
            if (list.isNotEmpty()) banner = "Đã đồng bộ ${list.size} tin nhắn"
        }
        dao.pending().forEach { rt?.send(sendJson(it)) }   // gửi lại tin đang chờ (server chống trùng bằng client_id)
        refresh()
    }

    private suspend fun saveServer(m: MsgDto) {
        val mine = m.senderId == session?.userId
        dao.upsert(MsgEntity(m.clientId, m.id, m.conversationId, m.senderId, m.senderName, m.text, m.createdAt,
            if (mine) (m.status ?: "sent") else "recv"))
    }

    private fun sendJson(e: MsgEntity) = JSONObject().put("type", "send_message")
        .put("conversation_id", e.convId).put("text", e.text).put("client_id", e.clientId).toString()

    private suspend fun handle(j: JSONObject) {
        val me = session?.userId ?: return
        when (j.optString("type")) {
            "config" -> hb = HbCfg(j.getInt("interval"), j.getInt("timeout"))
            "message_sent" -> {
                val m = gson.fromJson(j.getJSONObject("message").toString(), MsgDto::class.java)
                dao.markSent(m.clientId, m.id)
                refresh()
            }
            "new_message" -> {
                val m = gson.fromJson(j.getJSONObject("message").toString(), MsgDto::class.java)
                if (m.senderId == me) {
                    if (dao.byClient(m.clientId) == null) saveServer(m)       // tin từ thiết bị khác của mình
                } else {
                    saveServer(m)
                    rt?.send(JSONObject().put("type", "ack").put("message_ids", JSONArray().put(m.id)).toString())
                    if (openConv != m.conversationId) banner = "${m.senderName}: ${m.text}"
                }
                refresh()
            }
            "receipt" -> dao.markDelivered(j.getLong("message_id"))
            "presence" -> online[j.getString("user_id").toLong()] = j.getBoolean("online")
            "typing" -> {
                val c = j.getLong("conversation_id"); val n = j.getString("username")
                typing[c] = n
                viewModelScope.launch { delay(3000); if (typing[c] == n) typing.remove(c) }
            }
            "conversation" -> refresh()
        }
    }

    fun messages(convId: Long) = dao.observe(convId)

    fun openChat(convId: Long) {
        openConv = convId
        viewModelScope.launch { runCatching { api!!.history(convId) }.onSuccess { l -> l.forEach { saveServer(it) } } }
    }

    fun send(convId: Long, text: String) {
        val s = session ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            val e = MsgEntity(UUID.randomUUID().toString(), null, convId, s.userId, s.username,
                text.trim(), System.currentTimeMillis(), "sending")
            dao.upsert(e)                       // lưu trước, offline vẫn thấy tin ở trạng thái "đang gửi"
            if (connected) rt?.send(sendJson(e))
        }
    }

    fun typing(convId: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTyping < 2000) return
        lastTyping = now
        rt?.send(JSONObject().put("type", "typing").put("conversation_id", convId).toString())
    }

    fun createDirect(userId: Long, done: (Long) -> Unit) = viewModelScope.launch {
        runCatching { api!!.createConv(mapOf("kind" to "direct", "user_id" to userId)) }
            .onSuccess { refresh(); done(it.id) }.onFailure { error = it.message }
    }

    fun createGroup(name: String, ids: List<Long>, done: (Long) -> Unit) = viewModelScope.launch {
        runCatching { api!!.createConv(mapOf("kind" to "group", "name" to name, "member_ids" to ids)) }
            .onSuccess { refresh(); done(it.id) }.onFailure { error = it.message }
    }

    fun setPreset(p: String) = viewModelScope.launch {
        runCatching { api!!.setHb(mapOf("preset" to p)) }.onFailure { banner = "Không đổi được heartbeat" }
    }

    fun setInterval(i: Int) = viewModelScope.launch {
        runCatching { api!!.setHb(mapOf("interval" to i, "timeout" to i * 3)) }.onFailure { banner = "Không đổi được heartbeat" }
    }

    fun changeDisconnected(v: Boolean) {
        disconnected = v
        val s = session ?: return
        if (v) rt?.stop() else startRealtime(s)
    }

    fun changeFrozen(v: Boolean) { frozen = v; rt?.frozen = v }

    suspend fun loadMonitor() {
        runCatching { stats = api!!.stats(); events = api!!.events(0).takeLast(15).reversed() }
    }

    fun logout() = viewModelScope.launch {
        rt?.stop(); store.clear(); dao.clear()
        session = null; convs = emptyList(); online.clear(); disconnected = false; frozen = false
    }
}
package com.example.chatsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF16A34A)
private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

// ---------------- 01. Login ----------------
@Composable
fun LoginScreen(vm: AppVM) {
    var url by remember { mutableStateOf("http://10.0.2.2:8000/") }   // 10.0.2.2 = localhost của máy tính
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Text("ChatSystem", style = MaterialTheme.typography.headlineMedium)
        Text("Real-time Messaging Demo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url, { url = it }, label = { Text("Địa chỉ server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Mật khẩu") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tài khoản demo:")
            listOf("Phu", "An", "Binh").forEach { n -> OutlinedButton(onClick = { user = n; pass = "123456" }) { Text(n) } }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.login(url, user, pass, false) }) { Text("Đăng nhập") }
            OutlinedButton(onClick = { vm.login(url, user, pass, true) }) { Text("Đăng ký") }
        }
    }
}

// ---------------- 02. Chat List ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(vm: AppVM, nav: NavController) {
    var menu by remember { mutableStateOf(false) }
    var newChat by remember { mutableStateOf(false) }
    var hbDialog by remember { mutableStateOf(false) }
    val me = vm.session?.userId ?: 0L
    val status = when {
        vm.disconnected -> "Đã ngắt kết nối (chủ động)"
        vm.frozen && vm.connected -> "Heartbeat đang tạm dừng"
        vm.connected -> "Online · heartbeat ${vm.hb.interval}s/${vm.hb.timeout}s"
        else -> "Đang kết nối lại…"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.session?.username ?: "")
                        Text(status, style = MaterialTheme.typography.labelSmall,
                            color = if (vm.connected && !vm.frozen) Green else MaterialTheme.colorScheme.error)
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Heartbeat…") }, { menu = false; hbDialog = true })
                            DropdownMenuItem({ Text("System Monitor") }, { menu = false; nav.navigate("monitor") })
                            DropdownMenuItem({ Text(if (vm.disconnected) "Kết nối lại" else "Ngắt kết nối") },
                                { menu = false; vm.changeDisconnected(!vm.disconnected) })
                            DropdownMenuItem({ Text(if (vm.frozen) "Tiếp tục heartbeat" else "Treo heartbeat (giả lập mất mạng)") },
                                { menu = false; vm.changeFrozen(!vm.frozen) })
                            DropdownMenuItem({ Text("Đăng xuất") }, { menu = false; vm.logout() })
                        }
                    }
                })
        },
        floatingActionButton = { FloatingActionButton(onClick = { newChat = true }) { Text("+") } },
    ) { pad ->
        if (vm.convs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Chưa có cuộc trò chuyện. Bấm + để bắt đầu.")
            }
        }
        LazyColumn(Modifier.padding(pad)) {
            items(vm.convs, key = { it.id }) { c ->
                val others = c.members.filter { it.id != me }
                val isOn = others.any { vm.online[it.id] == true }
                val last = c.lastMessage
                Row(Modifier.fillMaxWidth().clickable { nav.navigate("chat/${c.id}") }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center) { Text(c.name.take(1).uppercase(), fontWeight = FontWeight.Bold) }
                        Box(Modifier.size(12.dp).align(Alignment.BottomEnd).clip(CircleShape)
                            .background(if (isOn) Green else Color.Gray))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text((if (c.kind == "group") "👥 " else "") + c.name, fontWeight = FontWeight.SemiBold)
                        Text(last?.let { "${it.senderName}: ${it.text}" } ?: "Chưa có tin nhắn", maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(last?.let { hhmm(it.createdAt) } ?: "", style = MaterialTheme.typography.labelSmall)
                        Text(if (c.kind == "group") "${others.count { vm.online[it.id] == true }}/${others.size} online"
                        else if (isOn) "online" else "offline", style = MaterialTheme.typography.labelSmall,
                            color = if (isOn) Green else Color.Gray)
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (newChat) NewChatDialog(vm, nav) { newChat = false }
    if (hbDialog) HeartbeatDialog(vm) { hbDialog = false }
}

@Composable
fun NewChatDialog(vm: AppVM, nav: NavController, close: () -> Unit) {
    var group by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Long>() }
    AlertDialog(onDismissRequest = close,
        title = { Text(if (group) "Tạo nhóm" else "Chat 1-1") },
        text = {
            Column {
                Row { TextButton({ group = false }) { Text("1-1") }; TextButton({ group = true }) { Text("Nhóm") } }
                if (group) OutlinedTextField(name, { name = it }, label = { Text("Tên nhóm") }, singleLine = true)
                vm.users.forEach { u ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (!group) { close(); vm.createDirect(u.id) { id -> nav.navigate("chat/$id") } }
                        else if (u.id in picked) picked.remove(u.id) else picked.add(u.id)
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (group) Checkbox(u.id in picked, null)
                        Text(u.username + if (vm.online[u.id] == true) "  ● online" else "  ○ offline")
                    }
                }
            }
        },
        confirmButton = {
            if (group) Button(enabled = picked.isNotEmpty(), onClick = {
                close(); vm.createGroup(name, picked.toList()) { id -> nav.navigate("chat/$id") }
            }) { Text("Tạo nhóm") }
        },
        dismissButton = { TextButton(close) { Text("Đóng") } })
}

@Composable
fun HeartbeatDialog(vm: AppVM, close: () -> Unit) {
    var interval by remember { mutableStateOf(vm.hb.interval.toFloat()) }
    AlertDialog(onDismissRequest = close, title = { Text("Heartbeat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hiện tại: ${vm.hb.interval}s / timeout ${vm.hb.timeout}s (áp dụng cho mọi thiết bị)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ vm.setPreset("demo") }) { Text("5s") }
                    OutlinedButton({ vm.setPreset("balanced") }) { Text("15s") }
                    OutlinedButton({ vm.setPreset("battery") }) { Text("30s") }
                }
                Text("Tuỳ chỉnh: ${interval.toInt()}s (timeout = 3 × interval)")
                Slider(interval, { interval = it }, valueRange = 1f..60f)
                Button({ vm.setInterval(interval.toInt()) }) { Text("Áp dụng") }
            }
        },
        confirmButton = { TextButton(close) { Text("Đóng") } })
}

// ---------------- 03/04. Chat Detail + Group Chat ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppVM, convId: Long, nav: NavController) {
    val me = vm.session?.userId ?: 0L
    val conv = vm.convs.find { it.id == convId }
    val msgs by vm.messages(convId).collectAsState(initial = emptyList())
    val list = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(convId) { vm.openChat(convId) }
    DisposableEffect(convId) { onDispose { vm.openConv = null } }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex) }
    val others = conv?.members?.filter { it.id != me } ?: emptyList()
    val sub = vm.typing[convId]?.let { "$it đang nhập…" } ?: when {
        conv == null -> ""
        conv.kind == "direct" -> if (others.any { vm.online[it.id] == true }) "online" else "offline"
        else -> others.joinToString(" · ") { it.username + if (vm.online[it.id] == true) " ●" else " ○" }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton({ nav.popBackStack() }) { Text("←") } },
                title = { Column { Text(conv?.name ?: ""); Text(sub, style = MaterialTheme.typography.labelSmall) } })
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it; vm.typing(convId) }, Modifier.weight(1f),
                    placeholder = { Text("Nhập tin nhắn…") }, maxLines = 3)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.send(convId, text); text = "" }, enabled = text.isNotBlank()) { Text("Gửi") }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = list) {
            items(msgs, key = { it.clientId }) { m -> Bubble(m, m.senderId == me, conv?.kind == "group") }
        }
    }
}

@Composable
fun Bubble(m: MsgEntity, mine: Boolean, group: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(12.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(10.dp).widthIn(max = 280.dp)) {
                if (group && !mine) Text(m.senderName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(m.text)
                val tick = if (!mine) "" else when (m.status) { "sending" -> "  …"; "sent" -> "  ✓"; else -> "  ✓✓" }
                Text(hhmm(m.createdAt) + tick, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------- 05. System Monitor ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(vm: AppVM, nav: NavController) {
    LaunchedEffect(Unit) { while (true) { vm.loadMonitor(); delay(2000) } }
    val s = vm.stats
    Scaffold(topBar = {
        TopAppBar(title = { Text("System Monitor") }, navigationIcon = { TextButton({ nav.popBackStack() }) { Text("←") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                if (s == null) Text("Đang tải…") else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Online users: ${s.onlineUsers.size} (${s.onlineUsers.joinToString()})")
                    Text("WebSocket connections: ${s.wsConnections}")
                    Text("Tổng tin nhắn: ${s.totalMessages} · Chờ đồng bộ: ${s.pendingSync}")
                    Text("Redis: ${if (s.redis) "Connected" else "Down"} · PostgreSQL: ${if (s.postgres) "Connected" else "Down"}")
                    Text("Heartbeat: ${s.heartbeat.interval}s / ${s.heartbeat.timeout}s")
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Event Log", fontWeight = FontWeight.Bold)
                }
            }
            items(vm.events, key = { it.id }) { e ->
                Column {
                    Text("${hhmm((e.ts * 1000).toLong())} – ${e.step}", fontWeight = FontWeight.SemiBold)
                    Text(e.detail, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
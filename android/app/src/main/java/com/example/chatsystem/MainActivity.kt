package com.example.chatsystem

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App(vm: AppVM = viewModel()) {
    val nav = rememberNavController()

    // Tự ẩn banner sau 3 giây
    LaunchedEffect(vm.banner) {
        if (vm.banner != null) {
            delay(3000)
            vm.banner = null
        }
    }

    // Chưa load xong session thì hiện loading, chưa dựng NavHost
    if (!vm.ready) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // startDestination chỉ đọc 1 lần khi graph được tạo
    val startDest = remember { if (vm.session != null) "list" else "login" }

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = startDest) {
            composable("login") { LoginScreen(vm) }
            composable("list") { ChatListScreen(vm, nav) }
            composable(
                "chat/{id}",
                listOf(navArgument("id") { type = NavType.LongType })
            ) {
                ChatScreen(vm, it.arguments!!.getLong("id"), nav)
            }
            composable("monitor") { MonitorScreen(vm, nav) }
        }

        // Đặt SAU NavHost để graph đã được set khi navigate
        LaunchedEffect(vm.session) {
            val current = nav.currentDestination?.route
            if (vm.session != null) {
                // Vừa đăng nhập xong -> sang danh sách chat
                if (current == "login") {
                    nav.navigate("list") { popUpTo("login") { inclusive = true } }
                }
            } else {
                // Đăng xuất / hết phiên -> về login, xóa back stack
                if (current != "login") {
                    nav.navigate("login") { popUpTo(0) }
                }
            }
        }

        vm.banner?.let {
            Surface(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .statusBarsPadding(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 6.dp
            ) {
                Text(
                    it,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}
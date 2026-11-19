package com.dmcc26.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dmcc26.app.BuildConfig
import com.dmcc26.app.R
import com.dmcc26.app.data.APP_FULL_NAME
import com.dmcc26.app.data.APP_SHORT_NAME
import com.dmcc26.app.data.DEVELOPER_NOTE
import com.dmcc26.app.data.Repo
import com.dmcc26.app.data.UserProfile
import com.dmcc26.app.data.Watcher
import com.dmcc26.app.ui.screens.AddMemberScreen
import com.dmcc26.app.ui.screens.AuthScreen
import com.dmcc26.app.ui.screens.ChatScreen
import com.dmcc26.app.ui.screens.GroupScreen
import com.dmcc26.app.ui.screens.HomeScreen
import com.dmcc26.app.ui.screens.JoinScreen
import com.dmcc26.app.ui.screens.MemberScreen
import com.dmcc26.app.ui.screens.NewGroupScreen
import com.dmcc26.app.ui.screens.NewNoticeScreen
import com.dmcc26.app.ui.screens.PccScreen
import com.dmcc26.app.ui.screens.ProfileScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun AppRoot(pendingRoute: MutableStateFlow<String?>) {
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1800); splash = false }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            splash -> Splash()
            !Repo.ready -> SetupMissing()
            else -> Gate(pendingRoute)
        }
    }
}

@Composable
fun Splash() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(180.dp))
        Spacer(Modifier.height(24.dp))
        Text(APP_FULL_NAME, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        Text("($APP_SHORT_NAME)", fontWeight = FontWeight.SemiBold, color = Saffron, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.weight(1f))
        Text(DEVELOPER_NOTE, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SetupMissing() {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Text("Server not connected", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("This build of $APP_SHORT_NAME has no Firebase configuration. Add app/google-services.json (package com.dmcc26.app) to the GitHub repo and build again. See SETUP.md in the repo.")
        Spacer(Modifier.height(24.dp))
        Text(DEVELOPER_NOTE, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Gate(pendingRoute: MutableStateFlow<String?>) {
    val user by remember { Repo.authFlow() }.collectAsState(initial = Repo.auth.currentUser)
    val u = user
    if (u == null) { AuthScreen(); return }
    var state by remember(u.uid) { mutableStateOf<Pair<Boolean, UserProfile?>>(false to null) }
    LaunchedEffect(u.uid) { Repo.userFlow(u.uid).collect { state = true to it } }
    if (!state.first) { Loading(); return }
    val p = state.second
    if (p == null || p.name.isBlank()) {
        ProfileScreen(onDone = {}, firstTime = true)
        return
    }
    val ctx = LocalContext.current
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(u.uid) {
        Watcher.start(ctx)
        runCatching { Repo.saveFcmToken() }
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    MainNav(pendingRoute)
}

@Composable
private fun MainNav(pendingRoute: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val route by pendingRoute.collectAsState()
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        pendingRoute.value = null
        runCatching { if (r == "notices") nav.navigate("home?tab=2") else nav.navigate(r) }
    }
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = "home?tab={tab}") {
        composable("home?tab={tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) {
            HomeScreen(nav, it.arguments?.getInt("tab") ?: 0)
        }
        composable("newgroup") { NewGroupScreen(nav) }
        composable("join?code={code}", arguments = listOf(navArgument("code") { defaultValue = "" })) {
            JoinScreen(nav, it.arguments?.getString("code") ?: "")
        }
        composable("group/{gid}") { GroupScreen(nav, it.arguments?.getString("gid") ?: "") }
        composable("pcc/{gid}/{pid}") { PccScreen(nav, it.arguments?.getString("gid") ?: "", it.arguments?.getString("pid") ?: "") }
        composable("member/{gid}/{uid}") { MemberScreen(nav, it.arguments?.getString("gid") ?: "", it.arguments?.getString("uid") ?: "") }
        composable("add/{gid}?pcc={pcc}", arguments = listOf(navArgument("pcc") { defaultValue = "" })) {
            AddMemberScreen(nav, it.arguments?.getString("gid") ?: "", it.arguments?.getString("pcc") ?: "")
        }
        composable("chat/{kind}/{id}") { ChatScreen(nav, it.arguments?.getString("kind") ?: "g", it.arguments?.getString("id") ?: "") }
        composable("newnotice?gid={gid}", arguments = listOf(navArgument("gid") { defaultValue = "" })) {
            NewNoticeScreen(nav, it.arguments?.getString("gid") ?: "")
        }
        composable("profile") { ProfileScreen(onDone = back, firstTime = false) }
    }
}

package com.dmcc26.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmcc26.app.R
import com.dmcc26.app.data.APP_FULL_NAME
import com.dmcc26.app.data.DEVELOPER_NOTE
import com.dmcc26.app.data.Media
import com.dmcc26.app.data.Repo
import com.dmcc26.app.data.normalizePhone
import com.dmcc26.app.ui.Avatar
import com.dmcc26.app.ui.Bar
import com.dmcc26.app.ui.findActivity
import com.dmcc26.app.ui.toast
import com.google.firebase.FirebaseException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(110.dp))
        Text(APP_FULL_NAME, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        Text("Register or log in", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Phone number") }, icon = { Icon(Icons.Default.Phone, null) })
            Tab(tab == 1, { tab = 1 }, text = { Text("Email") }, icon = { Icon(Icons.Default.Email, null) })
        }
        Spacer(Modifier.height(16.dp))
        if (tab == 0) PhoneLogin() else EmailLogin()
        Spacer(Modifier.height(32.dp))
        Text(DEVELOPER_NOTE, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PhoneLogin() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var verificationId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun signIn(cred: PhoneAuthCredential) {
        busy = true
        scope.launch {
            runCatching { Repo.auth.signInWithCredential(cred).await() }
                .onFailure { ctx.toast("Login failed: ${it.message}") }
            busy = false
        }
    }

    fun send() {
        val p = normalizePhone(phone)
        if (p.isBlank()) { ctx.toast("Enter a valid 10-digit mobile number"); return }
        val act = ctx.findActivity() ?: return
        busy = true
        val opts = PhoneAuthOptions.newBuilder(Repo.auth)
            .setPhoneNumber(p)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(act)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(c: PhoneAuthCredential) = signIn(c)
                override fun onVerificationFailed(e: FirebaseException) { busy = false; ctx.toast("Couldn't send OTP: ${e.message}") }
                override fun onCodeSent(id: String, t: PhoneAuthProvider.ForceResendingToken) {
                    busy = false; verificationId = id; ctx.toast("OTP sent to $p")
                }
            })
            .build()
        PhoneAuthProvider.verifyPhoneNumber(opts)
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(phone, { phone = it }, label = { Text("Mobile number") }, prefix = { Text("+91 ") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        if (verificationId != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(code, { code = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("6-digit OTP") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        else if (verificationId == null) Button(::send, Modifier.fillMaxWidth()) { Text("Send OTP") }
        else {
            Button({ if (code.length == 6) signIn(PhoneAuthProvider.getCredential(verificationId!!, code)) }, Modifier.fillMaxWidth()) { Text("Verify & continue") }
            TextButton(::send, Modifier.align(Alignment.CenterHorizontally)) { Text("Resend OTP") }
        }
    }
}

@Composable
private fun EmailLogin() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Password (min 6 characters)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        else Button({
            if (!email.contains("@") || pass.length < 6) { ctx.toast("Enter email and a password of at least 6 characters"); return@Button }
            busy = true
            scope.launch {
                runCatching {
                    if (register) Repo.auth.createUserWithEmailAndPassword(email, pass).await()
                    else Repo.auth.signInWithEmailAndPassword(email, pass).await()
                }.onFailure { ctx.toast(it.message ?: "Failed") }
                busy = false
            }
        }, Modifier.fillMaxWidth()) { Text(if (register) "Create account" else "Log in") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ register = !register }) { Text(if (register) "Have an account? Log in" else "New? Register") }
            if (!register) TextButton({
                if (!email.contains("@")) { ctx.toast("Enter your email first"); return@TextButton }
                scope.launch {
                    runCatching { Repo.auth.sendPasswordResetEmail(email).await() }
                        .onSuccess { ctx.toast("Password reset link sent to $email") }
                        .onFailure { ctx.toast(it.message ?: "Failed") }
                }
            }) { Text("Forgot password") }
        }
    }
}

/** First-time profile setup, and later "My profile". */
@Composable
fun ProfileScreen(onDone: () -> Unit, firstTime: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val authUser = Repo.auth.currentUser
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { Repo.getUser(Repo.uid) }.getOrNull()?.let { name = it.name; phone = it.phone; photo = it.photo }
        if (phone.isBlank()) phone = authUser?.phoneNumber ?: ""
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val d = Media.read(ctx, uri, maxSide = 400)
                photo = Repo.putBlob(d, scope = "public")
            }.onFailure { ctx.toast(it.message ?: "Couldn't use that photo") }
        }
    }
    val phoneLocked = !authUser?.phoneNumber.isNullOrBlank()
    Scaffold(topBar = { Bar(if (firstTime) "Your profile" else "My profile", onBack = if (firstTime) null else onDone) }) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.clickable { pick.launch("image/*") }, horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(name.ifBlank { "?" }, photo, 96.dp)
                Text("Change photo", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(phone, { if (!phoneLocked) phone = it }, label = { Text("Mobile number") }, singleLine = true,
                readOnly = phoneLocked, supportingText = { Text(if (phoneLocked) "Verified by OTP" else "So administrators can add you from their contacts") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            if (!authUser?.email.isNullOrBlank()) {
                OutlinedTextField(authUser?.email ?: "", {}, label = { Text("Email") }, readOnly = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            if (busy) CircularProgressIndicator()
            else Button({
                if (name.isBlank()) { ctx.toast("Enter your name"); return@Button }
                busy = true
                scope.launch {
                    runCatching { Repo.saveProfile(name, phone, photo) }
                        .onSuccess { if (!firstTime) { ctx.toast("Saved"); onDone() } }
                        .onFailure { ctx.toast(it.message ?: "Couldn't save") }
                    busy = false
                }
            }, Modifier.fillMaxWidth()) { Text(if (firstTime) "Continue" else "Save") }
            if (firstTime) TextButton({ Repo.signOut() }) { Text("Use a different account") }
        }
    }
}

package ru.begunok.ui.common

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.begunok.system.Permissions
import ru.begunok.system.SystemChecks

@Composable
fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) current()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun InfoCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
fun PermissionGate(
    permissions: Array<String>,
    title: String,
    rationale: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Permissions.allGranted(context, permissions)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Permissions.allGranted(context, permissions)
        askedOnce = true
    }
    OnResume { granted = Permissions.allGranted(context, permissions) }

    if (granted) {
        content()
        return
    }
    val activity = context.findActivity()
    val permanentlyDenied = askedOnce && activity != null && permissions
        .filterNot { Permissions.granted(context, it) }
        .none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    InfoCard(
        title = title,
        text = if (permanentlyDenied) "$rationale\n\nРазрешение отклонено. Включите его вручную в настройках приложения." else rationale,
        modifier = modifier,
    ) {
        if (permanentlyDenied) {
            Button(onClick = { SystemChecks.openAppSettings(context) }) { Text("Открыть настройки") }
        } else {
            Button(onClick = { launcher.launch(permissions) }) { Text("Разрешить") }
        }
    }
}

@Composable
fun BluetoothGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SystemChecks.isBluetoothEnabled(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = SystemChecks.isBluetoothEnabled(context)
    }
    OnResume { enabled = SystemChecks.isBluetoothEnabled(context) }
    if (enabled) {
        content()
        return
    }
    InfoCard(
        title = "Bluetooth выключен",
        text = "Пульсометр подключается по Bluetooth. Включите его, чтобы получать пульс.",
        modifier = modifier,
    ) {
        Button(onClick = {
            runCatching { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        }) { Text("Включить") }
    }
}

@Composable
fun LocationServiceGate(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SystemChecks.isLocationEnabled(context)) }
    OnResume { enabled = SystemChecks.isLocationEnabled(context) }
    if (enabled) {
        content()
        return
    }
    InfoCard(title = "Геолокация выключена", text = text, modifier = modifier) {
        Button(onClick = { SystemChecks.openLocationSettings(context) }) { Text("Включить") }
    }
}

@Composable
fun BatteryOptimizationCard(modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(SystemChecks.isIgnoringBatteryOptimizations(context)) }
    OnResume { ignoring = SystemChecks.isIgnoringBatteryOptimizations(context) }
    if (ignoring) return
    InfoCard(
        title = "Оптимизация батареи",
        text = "Некоторые прошивки (Xiaomi, Huawei, Samsung и др.) останавливают фоновые службы, " +
            "и тренировка может прерваться при выключенном экране. Отключите оптимизацию батареи для приложения. " +
            "На некоторых телефонах нужно ещё разрешить автозапуск: инструкции есть на dontkillmyapp.com.",
        modifier = modifier,
    ) {
        Button(onClick = { SystemChecks.openBatteryOptimizationSettings(context) }) { Text("Отключить") }
        TextButton(onClick = { SystemChecks.openUrl(context, "https://dontkillmyapp.com") }) { Text("Подробнее") }
        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Скрыть") }
    }
}

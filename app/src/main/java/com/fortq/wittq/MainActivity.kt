package com.fortq.wittq

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.content.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PriceInputScreen()
                }
            }
        }
    }
}



@Composable
fun PriceInputScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("StockPrefs", Context.MODE_PRIVATE) }
    var showBatteryExemptionPrompt by remember { mutableStateOf(false) }
    var showExactAlarmPrompt by remember { mutableStateOf(false) }
    var schedulerStatusVersion by remember { mutableStateOf(0) }

    fun requestExactAlarmIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms() &&
            !prefs.getBoolean("exact_alarm_prompted_v1", false)
        ) {
            showExactAlarmPrompt = true
        }
    }

    fun requestBatteryExemptionIfNeeded(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName) &&
            !prefs.getBoolean("battery_exemption_prompted", false)
        ) {
            showBatteryExemptionPrompt = true
            return true
        }
        return false
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (!requestBatteryExemptionIfNeeded()) requestExactAlarmIfNeeded()
    }

    LaunchedEffect(Unit) {
        val notificationPermissionNeeded = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_permission_prompted", false)
        if (notificationPermissionNeeded) {
            prefs.edit { putBoolean("notification_permission_prompted", true) }
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!requestBatteryExemptionIfNeeded()) {
            requestExactAlarmIfNeeded()
        }
        AutoRefreshScheduler.scheduleStock(context)
    }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                schedulerStatusVersion += 1
                AutoRefreshScheduler.scheduleStock(context)
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val schedulerDiagnostics = remember(schedulerStatusVersion) {
        AutoRefreshScheduler.diagnostics(context)
    }
    val batteryExempt = remember(schedulerStatusVersion) {
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }

    var avgPrice by remember { mutableStateOf(prefs.getFloat("user_avg_price", 50.0f).toString()) }
    var selectedPos by remember { mutableStateOf(prefs.getString("user_position", "TQQQ") ?: "TQQQ") }
    var overheatSmaLen by remember {
        mutableStateOf(
            prefs.getInt("tqqq_overheat_sma_len", TqqqAlgorithm.DEFAULT_OVERHEAT_SMA_LEN)
                .coerceIn(100, 300)
                .toString()
        )
    }
    var cooldownDays by remember {
        mutableStateOf(
            prefs.getInt("tqqq_cooldown_days", TqqqAlgorithm.DEFAULT_COOLDOWN_DAYS)
                .coerceIn(0, 30)
                .toString()
        )
    }

    val posOptions = listOf("TQQQ", "CASH")
    val isCashSelected = selectedPos == "CASH"
    var isUpdating by remember { mutableStateOf(false) }

    if (showBatteryExemptionPrompt) {
        AlertDialog(
            onDismissRequest = {
                prefs.edit { putBoolean("battery_exemption_prompted", true) }
                showBatteryExemptionPrompt = false
            },
            title = { Text("자동 갱신 보호") },
            text = { Text("장중 15분 자동 갱신과 포지션 알림이 절전 모드에 밀리지 않도록 witTQ를 배터리 최적화에서 제외합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean("battery_exemption_prompted", true) }
                    showBatteryExemptionPrompt = false
                    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    runCatching { context.startActivity(requestIntent) }
                        .getOrElse {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                }) { Text("허용 요청") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean("battery_exemption_prompted", true) }
                    showBatteryExemptionPrompt = false
                    requestExactAlarmIfNeeded()
                }) { Text("나중에") }
            }
        )
    }

    if (showExactAlarmPrompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AlertDialog(
            onDismissRequest = {
                prefs.edit { putBoolean("exact_alarm_prompted_v1", true) }
                showExactAlarmPrompt = false
            },
            title = { Text("정확한 장중 갱신") },
            text = {
                Text(
                    "Android가 WorkManager를 수 시간 늦게 실행하는 경우를 피하기 위해 " +
                        "시장 고정 슬롯은 정확한 알람으로 깨우고, 실제 데이터 갱신은 WorkManager가 수행합니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean("exact_alarm_prompted_v1", true) }
                    showExactAlarmPrompt = false
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    runCatching { context.startActivity(intent) }
                        .getOrElse {
                            Toast.makeText(
                                context,
                                "설정에서 '알람 및 리마인더' 권한을 허용해주세요.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                }) { Text("허용 설정") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean("exact_alarm_prompted_v1", true) }
                    showExactAlarmPrompt = false
                }) { Text("WorkManager 유지") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("전략 설정", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "자동갱신 ${schedulerDiagnostics.schedulerMode.ifBlank { "-" }} · " +
                "Exact ${if (schedulerDiagnostics.exactAlarmAllowed) "ON" else "OFF"} · " +
                "배터리예외 ${if (batteryExempt) "ON" else "OFF"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!schedulerDiagnostics.exactAlarmAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    runCatching { context.startActivity(intent) }
                },
            ) {
                Text("정확 알람 권한 열기")
            }
        }
        Text("보유 포지션", modifier = Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            posOptions.forEach { pos ->
                FilterChip(
                    selected = selectedPos == pos,
                    onClick = { selectedPos = pos },
                    label = { Text(pos) }
                )
            }
        }

        Text(
            text = "공통 평단 입력",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "TQQQ는 신호 기준으로 동작하고, 이 값은 AGTQ/Snow의 수익률 표시용입니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        // 숫자 입력창
        OutlinedTextField(
            value = if (isCashSelected) "0" else avgPrice,
            onValueChange = { if (!isCashSelected) avgPrice = it },
            label = { Text("평단가 ($)") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = !isCashSelected, // ✅ CASH 선택 시 입력창 비활성화
            colors = if (isCashSelected) OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            ) else OutlinedTextFieldDefaults.colors()
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = overheatSmaLen,
            onValueChange = { value -> overheatSmaLen = value.filter { it.isDigit() }.take(3) },
            label = { Text("TQQQ 익절/과열 SMA 기간") },
            supportingText = { Text("기본값 210, 허용 범위 100-300") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = cooldownDays,
            onValueChange = { value -> cooldownDays = value.filter { it.isDigit() }.take(2) },
            label = { Text("TQQQ 쿨다운 기간(일)") },
            supportingText = { Text("기본값 10, 허용 범위 0-30 / 실제 날짜 기준") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isUpdating) return@Button

                val rawPrice = avgPrice.toFloatOrNull() ?: 0f
                val inputPrice = (rawPrice * 10).toInt() / 10.0f
                avgPrice = inputPrice.toString()
                val inputOverheatSmaLen = (overheatSmaLen.toIntOrNull() ?: TqqqAlgorithm.DEFAULT_OVERHEAT_SMA_LEN)
                    .coerceIn(100, 300)
                overheatSmaLen = inputOverheatSmaLen.toString()
                val inputCooldownDays = (cooldownDays.toIntOrNull() ?: TqqqAlgorithm.DEFAULT_COOLDOWN_DAYS)
                    .coerceIn(0, 30)
                cooldownDays = inputCooldownDays.toString()
                isUpdating = true

                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        Toast.makeText(context, "위젯 업데이트 중...", Toast.LENGTH_SHORT).show()
                        withContext((Dispatchers.IO)) {
                            prefs.edit(commit = true) {
                                putFloat("user_avg_price", inputPrice)
                                putString("user_position", selectedPos)
                                putInt("tqqq_overheat_sma_len", inputOverheatSmaLen)
                                putInt("tqqq_cooldown_days", inputCooldownDays)

                                if (isCashSelected) {
                                    putFloat("last_entry_price", 0f)
                                    putFloat("last_signal_entry_price", 0f)
                                    putBoolean("had_force_exit", false)
                                    putLong("last_force_exit_time", 0L)
                                    putBoolean("vix_lock", false)
                                    putInt("vix_calm_days", 0)
                                    putBoolean("c3_release_active", false)
                                    putInt("qqq_bull_streak", 0)
                                    putInt("last_ratio", 0)
                                    putString("last_signal_desc", "-")
                                }
                                val saved = prefs.getFloat("user_avg_price", 0f)
                                Log.d("WITTQ_DEBUG", "Saved: avgPrice=$saved")
                            }
                        Log.d("WITTQ_DEBUG", "Saved: position=$selectedPos, price=$inputPrice")
                        Log.d("WITTQ_DEBUG", "Saved: overheatSmaLen=$inputOverheatSmaLen")
                        Log.d("WITTQ_DEBUG", "Saved: cooldownDays=$inputCooldownDays")
                        }
                    // 3. 위젯 업데이트 명령을 가장 우선순위 높게 호출
                        delay(100)

                        withContext(Dispatchers.IO) {
                            StockWidget().updateAll(context)
                            AGTQWidget().updateAll(context)
                            FGIWidget().updateAll(context)
                            Log.d("WITTQ_DEBUG", "Widget update requested")
                        }

                        delay(200)
                        Toast.makeText(context, "위젯 업데이트 완료", Toast.LENGTH_SHORT).show()

                    } catch (e: Exception) {
                        Log.e("WITTQ_DEBUG", "Update failed: ${e.message}", e)
                        Toast.makeText(context, "업데이트 실패. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                    } finally {
                        isUpdating = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
            shape = MaterialTheme.shapes.medium,
            enabled = !isUpdating
        ) {
            if (isUpdating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (isUpdating) "업데이트 중..." else "위젯에 반영하기",
                fontSize = 18.sp
            )
        }
        if (!isCashSelected) {
            Text(
                text = "현재 설정: $selectedPos @ $$avgPrice / 과열 SMA $overheatSmaLen / 쿨다운 ${cooldownDays}일",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

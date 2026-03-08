package com.fortq.wittq

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.content.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

    var avgPrice by remember { mutableStateOf(prefs.getFloat("user_avg_price", 50.0f).toString()) }
    var selectedPos by remember { mutableStateOf(prefs.getString("user_position", "TQQQ") ?: "TQQQ") }

    val lastEntryPrice = prefs.getFloat("last_entry_price", 0f)
    val hadForceExit = prefs.getBoolean("had_force_exit", false)

    val posOptions = listOf("TQQQ", "CASH")
    val isCashSelected = selectedPos == "CASH"
    var isUpdating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("전략 설정", fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
            text = "TQQQ 위젯 설정",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "수익률 계산을 위한 평단가를 입력해주세요.",
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

        Button(
            onClick = {
                if (isUpdating) return@Button

                val rawPrice = avgPrice.toFloatOrNull() ?: 0f
                val inputPrice = (rawPrice * 10).toInt() / 10.0f
                avgPrice = inputPrice.toString()
                isUpdating = true

                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        Toast.makeText(context, "위젯 업데이트 중...", Toast.LENGTH_SHORT).show()
                        withContext((Dispatchers.IO)) {
                            prefs.edit(commit = true) {
                                putFloat("user_avg_price", inputPrice)
                                putString("user_position", selectedPos)

                                if (isCashSelected) {
                                    putFloat("last_entry_price", 0f)
                                    putBoolean("had_force_exit", false)
                                }
                                val saved = prefs.getFloat("user_avg_price", 0f)
                                Log.d("WITTQ_DEBUG", "Saved: avgPrice=$saved")
                            }
                        Log.d("WITTQ_DEBUG", "Saved: position=$selectedPos, price=$inputPrice")
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
                text = "현재 설정: $selectedPos @ $$avgPrice",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
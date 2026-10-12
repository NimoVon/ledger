package com.app.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.ledger.ui.theme.LedgerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LedgerApplication).container
        setContent {
            LedgerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    M0SelfCheckScreen(container)
                }
            }
        }
    }
}

/** M0 里程碑界面：验证整条链路（Room/KSP/触发器/预置数据）在真机真实生效 */
@Composable
private fun M0SelfCheckScreen(container: AppContainer) {
    var lines by remember { mutableStateOf(listOf("自检运行中…")) }
    LaunchedEffect(Unit) {
        lines = try {
            container.selfCheck()
        } catch (t: Throwable) {
            listOf(
                "自检失败 ✗",
                t.javaClass.simpleName + ": " + (t.message ?: "未知错误"),
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "记账本 · M0 自检", style = MaterialTheme.typography.headlineSmall)
        lines.forEach { line ->
            Text(text = line, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

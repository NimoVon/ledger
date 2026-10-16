package com.nimo.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimo.ledger.ui.LedgerApp
import com.nimo.ledger.ui.theme.LedgerTheme
import com.nimo.ledger.ui.theme.ThemePreference

/** 只负责承载根界面；页面与导航都在 ui/ 下 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LedgerApplication).container
        setContent {
            // F12：外观偏好由设置页写进 ThemePreferenceStore，这里一直观察它 ——
            // 设置页点一下，整个界面当场换主题，不需要重启，也不需要把回调穿过导航层级。
            val preference by container.themePreference.preference
                .collectAsStateWithLifecycle(initialValue = ThemePreference.SYSTEM)
            LedgerTheme(preference = preference) {
                LedgerApp(container)
            }
        }
    }
}

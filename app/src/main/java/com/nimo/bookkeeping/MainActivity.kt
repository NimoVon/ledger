package com.nimo.bookkeeping

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nimo.bookkeeping.ui.LedgerApp
import com.nimo.bookkeeping.ui.theme.LedgerTheme

/** 只负责承载根界面；页面与导航都在 ui/ 下 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LedgerApplication).container
        setContent {
            LedgerTheme {
                LedgerApp(container)
            }
        }
    }
}

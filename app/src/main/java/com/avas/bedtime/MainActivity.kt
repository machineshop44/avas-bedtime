package com.avas.bedtime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.avas.bedtime.ui.BedtimeApp
import com.avas.bedtime.ui.theme.AvaBedtimeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AvaBedtimeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BedtimeApp()
                }
            }
        }
    }
}

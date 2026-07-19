package com.bf1.admin.tool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.bf1.admin.tool.ui.navigation.AppNavigation
import com.bf1.admin.tool.ui.theme.BF1AdminTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BF1AdminTheme {
                val navController = rememberNavController()
                AppNavigation(navController)
            }
        }
    }
}

package com.jakecampbell.hauly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakecampbell.hauly.presentation.MainViewModel
import com.jakecampbell.hauly.presentation.common.LoadingState
import com.jakecampbell.hauly.presentation.navigation.HaulyNavHost
import com.jakecampbell.hauly.presentation.theme.HaulyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HaulyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()
                    val isNetworkBusy by viewModel.isNetworkBusy.collectAsStateWithLifecycle()
                    when (isConfigured) {
                        null -> LoadingState()
                        else -> HaulyNavHost(
                            startConfigured = isConfigured == true,
                            networkBusy = isNetworkBusy,
                        )
                    }
                }
            }
        }
    }
}

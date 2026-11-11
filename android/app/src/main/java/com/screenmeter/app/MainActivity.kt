package com.screenmeter.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.screenmeter.app.ui.App

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState
    private var hasPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) state.probeCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(this, lifecycleScope)
        hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) state.probeCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
        setContent {
            App(state = state, hasPermission = hasPermission, requestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
    }

    override fun onDestroy() {
        state.release()
        super.onDestroy()
    }
}

package com.pro2on.geospacial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.pro2on.geospacial.ui.theme.GeoSpacialTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GeoSpacialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    ) {

                        val gpsPermissionState = rememberPermissionState(
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        )

                        val cameraPermissionState = rememberPermissionState(
                            android.Manifest.permission.CAMERA
                        )

                        if (gpsPermissionState.status.isGranted && cameraPermissionState.status.isGranted) {
                            ArNavigationScreen()
                        } else if (!gpsPermissionState.status.isGranted) {
                            Button(onClick = { gpsPermissionState.launchPermissionRequest() }) {
                                Text("Request permission got gps")
                            }
                        } else if (!cameraPermissionState.status.isGranted) {
                            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                                Text("Request permission for camera")
                            }
                        }

                    }
                }
            }
        }
    }
}

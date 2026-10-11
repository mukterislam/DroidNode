package com.example.droidnode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.droidnode.storage.StoragePermissionHelper
import com.example.droidnode.ui.NodeStudioScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check storage permission for /sdcard/www
        if (!StoragePermissionHelper.hasStoragePermission(this)) {
            StoragePermissionHelper.requestStoragePermission(this)
        }

        setContent {
            MaterialTheme {
                Surface {
                    NodeStudioScreen()
                }
            }
        }
    }
}

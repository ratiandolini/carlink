package com.example.carlink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: CarViewModel get() = (application as CarLinkApplication).vm
    private var pending: (() -> Unit)? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pending?.invoke()
            pending = null
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Android 12+ -ზე Bluetooth-ის გამოყენებამდე უნდა მოვითხოვოთ ნებართვა. */
    private fun withBluetooth(action: () -> Unit) {
        val ok = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) {
            action()
        } else {
            pending = action
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /** Android 13+ -ზე შეტყობინებებისთვის ნებართვაა საჭირო. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ეკრანი არ ჩაქრეს, სანამ მანქანასთან ვმუშაობთ
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        askNotifications()
        if (vm.state.value.watching) withBluetooth { vm.setWatching(true) }
        setContent {
            CarLinkTheme {
                CarLinkApp(vm = vm, withBluetooth = ::withBluetooth)
            }
        }
    }
}

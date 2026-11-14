package com.example.careway

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.careway.ui.CareWayApp

class MainActivity : ComponentActivity() {
    private val model: CareViewModel by viewModels()
    private val vehiclePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        model.retryVoice()
        model.switchMode(false)
        if (results.values.any { !it }) model.message("허용되지 않은 권한의 기능은 제한됩니다. 연결 상태를 확인하세요.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { CareWayApp(model, onConnect = ::connectVehicle) }
        if (model.restoreLiveConnection) connectVehicle()
    }
    private fun connectVehicle() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) { model.switchMode(false); return }
        @Suppress("DEPRECATION")
        val declared = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        val missing = declared.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED &&
                runCatching { packageManager.getPermissionInfo(permission, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false)
        }
        if (missing.isNotEmpty()) vehiclePermissions.launch(missing.toTypedArray())
        else { model.retryVoice(); model.switchMode(false) }
    }

    override fun onResume() { super.onResume(); model.foreground(true) }
    override fun onPause() { model.foreground(false); super.onPause() }
}

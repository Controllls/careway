package com.example.careway

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.view.WindowManager
import com.example.careway.ui.CareScreen

class MainActivity : Activity() {
    lateinit var model: CareController; private set
    lateinit var screen: CareScreen; private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        model = lastNonConfigurationInstance as? CareController ?: CareController(application)
        screen = CareScreen(this, model, ::connectVehicle, ::exportReport)
        setContentView(screen)
        model.onChanged = screen::render
        screen.selectPage(savedInstanceState?.getInt("page") ?: 0)
        if (model.restoreLiveConnection) connectVehicle()
    }
    private fun connectVehicle() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) { model.switchMode(false); return }
        val declared = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        val missing = declared.filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED && runCatching {
                packageManager.getPermissionInfo(permission, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)
        else { model.retryVoice(); model.switchMode(false) }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10) {
            model.retryVoice(); model.switchMode(false)
            if (results.any { it != PackageManager.PERMISSION_GRANTED }) model.message("허용되지 않은 기능은 제한됩니다")
        }
    }
    private fun exportReport(csv: Boolean) {
        if (!model.state.value.canConfigure) return
        runCatching { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (csv) "text/csv" else "application/json")
            .putExtra(Intent.EXTRA_TITLE, if (csv) "Kkomjjakmacha-30days.csv" else "Kkomjjakmacha-records.json"), if (csv) 12 else 11) }
            .onFailure { model.message("파일을 저장할 앱이 없습니다") }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode in 11..12) data?.data?.let { model.saveReport(it, requestCode == 12) }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", screen.page); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); model.foreground(true) }
    override fun onPause() { model.foreground(false); screen.dismissDialogs(); super.onPause() }
    override fun onRetainNonConfigurationInstance(): Any = model
    override fun onDestroy() {
        model.onChanged = null
        if (!isChangingConfigurations) model.close()
        super.onDestroy()
    }
}

package com.example.autoshareposter

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.autoshareposter.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * メイン画面。
 * 起動時に初回起動説明ポップアップを表示しつつカメラを準備・起動します。
 * 撮影後は直接Xアプリへ写真を連携し、Xから戻ってきた際に撮影した元写真を自動削除します。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingCameraUri: Uri? = null
    private lateinit var handoffStore: HandoffStore

    // カメラ撮影ランチャー
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingCameraUri
            if (success && uri != null) {
                // 撮影成功：直接Xアプリへ引き渡す
                shareDirectlyToX(uri)
            } else {
                // 撮影キャンセルまたは失敗時：空のMediaStore行を削除
                if (uri != null) {
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {}
                }
                pendingCameraUri = null
                binding.textStatus.text = "撮影がキャンセルされました。"
            }
        }

    // 権限要求ランチャー
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            if (cameraGranted) {
                binding.textStatus.text = "カメラの権限が許可されました。"
                launchCamera()
            } else {
                binding.textStatus.text = "カメラの権限が必要です。「カメラを起動」ボタンから許可してください。"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handoffStore = HandoffStore(this)

        // 24時間以上経過した古い一時キャッシュファイルを削除
        CacheManager.purgeOldCache(this)

        // 画面再生成時の撮影中URI復元
        if (savedInstanceState != null) {
            val uriStr = savedInstanceState.getString(KEY_PENDING_CAMERA_URI)
            if (uriStr != null) {
                pendingCameraUri = Uri.parse(uriStr)
            }
        }

        binding.buttonCamera.setOnClickListener {
            checkPermissionsAndLaunchCamera()
        }

        // 初回起動時（savedInstanceState == null）の処理
        if (savedInstanceState == null) {
            checkFirstTimeAndStart()
        }
    }

    override fun onStart() {
        super.onStart()
        // Xアプリなど別アプリから戻ってきた場合の自動削除処理
        val state = handoffStore.load()
        if (state.handedOff && state.leftApp) {
            processReturn(state)
        }
    }

    override fun onStop() {
        super.onStop()
        // Xアプリが起動して本アプリが非表示になった場合、離脱フラグを永続化
        val state = handoffStore.load()
        if (state.handedOff) {
            handoffStore.markLeftApp()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCameraUri?.let {
            outState.putString(KEY_PENDING_CAMERA_URI, it.toString())
        }
    }

    /**
     * 初回起動説明ポップアップの判定とカメラ起動
     */
    private fun checkFirstTimeAndStart() {
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val doNotShowAgain = prefs.getBoolean(KEY_DO_NOT_SHOW_AGAIN, false)

        // カメラの起動準備・要求を開始
        checkPermissionsAndLaunchCamera()

        if (!doNotShowAgain) {
            showFirstTimeExplanationDialog()
        }
    }

    /**
     * アプリについての説明と注意点のポップアップ
     */
    private fun showFirstTimeExplanationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_first_time_notice, null)
        val checkbox = dialogView.findViewById<CheckBox>(R.id.checkboxDoNotShowAgain)

        MaterialAlertDialogBuilder(this)
            .setTitle("AutoSharePosterへようこそ")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                if (checkbox.isChecked) {
                    val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                    prefs.edit().putBoolean(KEY_DO_NOT_SHOW_AGAIN, true).apply()
                }
            }
            .show()
    }

    private fun checkPermissionsAndLaunchCamera() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            launchCamera()
        }
    }

    private fun launchCamera() {
        val uri = createNewImageUri()
        if (uri == null) {
            Toast.makeText(this, "画像ストレージの確保に失敗しました", Toast.LENGTH_SHORT).show()
            return
        }

        pendingCameraUri = uri

        try {
            takePictureLauncher.launch(uri)
        } catch (_: ActivityNotFoundException) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
            pendingCameraUri = null
            Toast.makeText(this, "カメラアプリが見つかりません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNewImageUri(): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "AutoShare_$timeStamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AutoSharePoster")
        }

        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    /**
     * 撮影した写真を直接X (Twitter) 公式アプリへ引き渡す
     */
    private fun shareDirectlyToX(imageUri: Uri) {
        if (!XShareHelper.isTwitterInstalled(this)) {
            Toast.makeText(
                this,
                "X (Twitter) アプリが見つかりません。公式アプリをインストールしてください。",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        lifecycleScope.launch {
            val sharedUris = XShareHelper.copyImagesToCache(this@MainActivity, listOf(imageUri))

            if (sharedUris.isEmpty()) {
                Toast.makeText(this@MainActivity, "画像の準備に失敗しました", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val shareIntent = XShareHelper.createXShareIntent(this@MainActivity, sharedUris)

            // 所有URIに記録（Xから戻った際に自動削除される対象）
            handoffStore.save(
                ownedUris = listOf(imageUri.toString()),
                externalUris = emptyList(),
                handedOff = true,
                leftApp = false
            )

            try {
                startActivity(shareIntent)
            } catch (e: Exception) {
                handoffStore.clear()
                Log.e("MainActivity", "Failed to start X app", e)
                Toast.makeText(this@MainActivity, "Xアプリを起動できませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Xアプリから本アプリへ戻ってきたときの自動削除処理
     */
    private fun processReturn(state: HandoffStore.State) {
        val ownedUris = state.ownedUris.map { Uri.parse(it) }
        val failedOwnedUris = mutableListOf<Uri>()

        for (uri in ownedUris) {
            try {
                val rows = contentResolver.delete(uri, null, null)
                if (rows <= 0) {
                    failedOwnedUris.add(uri)
                }
            } catch (_: SecurityException) {
                failedOwnedUris.add(uri)
            } catch (_: Exception) {}
        }

        if (failedOwnedUris.isNotEmpty()) {
            val confirmIntent = DeleteConfirmActivity.createIntent(
                this,
                ArrayList(failedOwnedUris)
            )
            startActivity(confirmIntent)
        }

        handoffStore.clear()
        pendingCameraUri = null
        binding.textStatus.text = "撮影した写真を自動削除しました。"
        Toast.makeText(this, "撮影した元写真を自動削除しました", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_PENDING_CAMERA_URI = "pending_camera_uri"
        private const val PREFS_SETTINGS = "autoshareposter_settings"
        private const val KEY_DO_NOT_SHOW_AGAIN = "do_not_show_again"
    }
}

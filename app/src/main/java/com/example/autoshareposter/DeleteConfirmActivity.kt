package com.example.autoshareposter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat

/**
 * 端末のMediaStoreシステム削除確認ダイアログ（Android OS標準）を表示する半透明Activity。
 */
class DeleteConfirmActivity : AppCompatActivity() {

    private val deleteRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(this, "元の写真を削除しました", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "削除をキャンセルしました", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 画面回転などの再生成時は多重起動を防ぐため処理しない
        if (savedInstanceState != null) {
            return
        }

        val rawUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_URIS)
        }

        if (rawUris.isNullOrEmpty()) {
            Log.w(TAG, "No URIs received in DeleteConfirmActivity")
            finish()
            return
        }

        // 各URIを端末の正規MediaStore URIへ解決
        val mediaUris = mutableListOf<Uri>()
        var skippedAny = false

        for (uri in rawUris) {
            val resolved = MediaStoreUriResolver.resolveToMediaUri(this, uri)
            if (resolved != null) {
                if (!mediaUris.contains(resolved)) {
                    mediaUris.add(resolved)
                }
            } else {
                skippedAny = true
                Log.w(TAG, "Could not resolve URI to MediaStore: $uri")
            }
        }

        if (mediaUris.isEmpty()) {
            Toast.makeText(
                this,
                "この画像は端末のメディアとして特定できないため削除できませんでした",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        if (skippedAny) {
            Toast.makeText(this, "一部の画像は端末内で特定できませんでした", Toast.LENGTH_SHORT).show()
        }

        try {
            // Android 11 (API 30) 以降のシステム標準一括削除確認ダイアログ（OSダイアログ）を作成
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, mediaUris)
            val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            deleteRequestLauncher.launch(request)
        } catch (e: Exception) {
            Log.e(TAG, "createDeleteRequest failed", e)
            // 直接削除 (自アプリ所有の場合など) のフォールバック
            var directlyDeleted = false
            for (u in mediaUris) {
                try {
                    val count = contentResolver.delete(u, null, null)
                    if (count > 0) directlyDeleted = true
                } catch (_: Exception) {}
            }
            if (directlyDeleted) {
                Toast.makeText(this, "元の写真を削除しました", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "削除確認ダイアログの表示に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    companion object {
        private const val TAG = "DeleteConfirmActivity"
        const val EXTRA_URIS = "extra_uris"

        fun createIntent(context: Context, uris: ArrayList<Uri>): Intent {
            return Intent(context, DeleteConfirmActivity::class.java).apply {
                putParcelableArrayListExtra(EXTRA_URIS, uris)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}

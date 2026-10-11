package com.example.autoshareposter

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * X (Twitter) 公式アプリへの画像受け渡しとファイルキャッシュを統括するヘルパークラス。
 */
object XShareHelper {

    private const val TAG = "XShareHelper"
    val TWITTER_PACKAGES = listOf("com.twitter.android", "com.twitter.android.lite")

    /**
     * X公式アプリまたはX Liteが端末に存在するか判定
     */
    fun isTwitterInstalled(context: Context): Boolean {
        for (pkg in TWITTER_PACKAGES) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(pkg, 0)
                }
                return true
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return false
    }

    /**
     * 端末にインストールされているXのパッケージ名を取得（なければnull）
     */
    fun getInstalledTwitterPackage(context: Context): String? {
        for (pkg in TWITTER_PACKAGES) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(pkg, 0)
                }
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    /**
     * 画像を安全に共有するためプライベートキャッシュディレクトリへ複製
     */
    suspend fun copyImagesToCache(context: Context, uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val result = mutableListOf<Uri>()

        for (sourceUri in uris) {
            try {
                val mimeType = context.contentResolver.getType(sourceUri) ?: "image/jpeg"
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
                val destFile = File(sharedDir, "share_${UUID.randomUUID()}.$extension")

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    destFile
                )
                result.add(contentUri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy image: $sourceUri", e)
            }
        }
        result
    }

    /**
     * X公式アプリ起動用インテントを構築
     */
    fun createXShareIntent(context: Context, sharedUris: List<Uri>): Intent {
        val targetPackage = getInstalledTwitterPackage(context) ?: "com.twitter.android"

        val intent = if (sharedUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, sharedUris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(sharedUris))
            }
        }.apply {
            type = "image/*"
            setPackage(targetPackage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val clip = ClipData.newUri(context.contentResolver, "shared_image", sharedUris[0])
            for (i in 1 until sharedUris.size) {
                clip.addItem(ClipData.Item(sharedUris[i]))
            }
            clipData = clip
        }

        for (uri in sharedUris) {
            try {
                context.grantUriPermission(
                    targetPackage,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "grantUriPermission failed for $uri", e)
            }
        }

        return intent
    }

    /**
     * 重ねて表示の権限を持っているかチェック
     */
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }
}

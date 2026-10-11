package com.example.autoshareposter

import android.content.Context
import java.io.File

/**
 * 共有キャッシュの管理ヘルパー。
 */
object CacheManager {

    /**
     * 24時間以上経過した古い一時キャッシュファイルを削除します。
     */
    fun purgeOldCache(context: Context) {
        try {
            val sharedDir = File(context.cacheDir, "shared")
            if (sharedDir.exists() && sharedDir.isDirectory) {
                val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
                sharedDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoffTime) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

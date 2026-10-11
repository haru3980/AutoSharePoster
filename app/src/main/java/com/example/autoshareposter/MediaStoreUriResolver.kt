package com.example.autoshareposter

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * 共有などで受け取った任意の画像URI（content://, file://, document provider等）を
 * Androidの MediaStore.Images.Media の正規URI（content://media/external/images/media/...）
 * へ解決・変換するユーティリティ。
 */
object MediaStoreUriResolver {

    private const val TAG = "MediaStoreUriResolver"

    /**
     * 与えられたURIから、削除可能なMediaStore URIを解決します。
     * 解決できない場合はnullを返します。
     */
    fun resolveToMediaUri(context: Context, uri: Uri): Uri? {
        val scheme = uri.scheme ?: return null
        val authority = uri.authority

        // 1. すでに標準の MediaStore URI の場合
        if (scheme == ContentResolver.SCHEME_CONTENT && authority == MediaStore.AUTHORITY) {
            return uri
        }

        // 2. MediaStore.getMediaUri API の試行 (Android 10+)
        try {
            val mediaUri = MediaStore.getMediaUri(context, uri)
            if (mediaUri != null && mediaUri.authority == MediaStore.AUTHORITY) {
                return mediaUri
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaStore.getMediaUri failed for $uri: ${e.message}")
        }

        // 3. Document URI (com.android.providers.media.documents など) の場合
        if (scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isDocumentUri(context, uri)) {
            val docId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) {
                null
            }
            if (docId != null) {
                if (authority == "com.android.providers.media.documents") {
                    // 形式: "image:12345"
                    val split = docId.split(":")
                    if (split.size >= 2 && split[0] == "image") {
                        val id = split[1].toLongOrNull()
                        if (id != null) {
                            return ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id
                            )
                        }
                    }
                } else if (authority == "com.android.providers.downloads.documents") {
                    val id = docId.toLongOrNull()
                    if (id != null) {
                        val contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"),
                            id
                        )
                        val resolved = findMediaUriByQuery(context, contentUri)
                        if (resolved != null) return resolved
                    }
                }
            }
        }

        // 4. file:// スキームの場合
        if (scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path
            if (path != null) {
                val resolved = findMediaUriByFilePath(context, path)
                if (resolved != null) return resolved
            }
        }

        // 5. content:// スキームで _data (ファイルパス) 列または DISPLAY_NAME と SIZE による照合
        val fallback = findMediaUriByContentResolver(context, uri)
        if (fallback != null) {
            return fallback
        }

        Log.w(TAG, "Could not resolve URI to MediaStore URI: $uri")
        return null
    }

    private fun findMediaUriByFilePath(context: Context, filePath: String): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATA} = ?"
        val selectionArgs = arrayOf(filePath)

        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "findMediaUriByFilePath failed for $filePath", e)
            null
        }
    }

    private fun findMediaUriByContentResolver(context: Context, uri: Uri): Uri? {
        var filePath: String? = null
        var displayName: String? = null
        var size: Long = -1

        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "_data"),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx)

                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)

                    val dataIdx = cursor.getColumnIndex("_data")
                    if (dataIdx >= 0) filePath = cursor.getString(dataIdx)
                }
            }
        } catch (_: Exception) {}

        if (!filePath.isNullOrEmpty()) {
            val byPath = findMediaUriByFilePath(context, filePath!!)
            if (byPath != null) return byPath
        }

        // ファイル名とサイズで MediaStore.Images.Media を検索
        if (!displayName.isNullOrEmpty()) {
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE)
            val selection = if (size > 0) {
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.SIZE} = ?"
            } else {
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            }
            val selectionArgs = if (size > 0) {
                arrayOf(displayName!!, size.toString())
            } else {
                arrayOf(displayName!!)
            }

            try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "findMediaUriByContentResolver query failed", e)
            }
        }

        return null
    }

    private fun findMediaUriByQuery(context: Context, uri: Uri): Uri? {
        return try {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (!path.isNullOrEmpty()) findMediaUriByFilePath(context, path) else null
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

package com.example.autoshareposter

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * X (Twitter) アプリへの引き渡し状態を端末ストレージに永続化するストア。
 * アプリのプロセスがキルされたり再生成されたりしても状態を確実に復元します。
 */
class HandoffStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class State(
        val ownedUris: List<String> = emptyList(),
        val externalUris: List<String> = emptyList(),
        val handedOff: Boolean = false,
        val leftApp: Boolean = false
    )

    /**
     * 状態を保存します。
     */
    fun save(ownedUris: List<String>, externalUris: List<String>, handedOff: Boolean, leftApp: Boolean) {
        try {
            val json = JSONObject().apply {
                put(KEY_OWNED_URIS, JSONArray(ownedUris))
                put(KEY_EXTERNAL_URIS, JSONArray(externalUris))
                put(KEY_HANDED_OFF, handedOff)
                put(KEY_LEFT_APP, leftApp)
            }
            prefs.edit().putString(KEY_STATE_JSON, json.toString()).apply()
        } catch (_: Exception) {
            clear()
        }
    }

    /**
     * 現在の状態を読み込みます。解析エラー時はクリアして初期状態を返します。
     */
    fun load(): State {
        val jsonStr = prefs.getString(KEY_STATE_JSON, null) ?: return State()
        return try {
            val json = JSONObject(jsonStr)
            val ownedArray = json.optJSONArray(KEY_OWNED_URIS) ?: JSONArray()
            val externalArray = json.optJSONArray(KEY_EXTERNAL_URIS) ?: JSONArray()

            val ownedList = mutableListOf<String>()
            for (i in 0 until ownedArray.length()) {
                ownedList.add(ownedArray.getString(i))
            }

            val externalList = mutableListOf<String>()
            for (i in 0 until externalArray.length()) {
                externalList.add(externalArray.getString(i))
            }

            State(
                ownedUris = ownedList,
                externalUris = externalList,
                handedOff = json.optBoolean(KEY_HANDED_OFF, false),
                leftApp = json.optBoolean(KEY_LEFT_APP, false)
            )
        } catch (_: Exception) {
            clear()
            State()
        }
    }

    /**
     * アプリから離脱した（Xなどの別アプリへ遷移した）フラグを立てます。
     */
    fun markLeftApp() {
        val current = load()
        if (current.handedOff) {
            save(
                ownedUris = current.ownedUris,
                externalUris = current.externalUris,
                handedOff = true,
                leftApp = true
            )
        }
    }

    /**
     * 外部URIリストをクリアします（フローティングバブルの90秒タイムアウト時などに使用）。
     */
    fun clearExternalUris() {
        val current = load()
        save(
            ownedUris = current.ownedUris,
            externalUris = emptyList(),
            handedOff = current.handedOff,
            leftApp = current.leftApp
        )
    }

    /**
     * 全状態をクリアします。
     */
    fun clear() {
        prefs.edit().remove(KEY_STATE_JSON).apply()
    }

    companion object {
        private const val PREFS_NAME = "autoshareposter_handoff"
        private const val KEY_STATE_JSON = "state_json"
        private const val KEY_OWNED_URIS = "owned_uris"
        private const val KEY_EXTERNAL_URIS = "external_uris"
        private const val KEY_HANDED_OFF = "handed_off"
        private const val KEY_LEFT_APP = "left_app"
    }
}

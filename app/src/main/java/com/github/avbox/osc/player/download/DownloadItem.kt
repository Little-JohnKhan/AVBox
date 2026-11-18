package com.github.avbox.osc.player.download

import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 一条下载任务的业务信息（media3 那边只认 id，剧名/集名这些得自己存） */
data class DownloadItem(
    val id: String,
    val vodId: String,
    val vodName: String,
    val flag: String?,
    val episodeIndex: Int,
    val episodeName: String,
    val playUrl: String,
    val headers: Map<String, String>?,
    val poster: String?,
    val createdAt: Long,
)

/** 元信息存 KV（JSON），不碰 Room，避免改数据库版本和迁移 */
object DownloadMetaStore {

    private const val KEY = "download_items"

    private val gson = Gson()
    private val type = object : TypeToken<List<DownloadItem>>() {}.type

    @Synchronized
    fun all(): List<DownloadItem> {
        return try {
            val raw: String = KV.get(KEY, "")
            if (raw.isEmpty()) emptyList() else gson.fromJson(raw, type) ?: emptyList()
        } catch (th: Throwable) {
            AppLog.e("DownloadMetaStore", "read failed", th)
            emptyList()
        }
    }

    @Synchronized
    fun put(item: DownloadItem) {
        try {
            val list = all().filterNot { it.id == item.id }.toMutableList()
            list.add(0, item)
            KV.put(KEY, gson.toJson(list, type))
        } catch (th: Throwable) {
            AppLog.e("DownloadMetaStore", "put failed", th)
        }
    }

    @Synchronized
    fun get(id: String): DownloadItem? = all().firstOrNull { it.id == id }

    @Synchronized
    fun remove(id: String) {
        try {
            val list = all().filterNot { it.id == id }
            KV.put(KEY, gson.toJson(list, type))
        } catch (th: Throwable) {
            AppLog.e("DownloadMetaStore", "remove failed", th)
        }
    }
}

package com.github.avbox.osc.player.download

/**
 * 当前播放上下文（进程内单例）。
 *
 * 播放页的下载按钮需要「这次播放的原始地址 + 请求头」，而播放内核在另一个包里，
 * 这里用一个极轻的单例把两者解耦：内核写入，UI 读取。
 */
object CurrentPlayback {

    @Volatile
    var url: String? = null
        private set

    @Volatile
    var headers: Map<String, String>? = null
        private set

    @Volatile
    var title: String = ""
        private set

    fun update(url: String?, headers: Map<String, String>?) {
        this.url = url
        this.headers = headers
    }

    fun updateTitle(value: String?) {
        title = value.orEmpty()
    }
}

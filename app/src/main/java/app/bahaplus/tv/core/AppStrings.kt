package app.bahaplus.tv.core

/** Centralized Simplified/Traditional Chinese text and official-label localization. */
object AppStrings {
    fun tr(traditional: Boolean, simplified: String, traditionalText: String): String =
        if (traditional) traditionalText else simplified

    fun enabled(traditional: Boolean, value: Boolean): String =
        if (value) tr(traditional, "开启", "開啟") else tr(traditional, "关闭", "關閉")

    fun onOff(traditional: Boolean, value: Boolean): String =
        if (value) tr(traditional, "开", "開") else tr(traditional, "关", "關")

    fun weekday(traditional: Boolean, day: Int): String {
        val simplified = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val trad = arrayOf("週日", "週一", "週二", "週三", "週四", "週五", "週六")
        return tr(traditional, simplified[day.coerceIn(0, 6)], trad[day.coerceIn(0, 6)])
    }

    /** Translate known Traditional labels parsed from the official API into the active UI script. */
    fun metadataLabel(traditional: Boolean, raw: String): String {
        if (traditional) return raw
        return when (raw) {
            "年份" -> "年份"
            "分級說明" -> "分级说明"
            "作品類型" -> "作品类型"
            "導演" -> "导演"
            "製作廠商" -> "制作厂商"
            "代理商" -> "代理商"
            "評分" -> "评分"
            else -> raw
        }
    }

    /** Translate fixed status labels such as "觀看結束" without altering numeric timestamps. */
    fun progressLabel(traditional: Boolean, raw: String): String =
        if (!traditional && raw == "觀看結束") "观看结束" else raw

    /** Format numeric episode labels as "第 X 集" while keeping specials/OVAs as-is. */
    fun episodeName(label: String): String {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.all { it in '0'..'9' || it == '.' }) "第 $trimmed 集" else trimmed
    }

    fun ratingWarning(traditional: Boolean, rating: Int?): String = when (rating) {
        1 -> tr(traditional, "普遍级：一般观众皆可观看。", "普遍級：一般觀眾皆可觀看。")
        2 -> tr(traditional, "保护级：未满 6 岁不宜观看，6～11 岁应由成人陪同。", "保護級：未滿 6 歲不宜觀看，6～11 歲應由成人陪同。")
        3 -> tr(traditional, "辅 12 级：未满 12 岁不宜观看。", "輔 12 級：未滿 12 歲不宜觀看。")
        4 -> tr(traditional, "辅 15 级：未满 15 岁不宜观看。", "輔 15 級：未滿 15 歲不宜觀看。")
        else -> tr(traditional, "限制级：未满 18 岁不宜观看，仍须通过官方年龄验证。", "限制級：未滿 18 歲不宜觀看，仍須通過官方年齡驗證。")
    }

    fun playbackError(traditional: Boolean, code: String): String = when (code) {
        "parent-verification-required" -> tr(traditional,
            "当前账号要求家长验证，接口未获准播放。请检查官方账号的家长设置后重试。",
            "目前帳號要求家長驗證，介面未獲准播放。請檢查官方帳號的家長設定後重試。")
        "verification-required" -> tr(traditional,
            "官方要求额外验证，请完成登录验证后重试。",
            "官方要求額外驗證，請完成登入驗證後重試。")
        "short-ad-needs-fallback" -> tr(traditional,
            "当前广告未完成所需播放时长，请重试。",
            "目前廣告未完成所需播放時長，請重試。")
        else -> tr(traditional,
            "官方播放请求未完成，请检查网络、地区或账号状态后重试。",
            "官方播放請求未完成，請檢查網路、地區或帳號狀態後重試。")
    }
}

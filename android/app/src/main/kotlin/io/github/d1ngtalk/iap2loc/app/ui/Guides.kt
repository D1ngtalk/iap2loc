package io.github.d1ngtalk.iap2loc.app.ui

import android.os.Build

/** Beginner guide texts, shared by the onboarding steps and the help screen. Mirrors README.md. */
object Guides {
    val iphoneSteps = listOf(
        "在 iPhone 上关闭 Wi-Fi、蜂窝数据（数据流量）和「定位服务」（设置 → 隐私与安全性 → 定位服务）。",
        "等待十秒后，重新打开「定位服务」。这时先不要打开 Wi-Fi。",
        "打开「地图」App，确认蓝点落在你设定的坐标上，而不是你实际所在的位置。",
        "打开 Wi-Fi（仍然不要打开数据流量），等定位稳定后，再打开数据流量。",
    )

    const val IPHONE_RETRY =
        "重复几次仍不生效（地图显示一个大范围的圈，提示「无法确定你的位置」）：关闭 Wi-Fi、数据流量和定位服务，" +
            "重启 iPhone；重启后保持 iap2loc 连接，再从第 3 步开始。"

    const val IOS26 =
        "iOS 26 及更高版本更改了位置融合算法和定位缓存：必须按上面的步骤刷新定位缓存，修改后的位置才会生效；" +
            "「路径模式」在打开 Wi-Fi 或数据流量后可能不可用。"

    class Problem(val symptom: String, val fix: String)

    val troubleshooting = listOf(
        Problem(
            "设备列表里没有我的 iPhone",
            "先在「新手引导 → 配对 iPhone」里完成配对；确认已允许「附近的设备」权限并打开了蓝牙。控制台下拉框默认只显示 Apple 设备，可勾选「显示非 Apple 设备」。",
        ),
        Problem(
            "连接超时，或提示 read failed / socket",
            "解锁 iPhone 并停留在「设置 → 蓝牙」页面，两台手机靠近；关闭再打开两边的蓝牙；仍失败时在 iPhone 和安卓上都删除配对记录，再重新配对。",
        ),
        Problem(
            "识别被拒绝（日志里有 0x1D03）",
            "在控制台「高级设置」点「更换身份与蓝牙地址」后重新连接；在设置里打开「详细协议日志」查看被拒绝的参数编号。",
        ),
        Problem(
            "认证失败（日志里有 0xAA04）",
            "MFi 身份文件无效、证书链不完整，或私钥与证书不匹配。请重新导入合法取得的 identity.pk8 与 certificate.p7b。",
        ),
        Problem(
            "显示已认证，但 iPhone 的位置没有变",
            "按「iPhone 端切换定位源」的步骤操作。iOS 26 及以上必须刷新定位缓存。不保证 iOS 一定采用配件定位。",
        ),
        Problem(
            "锁屏或切到其他 App 后连接断开",
            "允许通知、关闭电池优化，并按「后台运行」里对应手机品牌的设置放行 iap2loc。",
        ),
        Problem(
            "电脑浏览器打不开控制台",
            "在「设置」里打开「允许局域网访问」，让电脑和手机连同一个 Wi-Fi，然后在电脑上输入设置页显示的地址。",
        ),
        Problem(
            "地图是空白的",
            "地图图块需要联网（OSM 或高德）。没有网络时仍可以在「目标」里手动输入经纬度。",
        ),
    )

    /** Battery settings differ per brand; the generic Android ones are not enough on most Chinese ROMs. */
    fun backgroundTips(manufacturer: String = Build.MANUFACTURER): List<String> {
        val brand = manufacturer.lowercase()
        val specific = when {
            listOf("xiaomi", "redmi", "poco").any(brand::contains) ->
                "小米 / Redmi：设置 → 应用设置 → 应用管理 → iap2loc → 省电策略选「无限制」，并打开「自启动」。"
            listOf("huawei", "honor").any(brand::contains) ->
                "华为 / 荣耀：设置 → 电池 → 应用启动管理 → iap2loc，关闭「自动管理」，并允许自启动、关联启动和后台活动。"
            listOf("oppo", "realme", "oneplus").any(brand::contains) ->
                "OPPO / realme / 一加：设置 → 电池 → 应用耗电管理 → iap2loc，允许「后台运行」或选择「完全允许后台行为」。"
            listOf("vivo", "iqoo").any(brand::contains) ->
                "vivo / iQOO：设置 → 电池 → 后台耗电管理 → iap2loc，选择「允许后台高耗电」。"
            brand.contains("samsung") ->
                "三星：设置 → 电池 → 后台使用限制 → 把 iap2loc 加入「永不休眠的应用」。"
            else -> "设置 → 应用 → iap2loc → 电池，选择「不受限制」。"
        }

        return listOf(
            specific,
            "在最近任务（多任务）界面里长按或下拉 iap2loc 的卡片并「锁定」，避免一键清理时被关闭。",
        )
    }
}

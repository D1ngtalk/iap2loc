package io.github.d1ngtalk.iap2loc.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.d1ngtalk.iap2loc.app.ui.onboarding.OnboardingActivity
import io.github.d1ngtalk.iap2loc.app.ui.theme.Iap2LocTheme

/** The README's quick start, iPhone steps and troubleshooting table, readable on the phone. */
class HelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val section = intent.getStringExtra(EXTRA_SECTION)
        setContent { Iap2LocTheme { HelpScreen(section) } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HelpScreen(section: String?) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("帮助") },
                    navigationIcon = {
                        IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Expandable("快速上手", open = section == null) {
                    NumberedSteps(
                        listOf(
                            "按「新手引导」完成权限、MFi 身份导入和 iPhone 配对。",
                            "在控制台「设备」里选择 iPhone，点「连接」，等状态变成「已连接」（已认证）。",
                            "在地图上点选位置，或切到「路径」模式连点、导入 GPX，然后点右上角「应用修改」。",
                            "第一次使用时，按下面「iPhone 端切换定位源」操作一次。",
                        ),
                    )
                    OutlinedButton(onClick = { startActivity(Intent(this@HelpActivity, OnboardingActivity::class.java)) }) {
                        Text("重新打开新手引导")
                    }
                }

                Expandable("iPhone 端切换定位源", open = section == SECTION_IPHONE) {
                    NumberedSteps(Guides.iphoneSteps)
                    Callout(Guides.IOS26, Tone.WARN)
                    Callout(Guides.IPHONE_RETRY)
                }

                Expandable("控制台怎么用", open = false) {
                    NumberedSteps(
                        listOf(
                            "地图基准：OSM 使用 WGS-84，高德使用 GCJ-02，切换时坐标会自动换算。",
                            "单点模式：点地图或拖动标记，也可以直接填经纬度。",
                            "搜索地点：点顶部的放大镜，输入地名后按搜索键，从候选列表里选一个，模拟位置立即切换过去。服务商（默认 OpenStreetMap Photon，也可用高德、百度、腾讯等）在「设置 → 地点搜索」里选择。",
                            "路径模式：依次点地图连成路线，拖动序号调整，长按序号删除；或「导入 GPX」。带时间戳的 GPX 按录制节奏回放，否则按设定速度匀速前进。",
                            "随机化：坐标偏移、随机精度和高度会在点「应用修改」时掷一次，之后保持不变。",
                            "收藏：右上角「收藏当前」保存坐标，点收藏项即可回到该位置。",
                            "高级设置：上报周期、定位质量、卫星数、更换身份与蓝牙地址、TCP 传输和控制台日志。",
                        ),
                    )
                }

                Expandable("常见问题", open = section == SECTION_TROUBLE) {
                    for (problem in Guides.troubleshooting) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(problem.symptom, style = MaterialTheme.typography.titleSmall)
                            Text(problem.fix, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Expandable("后台运行", open = false) {
                    NumberedSteps(Guides.backgroundTips())
                    OutlinedButton(onClick = { openAppSettings() }) { Text("打开 iap2loc 的应用设置") }
                }

                Expandable("关于与许可", open = false) {
                    Text(
                        "iap2loc 是 GPL-3.0 开源软件，安卓版与 Windows / macOS 版共用同一个网页控制台和配置格式。" +
                            "地图库 Leaflet 1.9.4（BSD-2-Clause）随 App 离线打包。地点搜索默认使用 Photon，地点数据 © OpenStreetMap 贡献者（ODbL）。\n\n" +
                            "完成 MFi 认证需要合法取得的配件身份，App 不包含任何私钥或证书。仅在自有设备上用于协议研究与合规测试；" +
                            "不保证 iOS 一定采用配件定位。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { openUrl(SOURCE_URL) }) { Text("查看源代码") }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_IPHONE = "iphone"
        const val SECTION_TROUBLE = "trouble"
    }
}

@Composable
private fun Expandable(title: String, open: Boolean, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(open) }
    SectionCard {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        if (expanded) {
            Column(Modifier.animateContentSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

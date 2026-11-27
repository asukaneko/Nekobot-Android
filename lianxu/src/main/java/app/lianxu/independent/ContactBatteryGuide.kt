package app.lianxu.independent

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import java.util.Locale

internal fun backgroundSteps(manufacturer:String,brand:String):String {
    val names=setOf(brand.lowercase(Locale.ROOT),manufacturer.lowercase(Locale.ROOT))
    fun has(vararg choices:String)=choices.any{it in names}
    val steps=when{
        has("honor","huawei")->"在设置中搜索「应用启动管理」，找到本应用并关闭自动管理。\n打开「允许自启动」和「允许后台活动」。"
        has("xiaomi","redmi","poco")->"在本应用的应用详情中打开「自启动」。\n进入「省电策略」，选择「无限制」。"
        has("oppo","oneplus","realme")->"在设置中搜索「自启动管理」，允许本应用自启动。\n到本应用的耗电管理，允许后台运行。"
        has("vivo","iqoo")->"在权限管理中找到「自启动」，选中本应用。\n在电池设置中搜索「后台耗电管理」，允许本应用后台运行。"
        has("samsung")->"打开本应用的详情，进入「电池」并选择「不受限制」。\n在后台使用限制中，检查本应用是否在休眠应用列表。"
        else->"打开本应用的应用详情，点「电池」或「应用耗电量」，允许后台运行。\n若系统另外提供「自启动」，也可在那里开启。"
    }
    return steps+"\n如有电池优化名单，找到本应用并设为不受限制。菜单名随系统版本略有不同。"
}
@Composable internal fun ContactBatteryGuide(close:()->Unit){
    val context=LocalContext.current
    var unavailable by remember{mutableStateOf(false)}
    fun details()=runCatching{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:${context.packageName}".toUri()))}.isSuccess
    AlertDialog(close,modifier=Modifier.testTag("companion-background-hint"),title={Text("后台运行提示")},text={Column{
        Text(backgroundSteps(Build.MANUFACTURER,Build.BRAND))
        TextButton({unavailable=!runCatching{context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}.isSuccess && !details()}){Text("去开电池不受限制")}
        TextButton({unavailable=!details()}){Text("打开应用详情（自启动/后台）")}
        if(unavailable)Text("请在手机系统设置的应用列表中找到本应用，打开应用详情。")
    }},confirmButton={TextButton(close,modifier=Modifier.testTag("companion-background-close")){Text("知道了")}})
}

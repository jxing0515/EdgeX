package io.github.soclear.edgex.hook


import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.soclear.edgex.data.Preference
import io.github.soclear.edgex.hook.util.addAssetPath
import kotlinx.serialization.json.Json
import java.io.File

class Main : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var modulePath: String

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.processName != "com.microsoft.emmx" &&
            lpparam.processName != "com.microsoft.emmx.beta" &&
            lpparam.processName != "com.microsoft.emmx.canary" &&
            lpparam.processName != "com.microsoft.emmx.dev"
        ) {
            return
        }

        addAssetPath(modulePath)
        SettingsButton.addSettingsButtonToToolbar()
        Crx.crxInstallCompatibility()

        val preference: Preference = try {
            val dataStoreFile = File(lpparam.appInfo.dataDir, "files/datastore/${Preference.FILE_NAME}")
            // 自愈：被误记成非下载器（例如音乐播放器）的默认下载器直接作废，回退到弹窗选择
            Download.sanitize(json.decodeFromString<Preference>(dataStoreFile.readText()))
        } catch (_: Exception) {
            null
        } ?: run {
            XposedBridge.log("[EdgeX][Main] 偏好配置缺失或解析失败，模块功能未启用")
            return
        }

        // 每个功能独立安装：某个 hook 因 Edge 改版失效时，不影响其它功能
        fun install(name: String, block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                XposedBridge.log("[EdgeX][Main] 「$name」安装失败（不影响其它功能）")
                XposedBridge.log(t)
            }
        }

        install("隐藏状态栏") {
            if (preference.hideStatusBar) {
                Ui.hideStatusBar()
            }
        }
        install("移除 padding") {
            Ui.removePadding(preference.removeTopPadding, preference.removeBottomPadding)
        }
        install("长按更多回顶部") {
            if (preference.longClickOverflowButtonToTop) {
                LongClick.setupScrollToTopOnLongClickOverflowButton()
            }
        }
        install("长按新标签页原地加载") {
            if (preference.longClickNewTabButtonToLoadInplace) {
                LongClick.setupLoadUrlOnLongClickNewTabButton(
                    if (preference.setNewTabPageUrl) preference.newTabPageUrl else "edge://newtab/"
                )
            }
        }
        install("设置新标签页 URL") {
            if (preference.setNewTabPageUrl) {
                Ui.setNewTabPageUrl(preference.newTabPageUrl)
            }
        }
        install("外部下载") {
            if (preference.externalDownload) {
                Download.externalDownload(
                    preference.blockOriginalDownloadDialog,
                    preference.setDefaultDownloader,
                    preference.defaultDownloaderType,
                    preference.defaultDownloaderPackageName
                )
            }
        }
        install("平板工具栏同步") {
            if (preference.syncTabletToolbar) {
                TabletToolbar.syncTabletToolbarWithDesktop()
            }
        }
        install("收藏夹栏高度") {
            if (preference.adjustUiSize) {
                TabletToolbar.scaleBookmarkBarHeight(preference.bookmarkBarHeightPercent)
            }
        }
        install("主页按钮替换") {
            if (preference.longClickNewTabButtonToLoadInplace && preference.replaceNewTabPageWithHome) {
                HomeButton.replaceNewTabPageWithHome()
            }
        }
        install("退出清除浏览数据") {
            if (preference.clearBrowsingDataOnExit) {
                BrowsingData.clearBrowsingDataOnExit(
                    preference.clearBrowsingDataOnExitDataTypes.toIntArray(),
                    preference.clearBrowsingDataOnExitShouldClearTabs,
                    preference.clearBrowsingDataOnExitTimePeriod
                )
            }
        }
        install("强制外部浏览器") {
            if (preference.redirectCustomTab) {
                CustomTab.redirectCustomTab(lpparam)
            }
        }
    }
}

package io.github.soclear.edgex.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.text.TextUtils
import android.webkit.URLUtil
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.soclear.edgex.R
import io.github.soclear.edgex.data.DownloaderType
import io.github.soclear.edgex.data.Preference
import io.github.soclear.edgex.hook.util.afterAttach
import io.github.soclear.edgex.ui.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import io.github.soclear.edgex.hook.util.allFields
import io.github.soclear.edgex.hook.util.getHookConfig
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt


object Download {

    // 只把“像下载器”的应用列进候选/允许记住，避免把音乐播放器等误记成默认下载器。
    private val DOWNLOADER_PACKAGE_KEYWORDS =
        listOf("download", "xunlei", "thunder", "idm", "adm", "aria", "m3u8")

    internal fun isLikelyDownloader(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return DOWNLOADER_PACKAGE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * 修正被误记成非下载器的默认下载器（例如媒体播放器），
     * 让下载流程回退到弹窗让用户重新选择。
     */
    internal fun sanitize(preference: Preference): Preference =
        if (preference.setDefaultDownloader &&
            !isLikelyDownloader(preference.defaultDownloaderPackageName)
        ) {
            preference.copy(setDefaultDownloader = false)
        } else {
            preference
        }

    /**
     * DownloadInfo 的字段名会被 R8 每次构建重命名；类型和声明顺序则稳定。
     * 这里优先按类型/运行时值特征定位字段，名字只作为最后回退，
     * 避免 Edge 更新后读到错位的字段。
     */
    private const val DOWNLOAD_INFO_FIELDS_KEY = "EdgeXDownloadInfoFields"

    private class DownloadInfoFields(
        val url: Field?,
        val referrer: Field?,
        val guid: Field?,
        val mimeType: Field?,
        val fileName: Field?,
        val cookie: Field?,
        val userAgent: Field?,
        val totalBytes: Field?,
        val otrProfileId: Field?,
    )

    private fun resolveDownloadInfoFields(info: Any): DownloadInfoFields {
        val cls = info.javaClass
        val cached = try {
            XposedHelpers.getAdditionalStaticField(cls, DOWNLOAD_INFO_FIELDS_KEY)
        } catch (_: Throwable) {
            null
        }
        if (cached is DownloadInfoFields) return cached

        val fields = cls.allFields.filter {
            !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers)
        }
        fields.forEach {
            try {
                it.isAccessible = true
            } catch (_: Throwable) {
            }
        }

        fun valueOf(field: Field): Any? = try {
            field.get(info)
        } catch (_: Throwable) {
            null
        }

        val gurlFields = fields.filter { it.type.name == "org.chromium.url.GURL" }
        val stringFields = fields.filter { it.type == String::class.java }
        val stringValues = stringFields.map { it to (valueOf(it) as? String) }
        val longFields = fields.filter { it.type == Long::class.javaPrimitiveType }
        val longValues = longFields.map { it to (valueOf(it) as? Long) }

        val uuidRegex = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        // guid 是 UUID 字符串
        val guid = stringValues.firstOrNull { (_, v) -> v != null && uuidRegex.matches(v) }?.first
            ?: stringFields.getOrNull(6)
        // mimeType 形如 application/zip
        val mimeType = stringValues.firstOrNull { (_, v) ->
            v != null && v.contains('/') && !v.contains("://")
        }?.first ?: stringFields.getOrNull(1)
        // userAgent 含 Mozilla/Android/Chrome
        val userAgent = stringValues.firstOrNull { (_, v) ->
            v != null && (v.contains("Mozilla") || v.contains("Android") || v.contains("Chrome/"))
        }?.first ?: stringFields.getOrNull(0)
        // cookie 形如 "a=b; c=d"
        val cookie = stringValues.firstOrNull { (_, v) ->
            v != null && v.contains('=') && v.contains(';')
        }?.first ?: stringFields.getOrNull(2)
        // fileName 带扩展名，且不是 mime/URL/UA
        val fileName = stringValues.firstOrNull { (_, v) ->
            v != null && v.contains('.') && !v.contains("://") && !v.contains('/')
        }?.first ?: stringFields.getOrNull(3)
        // totalBytes: long 里的字节数；时间戳字段通常是 13 位毫秒值
        val totalBytes = longValues.firstOrNull { (_, v) ->
            v != null && v >= 0 && v < 1_000_000_000_000L
        }?.first ?: longFields.getOrNull(1)

        val resolved = DownloadInfoFields(
            url = gurlFields.getOrNull(0),
            referrer = gurlFields.getOrNull(1),
            guid = guid,
            mimeType = mimeType,
            fileName = fileName,
            cookie = cookie,
            userAgent = userAgent,
            totalBytes = totalBytes,
            otrProfileId = fields.firstOrNull { it.type.name.endsWith("OtrProfileId") },
        )
        try {
            XposedHelpers.setAdditionalStaticField(cls, DOWNLOAD_INFO_FIELDS_KEY, resolved)
        } catch (_: Throwable) {
        }
        return resolved
    }


    private fun readPreference(context: Context): Preference? = try {
        val file = File(
            context.applicationInfo.dataDir,
            "files/datastore/${Preference.FILE_NAME}"
        )
        if (!file.exists()) {
            null
        } else {
            sanitize(Json.decodeFromString<Preference>(file.readText()))
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 记住用户这次选择的第三方下载器：写入默认下载器设置，
     * 下次点下载会直接交给它，不再弹 EdgeX 的对话框。
     */
    private fun rememberDownloader(activity: Activity, packageName: String) {
        try {
            val app = activity.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.dataStore.updateData { current ->
                        current.copy(
                            setDefaultDownloader = true,
                            defaultDownloaderType = DownloaderType.THIRD_PARTY_APP,
                            defaultDownloaderPackageName = packageName
                        )
                    }
                } catch (_: Throwable) {
                }
            }
            Toast.makeText(activity, "已记住此下载器，下次下载直接使用", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    fun externalDownload(
        blockOriginalDownloadDialog: Boolean,
        setDefaultDownloader: Boolean,
        defaultDownloaderType: DownloaderType,
        defaultDownloaderPackageName: String
    ) = afterAttach {
        var topActivityRef: WeakReference<Activity>? = null

        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    topActivityRef = WeakReference(param.thisObject as Activity)
                }
            }
        )

        val applicationStatusClass = XposedHelpers.findClassIfExists(
            "org.chromium.base.ApplicationStatus",
            classLoader
        )

        fun getTopActivity(): Activity? {
            val fromRef = topActivityRef?.get()
            if (fromRef != null && !fromRef.isFinishing && !fromRef.isDestroyed) {
                return fromRef
            }
            if (applicationStatusClass != null) {
                val fromAppStatus = try {
                    XposedHelpers.callStaticMethod(applicationStatusClass, "getLastTrackedFocusedActivity") as? Activity
                } catch (_: Throwable) {
                    try {
                        // 静态字段名会被 R8 重命名，按类型（Activity）定位
                        applicationStatusClass.allFields.firstOrNull {
                            Modifier.isStatic(it.modifiers) &&
                                it.type.name == Activity::class.java.name
                        }?.let { field ->
                            field.isAccessible = true
                            field.get(null) as? Activity
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (fromAppStatus != null && !fromAppStatus.isFinishing && !fromAppStatus.isDestroyed) {
                    return fromAppStatus
                }
            }
            return null
        }

        // 已接管的下载 GUID（onDownloadUpdated 会多次回调，用它去重，只在首次接管）
        val handledGuids = java.util.Collections.synchronizedSet(HashSet<String>())
        // 缓存 GURL 取 spec 方法名的键（附加在 GURL Class 上）
        val gurlSpecMethodKey = "EdgeXGurlSpecMethod"

        if (blockOriginalDownloadDialog) {
            val downloadDialogBridge = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.download.DownloadDialogBridge",
                classLoader
            )
            val duplicateBridgeClass = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                classLoader
            )
            val messageUiController = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.edge_hub.downloads.EdgeDownloadMessageUiControllerImpl",
                classLoader
            )
            val edgeDownloadManagerHelper = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.edge_hub.downloads.EdgeDownloadManagerHelper",
                classLoader
            )
            val edgeDownloadManagerFeatureBridge = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.download.EdgeDownloadManagerFeatureBridge",
                classLoader
            )
            val offlineItemClass = XposedHelpers.findClassIfExists(
                "org.chromium.components.offline_items_collection.OfflineItem",
                classLoader
            )
            val updateDeltaClass = XposedHelpers.findClassIfExists(
                "org.chromium.components.offline_items_collection.UpdateDelta",
                classLoader
            )
            val windowAndroidClass = XposedHelpers.findClassIfExists(
                "org.chromium.ui.base.WindowAndroid",
                classLoader
            )
            val profileClass = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.profiles.Profile",
                classLoader
            )

            if (messageUiController != null && offlineItemClass != null && updateDeltaClass != null) {
                // Edge 的下载提示是 in-app notification，不是 android.app.Dialog。
                XposedHelpers.findAndHookMethod(
                    messageUiController,
                    "onItemUpdated",
                    offlineItemClass,
                    updateDeltaClass,
                    XC_MethodReplacement.returnConstant(null)
                )
            }

            if (edgeDownloadManagerHelper != null) {
                // 启用新版下载流判断，避免进入旧版 ModalDialog 弹窗分支
                XposedHelpers.findAndHookMethod(
                    edgeDownloadManagerHelper,
                    "isUseNewDownloadDialogFlowEnabled",
                    XC_MethodReplacement.returnConstant(true)
                )
                XposedHelpers.findAndHookMethod(
                    edgeDownloadManagerHelper,
                    "isInAppNotificationEnabled",
                    XC_MethodReplacement.returnConstant(false)
                )
            }

            if (edgeDownloadManagerFeatureBridge != null) {
                XposedHelpers.findAndHookMethod(
                    edgeDownloadManagerFeatureBridge,
                    "isUseNewDownloadDialogFlowEnabled",
                    XC_MethodReplacement.returnConstant(true)
                )
                // 禁用 Edge 原生下载确认弹窗
                XposedHelpers.findAndHookMethod(
                    edgeDownloadManagerFeatureBridge,
                    "shouldConfirmDownload",
                    XC_MethodReplacement.returnConstant(false)
                )
            }

            if (duplicateBridgeClass != null) {
                // DuplicateDownloadDialogBridge.showDialog 在 z2 为 true 时直接回调 native 并返回，不弹窗
                XposedBridge.hookAllMethods(duplicateBridgeClass, "showDialog", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size > 6 && param.args[6] is Boolean) {
                            param.args[6] = true
                        }
                    }
                })
            }

            if (downloadDialogBridge != null && windowAndroidClass != null && profileClass != null) {
                var completeDialogMethod: Method? = null
                XposedHelpers.findAndHookMethod(
                    downloadDialogBridge,
                    "showDialog",
                    windowAndroidClass,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    profileClass,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                try {
                                    // 字段名会被 R8 重命名，按类型定位 WindowAndroid / Profile
                                    param.thisObject.javaClass.allFields.firstOrNull {
                                        it.type.name == windowAndroidClass.name
                                    }?.let { field ->
                                        field.isAccessible = true
                                        field.set(param.thisObject, param.args[0])
                                    }
                                    param.thisObject.javaClass.allFields.firstOrNull {
                                        it.type.name == profileClass.name
                                    }?.let { field ->
                                        field.isAccessible = true
                                        field.set(param.thisObject, param.args[5])
                                    }
                                } catch (_: Throwable) {}
                                val suggestedPath = (param.args[4] as? String)?.takeIf { it.isNotEmpty() } ?: ""
                                val method = completeDialogMethod
                                    ?: param.thisObject.javaClass.declaredMethods.singleOrNull {
                                        it.returnType == Void.TYPE &&
                                                it.parameterTypes.size == 2 &&
                                                it.parameterTypes[0] == String::class.java &&
                                                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                                    }?.also {
                                        it.isAccessible = true
                                        completeDialogMethod = it
                                    }
                                method?.invoke(param.thisObject, suggestedPath, false)
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            } finally {
                                param.result = null
                            }
                        }
                    }
                )
            }

            var hookConfig = getHookConfig { getHookConfigFromDexKit() }
            if (hookConfig != null && hookConfig.downloadDialogHookVersion < 1) {
                // 旧缓存未包含下载相关方法，清除并重新解析生成
                File(filesDir, "EdgeXHookConfig.json").delete()
                hookConfig = getHookConfig { getHookConfigFromDexKit() }
            }

            if (hookConfig != null) {
                hookConfig.methodDangerousDownloadCondition?.let { descriptor ->
                    try {
                        val method = DexMethod(descriptor).getMethodInstance(classLoader)
                        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(true))
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                hookConfig.methodDangerousDownloadConfirm?.let { descriptor ->
                    try {
                        val method = DexMethod(descriptor).getMethodInstance(classLoader)
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val callback = param.args[2]
                                    if (callback != null) {
                                        XposedHelpers.callMethod(callback, "onResult", true)
                                    }
                                    param.result = null
                                } catch (t: Throwable) {
                                    XposedBridge.log(t)
                                }
                            }
                        })
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                hookConfig.methodDownloadWarningConfirm?.let { descriptor ->
                    try {
                        val method = DexMethod(descriptor).getMethodInstance(classLoader)
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val callback = param.args[5] ?: return
                                    // Edge 155 使用 Callback<Integer>：1 为确认，2 为取消。
                                    // 必须完成回调再拦截，否则 native 下载会一直等待确认。
                                    XposedHelpers.callMethod(callback, "onResult", 1)
                                    param.result = null
                                } catch (t: Throwable) {
                                    XposedBridge.log(t)
                                }
                            }
                        })
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            }
        }

        val downloadManagerService = XposedHelpers.findClassIfExists(
            "org.chromium.chrome.browser.download.DownloadManagerService",
            classLoader
        ) ?: return@afterAttach

        // Edge 150+ 起，下载完全由 Chromium 原生引擎处理：DownloadManagerService.onDownloadItemCreated
        // 已成死代码，cookie/UA 也不再传到 Java 层。实测真正会触发的是
        // DownloadController.onDownloadUpdated(DownloadInfo)（下载进度回调）。
        val downloadController = XposedHelpers.findClassIfExists(
            "org.chromium.chrome.browser.download.DownloadController",
            classLoader
        ) ?: return@afterAttach

        XposedHelpers.findAndHookMethod(
            downloadController,
            "onDownloadUpdated",
            "org.chromium.chrome.browser.download.DownloadInfo",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val downloadInfo = param.args[0] ?: return
                        // 字段名可能随 Edge 更新变化，按类型/值特征解析一次后缓存
                        val infoFields = resolveDownloadInfoFields(downloadInfo)

                        fun readString(field: Field?): String? = try {
                            field?.get(downloadInfo) as? String
                        } catch (_: Throwable) {
                            null
                        }

                        // GUID 用于去重与取消 Edge 原生下载
                        val guid = readString(infoFields.guid) ?: return
                        // onDownloadUpdated 会随进度多次回调，只在首次接管
                        if (!handledGuids.add(guid)) return

                        val mimeType = readString(infoFields.mimeType)
                        // 排除插件
                        if (mimeType == "application/x-chrome-extension") {
                            return
                        }

                        // URL 是 GURL，取字符串（方法名混淆，反射解析）
                        val url = gurlToString(
                            try {
                                infoFields.url?.get(downloadInfo)
                            } catch (_: Throwable) {
                                null
                            }
                        )
                        if (url.isNullOrEmpty()) return

                        // referrer 是另一个 GURL
                        val referrer = gurlToString(
                            try {
                                infoFields.referrer?.get(downloadInfo)
                            } catch (_: Throwable) {
                                null
                            }
                        )
                        // cookie / UA 在新版 Edge 原生下载路径已不再传到 Java 层，恒为 null
                        val cookie = readString(infoFields.cookie)
                        val userAgent = readString(infoFields.userAgent)
                        // 文件名：优先用 DownloadInfo 自带字段，否则从 url 推断
                        val fileName = readString(infoFields.fileName)
                            ?.takeIf { it.isNotEmpty() }
                            ?: URLUtil.guessFileName(url, null, mimeType)
                        // 真实文件大小，拿不到则为 0/-1
                        val totalBytes = try {
                            infoFields.totalBytes?.getLong(downloadInfo) ?: 0L
                        } catch (_: Throwable) {
                            0L
                        }
                        // OtrProfileId，取消下载时需要
                        val otrProfileId = try {
                            infoFields.otrProfileId?.get(downloadInfo)
                        } catch (_: Throwable) {
                            null
                        }

                        val activity = getTopActivity()
                        val isValidActivity =
                            activity != null && !activity.isFinishing && !activity.isDestroyed
                        val context: Context =
                            (if (isValidActivity) activity else AndroidAppHelper.currentApplication())
                                ?: return

                        // 每次下载时实时读取设置，保证在下载对话框里“记住选择”后立即生效，
                        // 不需要重启 Edge 才走默认下载器。
                        val livePreference = readPreference(context)
                        val useDefaultDownloader =
                            livePreference?.setDefaultDownloader ?: setDefaultDownloader
                        val liveDownloaderType =
                            livePreference?.defaultDownloaderType ?: defaultDownloaderType
                        val liveDownloaderPackageName =
                            livePreference?.defaultDownloaderPackageName
                                ?: defaultDownloaderPackageName

                        // 只有在我们确实要接管时才取消 Edge 的原生下载，否则放行，避免下载丢失
                        if (useDefaultDownloader) {
                            cancelEdgeDownload(guid, otrProfileId)
                            if (liveDownloaderType == DownloaderType.SYSTEM_DOWNLOADER) {
                                systemDownload(
                                    url,
                                    cookie,
                                    userAgent,
                                    referrer,
                                    mimeType,
                                    fileName,
                                    context
                                )
                            } else {
                                thirdPartyDownload(
                                    url,
                                    mimeType,
                                    cookie,
                                    userAgent,
                                    referrer,
                                    context,
                                    liveDownloaderPackageName
                                )
                            }
                        } else if (isValidActivity) {
                            showExternalDownloadDialog(
                                activity,
                                fileName,
                                totalBytes,
                                url,
                                cookie,
                                userAgent,
                                referrer,
                                mimeType,
                                onShown = { cancelEdgeDownload(guid, otrProfileId) },
                                onFailure = { handledGuids.remove(guid) }
                            )
                        } else {
                            // 没有可用 Activity 弹窗，又未设默认下载器：放行 Edge 原生下载，
                            // 并把 GUID 从已处理集合移除，等下次回调再尝试接管
                            handledGuids.remove(guid)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                /**
                 * 取消 Edge 已经开始的原生下载。用 DownloadManagerService.removeDownload
                 * （按 GUID 删除，走 native）。
                 */
                private fun cancelEdgeDownload(
                    guid: String,
                    otrProfileId: Any?
                ) {
                    val dms = try {
                        XposedHelpers.callStaticMethod(downloadManagerService, "a")
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                        return
                    }
                    try {
                        XposedHelpers.callMethod(dms, "removeDownload", guid, otrProfileId, false)
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                /**
                 * 将 GURL 对象转成字符串。GURL 取 spec 的方法在混淆下每个版本可能不同，
                 * 这里通过“无参、返回 String、且返回值形如完整 URL”的特征反射定位并缓存。
                 */
                private fun gurlToString(gurl: Any?): String? {
                    if (gurl == null) return null
                    val clazz = gurl.javaClass
                    val cachedName = XposedHelpers.getAdditionalStaticField(
                        clazz,
                        gurlSpecMethodKey
                    ) as? String
                    if (cachedName != null) {
                        return XposedHelpers.callMethod(gurl, cachedName) as? String
                    }
                    for (method in clazz.declaredMethods) {
                        if (method.parameterTypes.isNotEmpty()) continue
                        if (method.returnType != String::class.java) continue
                        try {
                            method.isAccessible = true
                            val value = method.invoke(gurl) as? String ?: continue
                            if (value.contains("://") || value.startsWith("blob:") ||
                                value.startsWith("data:")
                            ) {
                                XposedHelpers.setAdditionalStaticField(
                                    clazz,
                                    gurlSpecMethodKey,
                                    method.name
                                )
                                return value
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    return null
                }

                private fun showExternalDownloadDialog(
                    activity: Activity,
                    fileName: String?,
                    totalBytes: Long,
                    url: String,
                    cookie: String?,
                    userAgent: String?,
                    referrer: String?,
                    mimeType: String?,
                    onShown: () -> Unit,
                    onFailure: () -> Unit
                ) {
                    Handler(Looper.getMainLooper()).post {
                        if (activity.isFinishing || activity.isDestroyed) {
                            onFailure()
                            return@post
                        }
                        try {
                            // 模块资源注入在 Activity.attach 完成后；afterAttach 的 split Context
                            // 不保证包含模块资源，按钮文字必须从当前 Activity 读取。
                            //
                            // 一次点选：直接列出系统下载器 + 已安装的第三方下载器 + 其他应用兜底，
                            // 不再让用户先选“第三方应用”再过一层系统选择器。
                            val candidates = queryDownloaderCandidates(activity, url, mimeType)
                            val labels = ArrayList<String>(candidates.size + 2)
                            labels.add(activity.getString(R.string.download_system))
                            candidates.mapTo(labels) { it.second }
                            labels.add(activity.getString(R.string.download_third_party))

                            AlertDialog.Builder(activity)
                                .setTitle(fileName)
                                .setView(createUrlContainer(activity, totalBytes, url))
                                .setItems(labels.toTypedArray()) { _, which ->
                                    try {
                                        when (which) {
                                            0 -> systemDownload(
                                                url,
                                                cookie,
                                                userAgent,
                                                referrer,
                                                mimeType,
                                                fileName,
                                                activity
                                            )

                                            labels.lastIndex -> thirdPartyDownload(
                                                url,
                                                mimeType,
                                                cookie,
                                                userAgent,
                                                referrer,
                                                activity,
                                                null
                                            )

                                            else -> {
                                                val candidate = candidates[which - 1]
                                                rememberDownloader(activity, candidate.first)
                                                thirdPartyDownload(
                                                    url,
                                                    mimeType,
                                                    cookie,
                                                    userAgent,
                                                    referrer,
                                                    activity,
                                                    candidate.first
                                                )
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        XposedBridge.log(t)
                                    }
                                }
                                .setNegativeButton(activity.getString(R.string.download_copy_link)) { _, _ ->
                                    copyLink(activity, url)
                                }
                                .setOnDismissListener {
                                    closeBlankTab(activity)
                                }
                                .create()
                                .show()
                            // 弹窗成功展示后才取消原生下载，避免资源或窗口异常导致下载丢失。
                            onShown()
                        } catch (t: Throwable) {
                            onFailure()
                            XposedBridge.log(t)
                        }
                    }
                }

                /**
                 * 查询能处理当前下载链接的应用，供对话框直接列出（包名 to 应用名）。
                 * 用应用名而不是 Activity 名，并过滤浏览器自身与系统下载/文件管理器。
                 */
                @Suppress("DEPRECATION")
                private fun queryDownloaderCandidates(
                    activity: Activity,
                    url: String,
                    mimeType: String?
                ): List<Pair<String, String>> {
                    return try {
                        val uri = url.toUri()
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            if (!mimeType.isNullOrBlank()) {
                                setDataAndType(uri, mimeType)
                            } else {
                                data = uri
                            }
                        }
                        val pm = activity.packageManager
                        val excluded = setOf(
                            activity.packageName,
                            "com.android.providers.downloads",
                            "com.android.providers.downloads.ui",
                            "com.android.documentsui",
                        )
                        pm.queryIntentActivities(intent, 0)
                            .mapNotNull { info ->
                                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                                if (pkg in excluded) return@mapNotNull null
                                if (!isLikelyDownloader(pkg)) return@mapNotNull null
                                val label = try {
                                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
                                        .toString().trim()
                                } catch (_: Throwable) {
                                    info.loadLabel(pm)?.toString()?.trim().orEmpty()
                                }
                                if (label.isEmpty()) null else pkg to label
                            }
                            .distinctBy { it.first }
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }

                private fun createUrlContainer(
                    activity: Activity,
                    totalBytes: Long,
                    url: String
                ): LinearLayout {
                    val padding =
                        (20 * activity.resources.displayMetrics.density).roundToInt()
                    val container = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(padding, padding / 2, padding, 0)
                    }

                    container.addView(TextView(activity).apply {
                        text = formatFileSize(totalBytes)
                    })

                    container.addView(TextView(activity).apply {
                        text = Uri.decode(url)
                        setPadding(
                            0,
                            (8 * activity.resources.displayMetrics.density).roundToInt(),
                            0,
                            0
                        )
                        maxLines = 5
                        ellipsize = TextUtils.TruncateAt.END
                        setTextIsSelectable(true)
                    })
                    return container
                }


                fun formatFileSize(size: Long): String {
                    if (size <= 0) return "0 B"

                    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
                    // 计算单位所在的索引 (通过对数计算，性能优于循环除法)
                    val digitGroups =
                        (log10(size.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)

                    // 计算得出具体数值
                    val value = size / 1024.0.pow(digitGroups.toDouble())

                    // 格式化输出：最多保留两位小数，如果末尾是0会自动省略 (例如展示为 1.5 MB 而不是 1.50 MB)
                    val decimalFormat = DecimalFormat("#,##0.##")
                    return "${decimalFormat.format(value)} ${units[digitGroups]}"
                }

                private fun systemDownload(
                    url: String,
                    cookie: String?,
                    userAgent: String?,
                    referrer: String?,
                    mimeType: String?,
                    fileName: String?,
                    context: Context
                ) {
                    val uri = url.toUri()
                    val request = DownloadManager.Request(uri).apply {
                        if (!cookie.isNullOrBlank()) addRequestHeader(
                            "Cookie",
                            cookie
                        )
                        if (!userAgent.isNullOrBlank()) addRequestHeader(
                            "User-Agent",
                            userAgent
                        )
                        if (!referrer.isNullOrBlank()) addRequestHeader(
                            "Referer",
                            referrer
                        )
                        if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                        setTitle(fileName)
                        setDescription(context.getString(R.string.download_downloading))
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                        )
                        setAllowedOverMetered(true)
                        setAllowedOverRoaming(true)
                    }

                    val downloadManager =
                        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                }

                private fun thirdPartyDownload(
                    url: String,
                    mimeType: String?,
                    cookie: String?,
                    userAgent: String?,
                    referrer: String?,
                    context: Context,
                    targetPackageName: String?
                ) {
                    try {
                        val uri = url.toUri()
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                            // 安全地设置 Uri 和 MimeType
                            if (!mimeType.isNullOrBlank()) {
                                setDataAndType(uri, mimeType)
                            } else {
                                // 如果没有 mimeType，仅设置 uri
                                data = uri
                            }

                            val headers = Bundle().apply {
                                if (!cookie.isNullOrBlank()) putString(
                                    "Cookie",
                                    cookie
                                )
                                if (!userAgent.isNullOrBlank()) putString(
                                    "User-Agent",
                                    userAgent
                                )
                                if (!referrer.isNullOrBlank()) putString(
                                    "Referer",
                                    referrer
                                )
                            }
                            putExtra(Browser.EXTRA_HEADERS, headers)
                        }

                        if (targetPackageName.isNullOrBlank()) {
                            val chooser =
                                Intent.createChooser(
                                    intent,
                                    context.getString(R.string.download_chooser_title)
                                )
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(chooser)
                        } else {
                            // 指定了包名
                            intent.setPackage(targetPackageName)
                            val pm = context.packageManager

                            // 局部函数：寻找目标并用 ApplicationContext 强制启动
                            val tryForceLaunch = { testIntent: Intent ->
                                var isLaunched = false

                                // 使用 queryIntentActivities 获取所有能处理该 Intent 的 Activity
                                @SuppressLint("QueryPermissionsNeeded")
                                val resolveInfos = pm.queryIntentActivities(testIntent, 0)
                                // 同一个应用可能有多个入口（例如 IDM+ 既有浏览器入口，也有纯下载 Activity）。
                                // 优先选名字像“下载”的入口，并避开浏览器/启动器入口，避免跳进下载器自带的浏览器。
                                val candidates = resolveInfos.filter {
                                    it.activityInfo.packageName == targetPackageName
                                }
                                val targetInfo = candidates.firstOrNull {
                                    it.activityInfo.name.contains("download", true)
                                } ?: candidates.firstOrNull {
                                    !it.activityInfo.name.contains("browser", true) &&
                                        !it.activityInfo.name.contains("launcher", true)
                                } ?: candidates.firstOrNull()

                                if (targetInfo != null) {
                                    // 找到了目标应用内的真实 Activity，强制转为显式 Intent
                                    testIntent.component = ComponentName(
                                        targetInfo.activityInfo.packageName,
                                        targetInfo.activityInfo.name
                                    )
                                    try {
                                        context.applicationContext.startActivity(testIntent)
                                        isLaunched = true
                                    } catch (e: Exception) {
                                        XposedBridge.log("Explicit launch failed: ${e.stackTraceToString()}")
                                    }
                                }

                                // 如果找不到具体的 Activity（可能是受到 Android 11+ 包可见性限制）
                                // 或者上面的启动失败了，我们尝试清除 Component 并直接依赖 setPackage 启动
                                if (!isLaunched) {
                                    try {
                                        // 极其重要：清除可能残留的错误 component（防止携带系统的 ResolverActivity）
                                        testIntent.component = null
                                        testIntent.setPackage(targetPackageName)
                                        context.applicationContext.startActivity(testIntent)
                                        isLaunched = true
                                    } catch (e: Exception) {
                                        XposedBridge.log("Implicit launch failed: ${e.stackTraceToString()}")
                                    }
                                }

                                isLaunched
                            }

                            // 1. 精确匹配
                            var isLaunched = tryForceLaunch(intent)

                            if (!isLaunched) {
                                // 2. 降级匹配: */*
                                intent.setDataAndType(uri, "*/*")
                                isLaunched = tryForceLaunch(intent)
                            }

                            if (!isLaunched) {
                                // 3. 降级匹配: 只保留 URL
                                intent.data = uri
                                isLaunched = tryForceLaunch(intent)
                            }

                            if (!isLaunched) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.download_target_app_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.download_no_app_found),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                private fun copyLink(activity: Activity, url: String) {
                    val clipboard =
                        activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(
                        activity.getString(R.string.download_clipboard_label),
                        url
                    )
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.download_copied_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                private fun closeBlankTab(activity: Activity) {
                    // 下面代码只是为了关闭因为下载而产生的空白标签页
                    // 而原版下载会自动关闭
                    if (!blockOriginalDownloadDialog) return
                    val currentTab = try {
                        val key = "ActivityTabProviderField"

                        val activityTabProviderField =
                            XposedHelpers.getAdditionalStaticField(
                                Unit,
                                key
                            ) as Field?
                                ?: run {
                                    activity.javaClass.allFields.find {
                                        it.type.name == "org.chromium.chrome.browser.ActivityTabProvider"
                                    }?.also {
                                        XposedHelpers.setAdditionalStaticField(
                                            Unit,
                                            key,
                                            it
                                        )
                                    }
                                } ?: return

                        activityTabProviderField.isAccessible = true
                        val activityTabProvider =
                            activityTabProviderField.get(activity)
                        XposedHelpers.callMethod(activityTabProvider, "get")
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                        null
                    } ?: return


                    val gurl = XposedHelpers.callMethod(currentTab, "getUrl")
                    val currentUrl = gurlToString(gurl)
                    // about:blank
                    if (currentUrl == "") {
                        val activityName = activity.javaClass.name
                        if (activityName.contains("CustomTabActivity")) {
                            activity.finish()
                        } else if (activityName.contains("ChromeTabbedActivity")) {
                            XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass(
                                    "org.chromium.chrome.browser.tab.TabImpl",
                                    classLoader
                                ),
                                "closeTabFromNative",
                                currentTab
                            )
                        }
                    }
                }
            }
        )
    }
}

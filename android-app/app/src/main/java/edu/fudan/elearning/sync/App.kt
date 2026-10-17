package edu.fudan.elearning.sync

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import edu.fudan.elearning.sync.office.OfficeExtractor
import edu.fudan.elearning.sync.worker.Notifier

/**
 * 应用入口：初始化通知渠道，并把 POI 的单记录安全上限按设备内存自适应放宽。
 *
 * 放宽上限必须在任何 Office 解析之前完成（含后台 Worker），因此放在 Application.onCreate：含超大内嵌媒体/OLE 的文档否则会直接被 POI 拒绝。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        OfficeExtractor.applyPoiLimits()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components {
                    if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                    else add(GifDecoder.Factory())
                    add(SvgDecoder.Factory())
                }
                .build()
        )
        Notifier.createChannel(this)
    }
}

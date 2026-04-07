package com.nuvio.tv

import android.app.Application
import android.os.Build
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nuvio.tv.core.sync.StartupSyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var startupSyncService: StartupSyncService

    override fun onCreate() {
        super.onCreate()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(4))
            .bitmapFactoryMaxParallelism(2)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
}

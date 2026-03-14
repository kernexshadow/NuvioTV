package com.lagradost.cloudstream3.extractors

import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorApi

private const val TAG = "BuiltInExtractors"

/**
 * All built-in extractor instances that NuvioTV ships with.
 * These mirror the extractors that CloudStream3 compiles into its APK.
 * Extensions call loadExtractor() with URLs from these domains,
 * expecting them to already be registered.
 */
object BuiltInExtractorRegistry {
    private var initialized = false

    fun getAll(): List<ExtractorApi> {
        return buildList {
            // Filemoon family
            add(FilemoonV2())
            add(FileMoon())
            add(FileMoonIn())
            add(FileMoonSx())

            // StreamWish family
            add(StreamWishExtractor())
            add(Mwish())
            add(Dwish())
            add(Ewish())
            add(WishembedPro())
            add(Kswplayer())
            add(Wishfast())
            add(Streamwish2())
            add(SfastwishCom())
            add(Strwish())
            add(Strwish2())
            add(FlaswishCom())
            add(Awish())
            add(Obeywish())
            add(Jodwish())
            add(Swhoi())
            add(Multimovies())
            add(UqloadsXyz())
            add(Doodporn())
            add(CdnwishCom())
            add(Asnwish())
            add(Nekowish())
            add(Nekostream())
            add(Swdyu())
            add(Wishonly())
            add(Playerwish())
            add(StreamHLS())
            add(HlsWish())

            // Voe family
            add(Voe())
            add(Tubeless())
            add(Simpulumlamerop())
            add(Urochsunloath())
            add(NathanFromSubject())
            add(Yipsu())
            add(MetaGnathTuggers())
            add(Voe1())

            // MixDrop family
            add(MixDrop())
            add(MixDropPs())
            add(Mdy())
            add(MxDropTo())
            add(MixDropSi())
            add(MixDropBz())
            add(MixDropAg())
            add(MixDropCh())
            add(MixDropTo())

            // DoodStream family
            add(DoodLaExtractor())
            add(Doodspro())
            add(Dsvplay())
            add(D0000d())
            add(D000dCom())
            add(DoodstreamCom())
            add(Dooood())
            add(DoodWfExtractor())
            add(DoodCxExtractor())
            add(DoodShExtractor())
            add(DoodWatchExtractor())
            add(DoodPmExtractor())
            add(DoodToExtractor())
            add(DoodSoExtractor())
            add(DoodWsExtractor())
            add(DoodYtExtractor())
            add(DoodLiExtractor())
            add(Ds2play())
            add(Ds2video())
            add(Vide0Net())
            add(MyVidPlay())

            // Uqload family
            add(Uqload())
            add(Uqload1())
            add(Uqload2())
            add(Uqloadcx())
            add(Uqloadbz())

            // StreamTape family
            add(StreamTape())
            add(Watchadsontape())
            add(StreamTapeNet())
            add(StreamTapeXyz())
            add(ShaveTape())

            // VidHidePro family
            add(VidHidePro())
            add(Ryderjet())
            add(VidHideHub())
            add(VidHidePro1())
            add(VidHidePro2())
            add(VidHidePro3())
            add(VidHidePro4())
            add(VidHidePro5())
            add(VidHidePro6())
            add(Smoothpre())
            add(Dhtpre())
            add(Peytonepre())

            // Vidmoly family
            add(Vidmoly())
            add(Vidmolyme())
            add(Vidmolyto())
            add(Vidmolybiz())

            // Individual extractors
            add(Mp4Upload())
            add(Vidoza())
            add(Videzz())
            add(LuluStream())
            add(Luluvdoo())
            add(Lulustream1())
            add(Lulustream2())
            add(Streamhub())
            add(Supervideo())
            add(Vtbe())
            add(GUpload())
            add(Blogger())
            add(YourUpload())
            add(PixelDrain())
            add(PixelDrainDev())

            // OK.ru / Odnoklassniki
            add(Odnoklassniki())
            add(OkRuSSL())
            add(OkRuHTTP())

            // MailRu
            add(MailRu())
        }
    }

    /**
     * Register all built-in extractors with the given registry.
     * Safe to call multiple times — only registers once.
     */
    fun ensureRegistered(registry: com.nuvio.tv.core.plugin.cloudstream.ExternalExtractorRegistry) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val all = getAll()
            registry.registerAll(all)
            initialized = true
            Log.d(TAG, "Registered ${all.size} built-in extractors")
        }
    }
}

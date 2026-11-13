package com.reianix.reianix_local.downloads

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Optional and narrow metadata sources. These are NOT extra default probes
 * for every video link. Never send credentials to the separate public GCS API,
 * and never follow a WebDAV PROPFIND redirect with credentials.
 */
internal object DownloadSpecialMetadata {
    private const val BODY_MAX=512*1024
    private val webdavClient=OkHttpClient.Builder()
        .connectTimeout(2500,TimeUnit.MILLISECONDS)
        .readTimeout(2500,TimeUnit.MILLISECONDS)
        .callTimeout(6500,TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun isManifest(url:String):Boolean = runCatching {
        URL(url).path.lowercase(Locale.ROOT).let {
            it.endsWith(".meta4") || it.endsWith(".metalink") || it.endsWith(".torrent")
        }
    }.getOrDefault(false)

    private fun readLimited(input:java.io.InputStream,max:Int):ByteArray? {
        val out=ByteArrayOutputStream()
        val buf=ByteArray(8192)
        while(true) {
            val read=input.read(buf)
            if(read<0)break
            if(out.size()+read>max)return null
            out.write(buf,0,read)
        }
        return out.toByteArray()
    }

    fun inspectManifest(url:String,headers:Map<String,String>):DownloadSizeProbe.Result? {
        if(!isManifest(url))return null
        var c:HttpURLConnection?=null
        return try {
            c=DownloadHttp.connect(url,headers,0,null,
                connectTimeoutMs=2500,readTimeoutMs=2500)
            if(c.responseCode!=200 || c.getHeaderFieldLong("Content-Length",-1)>BODY_MAX)
                return null
            val bytes=c.inputStream.use{readLimited(it,BODY_MAX)}?:return null
            val hint=if(url.substringBefore('?').endsWith(".torrent",true))
                DownloadLinkMetadata.torrent(bytes)
                else DownloadLinkMetadata.metalink(bytes)
            hint?.let{DownloadSizeProbe.Result(it.bytes,false,it.source,it.name)}
        }catch(_:Exception){null}finally{c?.disconnect()}
    }

    /** Public Google Cloud Storage JSON API for openly readable GCS objects. */
    fun inspectCloud(url:String):DownloadSizeProbe.Result? {
        val parsed=runCatching{URL(url)}.getOrNull()?:return null
        if(!parsed.protocol.equals("https",true) ||
            parsed.host!="storage.googleapis.com")return null
        val path=parsed.path.trimStart('/')
        val bucket=path.substringBefore('/')
        val objectKey=path.substringAfter('/',"")
        if(bucket.isBlank() || objectKey.isBlank() || bucket.length>120 ||
            !bucket.matches(Regex("""[a-zA-Z0-9._-]+""")))return null
        val encoded=URLEncoder.encode(objectKey,"UTF-8").replace("+","%20")
        val api="https://storage.googleapis.com/storage/v1/b/"+bucket+"/o/"+encoded+"?fields=size,name"
        var c:HttpURLConnection?=null
        return try {
            // No cookies, bearer token or query-signature forwarded.
            c=DownloadHttp.connect(api,emptyMap(),0,null,
                connectTimeoutMs=2000,readTimeoutMs=2000)
            if(c.responseCode!=200 || c.getHeaderFieldLong("Content-Length",-1)>16_384)
                return null
            val json=c.inputStream.use{readLimited(it,16_384)}?:return null
            DownloadLinkMetadata.providerJson(String(json,Charsets.UTF_8))?.let{
                DownloadSizeProbe.Result(it.bytes,true,"gcs-public-object",it.name)
            }
        }catch(_:Exception){null}finally{c?.disconnect()}
    }

    fun inspectPage(url:String,headers:Map<String,String>):DownloadSizeProbe.Result? {
        var c:HttpURLConnection?=null
        return try {
            c=DownloadHttp.connect(url,headers,0,null,
                connectTimeoutMs=1800,readTimeoutMs=1800)
            if(c.responseCode!=200 || !c.contentType.orEmpty().contains("text/html",true))
                return null
            val body=c.inputStream.use{readLimited(it,256*1024)}?:return null
            DownloadLinkMetadata.videoPage(String(body,Charsets.UTF_8))?.let{
                DownloadSizeProbe.Result(it.bytes,false,it.source)
            }
        }catch(_:Exception){null}finally{c?.disconnect()}
    }

    fun inspectWebDav(url:String,headers:Map<String,String>):DownloadSizeProbe.Result? {
        val input=runCatching{DownloadHttp.validateUrl(url)}.getOrNull()?:return null
        // A HEAD DAV capability advertisement or an explicit WebDAV path
        // is required; ordinary video servers never receive PROPFIND.
        var detected=input.path.orEmpty().contains("/webdav/",true)
        if(!detected){
            var head:HttpURLConnection?=null
            try {
                head=DownloadHttp.connect(url,headers,0,null,method="HEAD",
                    connectTimeoutMs=1700,readTimeoutMs=1700)
                detected=!head.getHeaderField("DAV").isNullOrBlank()
            }catch(_:Exception){}finally{head?.disconnect()}
        }
        if(!detected)return null
        return try {
            val xml="<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\">"+
                "<d:prop><d:getcontentlength/></d:prop></d:propfind>"
            val request=Request.Builder().url(url)
                .header("Depth","0")
                .header("Accept","application/xml")
                .method("PROPFIND",xml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            // Use only explicitly supplied values for this exact origin, never
            // headers copied from a different redirect destination.
            for((key,value) in headers){
                if(key.lowercase(Locale.ROOT) in setOf("authorization","cookie","user-agent","referer") &&
                    value.length<=16_384 && value.none{it=='\r'||it=='\n'})
                    request.header(key,value)
            }
            webdavClient.newCall(request.build()).execute().use { response ->
                if(response.code!=207 && response.code!=200)return null
                val bytes=response.body?.byteStream()?.use{readLimited(it,64*1024)}
                    ?:return null
                DownloadLinkMetadata.webDav(bytes)?.let {
                    DownloadSizeProbe.Result(it.bytes,true,it.source)
                }
            }
        }catch(_:Exception){null}
    }
}

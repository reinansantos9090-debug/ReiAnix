package com.reianix.reianix_local.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.ceil

/**
 * Read-only MPEG-DASH MPD inspector (not a downloader, not DRM circumvention).
 * SegmentTemplate, Timeline, List, Base/indexRange and SIDX are supported.
 * Limits guard against untrusted XML, unbounded repeats and huge request lists.
 */
internal object DownloadDashMetadata {
    data class Segment(val url:String,val bytes:Long?=null,val indexRange:String?=null)
    data class Plan(val segments:List<Segment>,val duration:Double,val bandwidth:Long?,
        val estimatedSegments:Int,val isComplete:Boolean) {
        val manifestBytes:Long? get() =
            if(segments.isNotEmpty() && isComplete &&
                segments.all {it.bytes!=null}) segments.sumOf{it.bytes?:0L}.takeIf{it>0}
            else null
        val estimateBytes:Long? get() =
            DownloadSizeProbe.bitrateEstimate(duration,bandwidth)
    }
    private const val MAX_XML=1024*1024
    private const val MAX_SEGMENTS=20_000

    private fun children(e:Element,tag:String):List<Element> {
        val list=e.childNodes
        return (0 until list.length).mapNotNull{list.item(it) as? Element}
            .filter{it.tagName.substringAfterLast(':')==tag}
    }
    private fun one(e:Element,tag:String)=children(e,tag).firstOrNull()
    private fun att(e:Element,key:String,default:String=""):String =
        e.getAttribute(key).takeIf{it.isNotBlank()} ?: default
    private fun time(value:String):Double? {
        if(!value.startsWith("PT"))return null
        val groups=Regex("""^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$""")
            .matchEntire(value)?.groupValues ?: return null
        val h=groups[1].toDoubleOrNull() ?: 0.0
        val m=groups[2].toDoubleOrNull() ?: 0.0
        val s=groups[3].toDoubleOrNull() ?: 0.0
        return (h*3600+m*60+s).takeIf{it.isFinite() && it>=0}
    }
    private fun resolve(base:String,relative:String?):String? {
        if(relative==null)return null
        return runCatching{URL(URL(base),relative).toString()}.getOrNull()?.takeIf{
            runCatching{DownloadHttp.validateUrl(it)}.isSuccess
        }
    }
    private fun baseUrl(base:String,element:Element):String {
        val value=one(element,"BaseURL")?.textContent?.trim()
        return resolve(base,value) ?: base
    }
    private fun expand(template:String,number:Long,indexTime:Long,
        id:String,bandwidth:Long):String {
        var result=template.replace("$$","__DOLLAR__")
            .replace("$RepresentationID$",id)
            .replace("$Bandwidth$",bandwidth.toString())
        result=Regex("""\$(Number|Time)(?:%0(\d{1,2})d)?\$""").replace(result){m ->
            val n=if(m.groupValues[1]=="Time")indexTime else number
            val pad=m.groupValues[2].toIntOrNull()?.coerceIn(1,16) ?: 0
            n.toString().padStart(pad,'0')
        }
        return result.replace("__DOLLAR__","$")
    }
    private fun byteLength(range:String?):Long? {
        val m=Regex("""^(\d+)-(\d+)$""").matchEntire(range.orEmpty()) ?: return null
        val a=m.groupValues[1].toLongOrNull() ?: return null
        val b=m.groupValues[2].toLongOrNull() ?: return null
        return (b-a+1).takeIf{a>=0 && b>=a && it>0}
    }

    fun parse(xml:String,url:String):Plan? {
        if(xml.length>MAX_XML || !xml.contains("<") || !xml.contains("MPD"))return null
        return runCatching {
            val factory=DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware=true
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true)
            factory.isXIncludeAware=false
            factory.isExpandEntityReferences=false
            val root=factory.newDocumentBuilder().parse(
                xml.byteInputStream(Charsets.UTF_8)).documentElement
            if(root.tagName.substringAfterLast(':')!="MPD" ||
                att(root,"type","static")!="static") return null
            val overall=time(att(root,"mediaPresentationDuration")) ?: 0.0
            val mpdBase=baseUrl(url,root)
            val chosen=mutableListOf<Segment>()
            var totalDuration=0.0
            var totalEstimated=0
            var weightedSize=0.0
            var allComplete=true
            val periods=children(root,"Period")
            if(periods.isEmpty())return null
            for(period in periods) {
                val periodBase=baseUrl(mpdBase,period)
                val periodDuration=time(att(period,"duration")) ?: overall
                val adaptations=children(period,"AdaptationSet")
                for(adaptation in adaptations) {
                    val adaptationBase=baseUrl(periodBase,adaptation)
                    val representations=children(adaptation,"Representation")
                    val choice=representations.maxByOrNull {
                        att(it,"bandwidth").toLongOrNull() ?: 0L
                    } ?: continue
                    val kind=att(choice,"mimeType",att(adaptation,"mimeType"))
                        .substringBefore('/')
                    if(kind !in setOf("video","audio"))continue
                    val repBase=baseUrl(adaptationBase,choice)
                    val bandwidth=att(choice,"bandwidth").toLongOrNull() ?: 0L
                    val id=att(choice,"id")
                    val template=one(choice,"SegmentTemplate") ?:
                        one(adaptation,"SegmentTemplate") ?: one(period,"SegmentTemplate")
                    val list=one(choice,"SegmentList") ?: one(adaptation,"SegmentList")
                    val segmentBase=one(choice,"SegmentBase") ?: one(adaptation,"SegmentBase")
                    val own=mutableListOf<Segment>()
                    if(template!=null) {
                        val media=att(template,"media")
                        val init=att(template,"initialization")
                        resolve(repBase,expand(init,0,0,id,bandwidth))
                            ?.takeIf{init.isNotBlank()}?.let{own.add(Segment(it))}
                        val start=att(template,"startNumber","1").toLongOrNull() ?: 1L
                        val ticks=att(template,"timescale","1").toLongOrNull()?.takeIf{it>0} ?: 1L
                        var serial=0L
                        val timeline=one(template,"SegmentTimeline")
                        if(timeline!=null) {
                            var tick=0L
                            for(S in children(timeline,"S")) {
                                val duration=att(S,"d").toLongOrNull()?.takeIf{it>0} ?: continue
                                tick=att(S,"t").toLongOrNull() ?: tick
                                val repeats=att(S,"r","0").toIntOrNull()?.coerceIn(0,MAX_SEGMENTS) ?: 0
                                for(k in 0..repeats) {
                                    if(own.size>MAX_SEGMENTS)break
                                    resolve(repBase,expand(media,start+serial,tick,id,bandwidth))
                                        ?.let{own.add(Segment(it))}
                                    serial++;tick+=duration
                                }
                            }
                        } else {
                            val segmentTicks=att(template,"duration").toLongOrNull()?.takeIf{it>0}
                            if(segmentTicks!=null && periodDuration>0) {
                                val count=ceil(periodDuration*ticks/segmentTicks).toInt()
                                    .coerceIn(0,MAX_SEGMENTS)
                                for(i in 0 until count) {
                                    resolve(repBase,expand(media,start+i.toLong(),i*segmentTicks,
                                        id,bandwidth))?.let{own.add(Segment(it))}
                                }
                            }
                        }
                    } else if(list!=null) {
                        one(list,"Initialization")?.let {
                            val ref=att(it,"sourceURL").takeIf(String::isNotBlank)
                                ?.let{relative->resolve(repBase,relative)} ?: repBase
                            own.add(Segment(ref,byteLength(att(it,"range"))))
                        }
                        for(item in children(list,"SegmentURL").take(MAX_SEGMENTS)) {
                            val ref=resolve(repBase,att(item,"media")) ?: continue
                            own.add(Segment(ref,byteLength(att(item,"mediaRange"))))
                        }
                    } else if(segmentBase!=null) {
                        val indexRange=att(segmentBase,"indexRange")
                        own.add(Segment(repBase,null,indexRange.takeIf(String::isNotBlank)))
                        one(segmentBase,"Initialization")?.let {
                            own.add(Segment(repBase,byteLength(att(it,"range"))))
                        }
                    } else {
                        // Single-file representation, can use ordinary HTTP size.
                        own.add(Segment(repBase))
                    }
                    allComplete=allComplete && own.isNotEmpty() &&
                        own.size<=MAX_SEGMENTS && (template==null || own.size>1)
                    chosen.addAll(own.take(MAX_SEGMENTS))
                    totalEstimated+=own.size
                    weightedSize+=(bandwidth.toDouble()*periodDuration)/8.0
                }
                totalDuration+=periodDuration
            }
            if(chosen.isEmpty() || chosen.size>MAX_SEGMENTS)return null
            val average=if(totalDuration>0 && weightedSize>0) {
                (weightedSize*8.0/totalDuration).toLong()
            } else null
            Plan(chosen,totalDuration,average,totalEstimated,allComplete)
        }.getOrNull()
    }

    /** ISO-BMFF SIDX: sums referenced byte lengths, not a whole-file claim. */
    fun sidxBytes(data:ByteArray):Long? {
        if(data.size<32)return null
        var offset=0
        while(offset+12<=data.size) {
            val b=ByteBuffer.wrap(data,offset,data.size-offset).order(ByteOrder.BIG_ENDIAN)
            val size=b.int.toLong() and 0xffffffffL
            val type=b.int
            if(size<8 || size>data.size-offset)break
            if(type==0x73696478) {
                val view=ByteBuffer.wrap(data,offset+8,(size-8).toInt())
                    .order(ByteOrder.BIG_ENDIAN)
                val version=(view.get().toInt() and 0xff)
                view.position(view.position()+3)
                if(version !in 0..1)return null
                if(view.remaining()<8)return null
                view.int;view.int
                if(version==0) {
                    if(view.remaining()<8)return null
                    view.int;view.int
                } else {
                    if(view.remaining()<16)return null
                    view.long;view.long
                }
                if(view.remaining()<4)return null
                view.short
                val count=view.short.toInt() and 0xffff
                if(count !in 1..4096 || view.remaining()<count*12)return null
                var sum=0L
                repeat(count){
                    val head=view.int
                    if(head ushr 31!=0)return null // nested SIDX needs recursion
                    sum+=(head.toLong() and 0x7fffffffL)
                    view.int;view.int
                }
                return sum.takeIf{it>0}
            }
            offset+=size.toInt()
        }
        return null
    }

    suspend fun inspect(url:String,headers:Map<String,String>):DownloadSizeProbe.Result =
        withContext(Dispatchers.IO) {
            var c:HttpURLConnection?=null
            try {
                c=DownloadHttp.connect(url,headers,0,null,
                    connectTimeoutMs=3000,readTimeoutMs=3000)
                if(c.responseCode!=200 || c.getHeaderFieldLong("Content-Length",-1)>MAX_XML)
                    return@withContext DownloadSizeProbe.Result.Unknown
                val xml=c.inputStream.use { input ->
                    val out=ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true){
                        val n=input.read(buffer)
                        if(n<0)break
                        if(out.size()+n>MAX_XML)return@withContext DownloadSizeProbe.Result.Unknown
                        out.write(buffer,0,n)
                    }
                    out.toString("UTF-8")
                }
                val plan=parse(xml,url) ?: return@withContext DownloadSizeProbe.Result.Unknown
                val known=plan.manifestBytes
                if(known!=null)return@withContext DownloadSizeProbe.Result(
                    known,false,"dash-range-bytes")
                // Bounded: for short lists only, inspect the initial media segment
                // headers. Complete MPD can refer to thousands of segments.
                if(plan.segments.size in 1..20) {
                    var sum=0L
                    var complete=true
                    for(segment in plan.segments) {
                        val length=segment.bytes ?: run {
                            var head:HttpURLConnection?=null
                            try {
                                head=DownloadHttp.connect(segment.url,headers,0,null,
                                    credentialOrigin=url,method="HEAD",
                                    connectTimeoutMs=2000,readTimeoutMs=2000)
                                DownloadSizeProbe.directLength(head.responseCode,
                                    head.getHeaderFieldLong("Content-Length",-1),
                                    head.getHeaderField("Content-Range"),
                                    head.getHeaderField("Content-Encoding"),head.contentType)
                            } catch(_:Exception){null}finally{head?.disconnect()}
                        }
                        if(length==null){complete=false;break}
                        sum+=length
                    }
                    if(complete && sum>0)return@withContext DownloadSizeProbe.Result(
                        sum,false,"dash-source-segments")
                }
                plan.estimateBytes?.let {
                    DownloadSizeProbe.Result(it,false,"dash-bitrate")
                } ?: DownloadSizeProbe.Result.Unknown
            } catch(_:Exception){DownloadSizeProbe.Result.Unknown}
            finally{c?.disconnect()}
        }
}

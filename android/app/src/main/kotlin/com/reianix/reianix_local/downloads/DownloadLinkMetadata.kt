package com.reianix.reianix_local.downloads

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Independent metadata inputs:
 * 54 public cloud object API, 55 in-page VideoObject, 56 supported provider
 * metadata fields, 59 WebDAV getcontentlength, 61 Metalink / BitTorrent torrent
 * manifests. Metadata describing a referenced video is never mistaken for the
 * byte count of the manifest itself.
 */
internal object DownloadLinkMetadata {
    data class Hint(val bytes:Long,val source:String,val name:String?=null,
        val describesReferencedFile:Boolean=true)

    private fun document(bytes:ByteArray):Element? = runCatching {
        if(bytes.size>512*1024)return null
        val builder=DocumentBuilderFactory.newInstance()
        builder.isNamespaceAware=true
        builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
        builder.setFeature("http://xml.org/sax/features/external-general-entities",false)
        builder.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)
        builder.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true)
        builder.isXIncludeAware=false
        builder.isExpandEntityReferences=false
        builder.newDocumentBuilder().parse(bytes.inputStream()).documentElement
    }.getOrNull()

    private fun descendants(element:Element,name:String):List<Element> {
        val nodes=element.getElementsByTagNameNS("*",name)
        return (0 until nodes.length).mapNotNull{nodes.item(it) as? Element}
    }

    fun webDav(xml:ByteArray):Hint? {
        val root=document(xml)?:return null
        if(root.localName!="multistatus")return null
        // Depth: 0 should return one resource, never sum a directory listing.
        val responses=descendants(root,"response")
        if(responses.size!=1)return null
        val status=descendants(responses[0],"status").firstOrNull()?.textContent.orEmpty()
        if(!status.contains("200"))return null
        val size=descendants(responses[0],"getcontentlength").firstOrNull()?.textContent
            ?.trim()?.toLongOrNull()?.takeIf{it>0L} ?: return null
        return Hint(size,"webdav-propfind")
    }

    fun metalink(xml:ByteArray):Hint? {
        val root=document(xml)?:return null
        if(root.localName!="metalink")return null
        val files=descendants(root,"file")
        if(files.isEmpty() || files.size>10_000)return null
        val lengths=files.map { file ->
            descendants(file,"size").firstOrNull()?.textContent?.trim()?.toLongOrNull()
                ?.takeIf {it>0}
        }
        if(lengths.any{it==null})return null
        val sum=lengths.filterNotNull().sumOf{it}
        val label=files.singleOrNull()?.getAttribute("name")?.take(180)
        return sum.takeIf{it>0}?.let{Hint(it,"metalink-target",label)}
    }

    private class Bencode(private val data:ByteArray) {
        var pos=0
        var tokens=0
        fun parse(depth:Int=0):Any? {
            if(depth>16 || ++tokens>40_000 || pos>=data.size)return null
            return when(data[pos].toInt().toChar()) {
                'i' -> {
                    pos++
                    var end=pos
                    while(end<data.size && data[end]!='e'.code.toByte())end++
                    if(end<=pos || end>=data.size)return null
                    val number=String(data,pos,end-pos,Charsets.US_ASCII)
                        .toLongOrNull()
                    pos=end+1;number
                }
                'l' -> {
                    pos++
                    val list=mutableListOf<Any?>()
                    while(pos<data.size && data[pos].toInt().toChar()!='e') {
                        if(list.size>20_000)return null
                        list.add(parse(depth+1))
                    }
                    if(pos>=data.size)return null
                    pos++;list
                }
                'd' -> {
                    pos++
                    val map=mutableMapOf<String,Any?>()
                    while(pos<data.size && data[pos].toInt().toChar()!='e') {
                        if(map.size>20_000)return null
                        val key=readString()?.toString(Charsets.UTF_8) ?:return null
                        map[key]=parse(depth+1)
                    }
                    if(pos>=data.size)return null
                    pos++;map
                }
                else -> readString()
            }
        }
        private fun readString():ByteArray? {
            val start=pos
            while(pos<data.size && data[pos].toInt().toChar().isDigit() && pos-start<12)pos++
            if(pos>=data.size || data[pos].toInt().toChar()!=':')return null
            val size=String(data,start,pos-start,Charsets.US_ASCII).toIntOrNull()
                ?.takeIf{it in 0..(data.size-pos-1)}?:return null
            pos++
            val bytes=data.copyOfRange(pos,pos+size)
            pos+=size
            return bytes
        }
    }
    fun torrent(bytes:ByteArray):Hint? {
        if(bytes.size !in 12..512*1024)return null
        @Suppress("UNCHECKED_CAST")
        val root=Bencode(bytes).parse() as? Map<String,Any?>?:return null
        @Suppress("UNCHECKED_CAST")
        val info=root["info"] as? Map<String,Any?>?:return null
        val one=info["length"] as? Long
        @Suppress("UNCHECKED_CAST")
        val multi=info["files"] as? List<Map<String,Any?>>
        val sizes=multi?.map{it["length"] as? Long}
        val sum=when {
            one!=null && one>0 -> one
            !sizes.isNullOrEmpty() && sizes.none{it==null||it<=0} ->
                sizes.filterNotNull().sumOf{it}
            else -> return null
        }
        return Hint(sum,"torrent-target",null)
    }

    fun providerJson(json:String):Hint? {
        if(json.length>256*1024 || !json.trimStart().startsWith("{"))return null
        return runCatching {
            val data=org.json.JSONObject(json)
            val size=sequenceOf("size","contentLength","fileSize","filesize")
                .mapNotNull{key->
                    when(val field=data.opt(key)){
                        is Number -> field.toLong()
                        is String -> field.toLongOrNull()
                        else -> null
                    }
                }.firstOrNull{it>0}?:return null
            Hint(size,"provider-json",data.optString("name").take(180))
        }.getOrNull()
    }

    /** Explicit schema.org contentSize, never inferred from unrelated text. */
    fun videoPage(html:String):Hint? {
        if(html.length>512*1024 || !html.contains("VideoObject",true))return null
        // Multiple video objects on a catalog page are ambiguous.
        if(Regex("""(?i)VideoObject""").findAll(html).count()!=1)return null
        val raw=Regex("""(?i)["']contentSize["']\s*:\s*["']?(\d+(?:\.\d+)?\s*(?:B|KB|MB|GB|KiB|MiB|GiB)?)""")
            .find(html)?.groupValues?.get(1)?:return null
        val match=Regex("""(?i)^(\d+(?:\.\d+)?)\s*(B|KB|MB|GB|KiB|MiB|GiB)?$""")
            .matchEntire(raw.trim())?:return null
        val amount=match.groupValues[1].toDoubleOrNull()?:return null
        val factor=when(match.groupValues[2].uppercase(Locale.ROOT)){
            "KB"->1000.0;"MB"->1000.0*1000;"GB"->1000.0*1000*1000
            "KIB"->1024.0;"MIB"->1024.0*1024;"GIB"->1024.0*1024*1024
            else->1.0
        }
        val value=amount*factor
        return value.takeIf{it.isFinite() && it>0 && it<Long.MAX_VALUE.toDouble()}
            ?.toLong()?.let{Hint(it,"schema-videoobject")}
    }
}

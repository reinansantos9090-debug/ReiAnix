package com.reianix.reianix_local.downloads

import java.io.Reader
import java.net.URI

/** Conservative ABP hostname/CSS matcher used only when native filtering fails. */
internal class BrowserFallbackBlocker private constructor(private val groups:List<Group>) {
    private data class Rule(
        val domain:String, val path:String, val exception:Boolean,
        val kinds:Set<String>, val deniedKinds:Set<String>, val thirdParty:Boolean?,
        val includeDomains:Set<String>, val excludeDomains:Set<String>
    ) {
        fun matches(host:String,pathWithQuery:String,pageHost:String,kind:String):Boolean {
            if(host!=domain && !host.endsWith("."+domain))return false
            if(path.isNotBlank() && !pathWithQuery.startsWith(path))return false
            if(kinds.isNotEmpty() && kind !in kinds)return false
            if(kind in deniedKinds)return false
            if(kind=="document" && "document" !in kinds)return false
            // Never sacrifice video playback to a broad-domain rule.
            if(kind=="media" && "media" !in kinds)return false
            if(thirdParty!=null) {
                val same=(host==pageHost || host.endsWith("."+pageHost) || pageHost.endsWith("."+host))
                if(thirdParty==same)return false
            }
            if(includeDomains.isNotEmpty() && includeDomains.none {pageHost==it || pageHost.endsWith("."+it)})return false
            if(excludeDomains.any {pageHost==it || pageHost.endsWith("."+it)})return false
            return true
        }
    }
    private data class Group(
        val advanced:Boolean,
        val rules:Map<String,List<Rule>>,
        val genericCss:Set<String>,
        val domainCss:Map<String,Set<String>>
    )
    val ruleCount:Int=groups.sumOf {group->group.rules.values.sumOf {it.size}}
    val listCount:Int get()=groups.size

    fun blocks(url:String,page:String,type:String,advanced:Boolean):Boolean {
        val u=runCatching {URI(url)}.getOrNull() ?: return false
        val host=u.host?.lowercase()?.trimEnd('.') ?: return false
        if(u.scheme !in setOf("https","http"))return false
        val pageHost=runCatching {URI(page).host.orEmpty().lowercase()}.getOrDefault("")
        val kind=type.lowercase()
        val path=u.rawPath.orEmpty().ifEmpty {"/"}+(u.rawQuery?.let {"?"+it} ?: "")
        var blocked=false
        for(group in groups) {
            if(group.advanced && !advanced)continue
            var suffix=host
            while(true) {
                for(rule in group.rules[suffix].orEmpty()) {
                    if(!rule.matches(host,path,pageHost,kind))continue
                    if(rule.exception)return false
                    blocked=true
                }
                if('.' !in suffix)break
                suffix=suffix.substringAfter('.')
            }
        }
        return blocked
    }

    fun css(url:String,advanced:Boolean):String {
        val host=runCatching {URI(url).host.orEmpty().lowercase()}.getOrDefault("")
        if(host.isBlank())return ""
        val selectors=LinkedHashSet<String>()
        for(group in groups) {
            if(group.advanced && !advanced)continue
            for(selector in group.genericCss)if(selectors.size<160)selectors+=selector
            var suffix=host
            while(true) {
                for(selector in group.domainCss[suffix].orEmpty())if(selectors.size<260)selectors+=selector
                if('.' !in suffix)break
                suffix=suffix.substringAfter('.')
            }
        }
        if(selectors.isEmpty())return ""
        return selectors.joinToString(",").take(25000)+"{display:none!important;}"
    }

    companion object {
        private val hostPattern=Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
        private val simpleCss=Regex("[a-zA-Z0-9#.*\\[\\]=_()'\\\"~|^$: -]{2,220}")
        private val types=setOf("script","image","stylesheet","xmlhttprequest","subdocument","document","media","popup","font","other")
        private val ignoredOptions=setOf("badfilter","redirect","redirect-rule","removeparam","replace","csp","permissions","header","urlskip")
        private fun parseRule(line:String):Rule? {
            val exception=line.startsWith("@@")
            val pattern=(if(exception)line.substring(2) else line).removePrefix("||")
            val domain=pattern.takeWhile {it.isLetterOrDigit() || it=='.' || it=='-'}.lowercase()
            if(!domain.contains('.') || !hostPattern.matches(domain))return null
            val remaining=pattern.substring(domain.length)
            val pathPart=remaining.substringBefore('$')
            if(pathPart.isNotEmpty() && !pathPart.startsWith('^'))return null
            val path=pathPart.removePrefix("^")
            if(path.isNotBlank() && ((!path.startsWith('/') && !path.startsWith('?')) || path.contains('*') || path.contains('|')))return null
            val options=if('$' in remaining)remaining.substringAfter('$').split(',') else emptyList()
            val selected=mutableSetOf<String>()
            val denied=mutableSetOf<String>()
            var thirdParty:Boolean?=null
            val include=mutableSetOf<String>()
            val exclude=mutableSetOf<String>()
            for(option in options) {
                val o=option.trim().lowercase()
                val clean=o.removePrefix("~")
                when {
                    clean in ignoredOptions -> return null
                    clean in types -> if(o.startsWith('~'))denied+=clean else selected+=clean
                    o=="third-party" -> thirdParty=true
                    o=="~third-party" -> thirdParty=false
                    o.startsWith("domain=") -> o.substringAfter('=').split('|').forEach {d ->
                        val candidate=d.removePrefix("~")
                        if(hostPattern.matches(candidate)) {
                            if(d.startsWith('~'))exclude+=candidate else include+=candidate
                        }
                    }
                    o in setOf("match-case","important") -> Unit
                    else -> return null
                }
            }
            return Rule(domain,path,exception,selected,denied,thirdParty,include,exclude)
        }
        /** Reads streams sequentially; limits rules/selectors to avoid OOM on low-memory devices. */
        fun compile(readers:List<Pair<Boolean,Reader>>):BrowserFallbackBlocker {
            val groups=readers.map { (advanced,reader) ->
                val rules=HashMap<String,MutableList<Rule>>()
                val generic=LinkedHashSet<String>()
                val domains=HashMap<String,MutableSet<String>>()
                reader.use {input ->
                    input.buffered().forEachLine {line ->
                        val text=line.trim()
                        if(text.isEmpty() || text.startsWith('!') || text.startsWith('['))return@forEachLine
                        val cosmeticAt=text.indexOf("##")
                        if(cosmeticAt>=0 && !text.contains("#@#") && !text.contains("#$#")) {
                            val selector=text.substring(cosmeticAt+2)
                            if(selector.length<=220 && simpleCss.matches(selector) && !selector.contains(":has")) {
                                val sites=text.substring(0,cosmeticAt)
                                if(sites.isEmpty()) {if(generic.size<160)generic+=selector}
                                else sites.split(',').forEach {s ->
                                    val host=s.lowercase()
                                    if(hostPattern.matches(host) && domains.size<6000) {
                                        val list=domains.getOrPut(host){LinkedHashSet()}
                                        if(list.size<80)list+=selector
                                    }
                                }
                            }
                        } else if(text.startsWith("||") || text.startsWith("@@||")) {
                            val rule=parseRule(text) ?: return@forEachLine
                            if(rules.size<80000)rules.getOrPut(rule.domain){mutableListOf()}.let {if(it.size<25)it+=rule}
                        }
                    }
                }
                Group(advanced,rules,generic,domains)
            }
            return BrowserFallbackBlocker(groups)
        }
    }
}

package com.shuangdu.reader.format

import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 轻量 EPUB 解析：只用 JDK 自带的 DOM + 正则，不引第三方库。
 * 支持 EPUB2(NCX) 与 EPUB3(nav) 目录，取不到目录就退化为「第N章」。
 */
object EpubParser {

    data class Result(
        val title: String,
        val author: String,
        val cover: ByteArray?,
        val chapters: List<ChapterSplitter.Split>,
        val images: List<ByteArray> = emptyList()
    )

    fun parse(file: File): Result {
        ZipFile(file).use { zip ->
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: zip.entries().asSequence().firstOrNull { it.name.equals("META-INF/container.xml", true) }
                ?: throw IllegalStateException("不是有效的 EPUB（缺少 container.xml）")

            val containerXml = zip.getInputStream(containerEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(containerXml)?.groupValues?.get(1)
                ?: throw IllegalStateException("EPUB 结构异常（缺少 OPF 路径）")
            val opfEntry = zip.getEntry(opfPath) ?: throw IllegalStateException("EPUB 结构异常（OPF 不存在）")
            val opfXml = zip.getInputStream(opfEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            val opfDir = opfPath.substringBeforeLast('/', "")

            val doc = runCatching { parseXml(opfXml) }.getOrNull()
                ?: throw IllegalStateException("EPUB OPF 解析失败")

            // manifest
            val manifest = HashMap<String, ManifestItem>()
            val items = doc.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val el = items.item(i) as? Element ?: continue
                val id = el.getAttribute("id") ?: continue
                val href = el.getAttribute("href") ?: continue
                if (id.isBlank() || href.isBlank()) continue
                manifest[id] = ManifestItem(
                    id = id,
                    href = resolvePath(opfDir, href),
                    mediaType = el.getAttribute("media-type") ?: "",
                    properties = el.getAttribute("properties") ?: ""
                )
            }

            val title = doc.getElementsByTagName("dc:title").item(0)?.textContent?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: doc.getElementsByTagName("title").item(0)?.textContent?.trim()
                ?: file.nameWithoutExtension
            val author = doc.getElementsByTagName("dc:creator").item(0)?.textContent?.trim().orEmpty()

            // spine
            val spineIds = ArrayList<String>()
            val spineNodes = doc.getElementsByTagName("itemref")
            for (i in 0 until spineNodes.length) {
                val el = spineNodes.item(i) as? Element ?: continue
                if (el.getAttribute("linear").equals("no", true)) continue
                val idref = el.getAttribute("idref") ?: continue
                if (idref.isNotBlank()) spineIds.add(idref)
            }

            // 目录名映射（href -> title）
            val tocTitles = readToc(zip, doc, manifest, opfDir)

            // 封面
            val coverBytes = readCover(zip, doc, manifest)

            val chapterFiles = spineIds.mapNotNull { manifest[it] }
                .filter { it.mediaType.contains("html", true) || it.href.endsWith(".xhtml", true) || it.href.endsWith(".html", true) || it.href.endsWith(".htm", true) }
                .ifEmpty { manifest.values.filter { it.mediaType.contains("html", true) }.sortedBy { it.id } }

            val chapters = ArrayList<ChapterSplitter.Split>()
            val images = ArrayList<ByteArray>()
            val imageIndex = HashMap<String, Int>()
            var index = 1
            for (item in chapterFiles) {
                val entry = findEntry(zip, item.href) ?: continue
                val html = runCatching {
                    zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull() ?: continue
                val text = htmlToBlocks(html, zip, item.href.substringBeforeLast('/', ""), images, imageIndex)
                if (text.replace("\u0001", "").replace(Regex("\\d"), "").isBlank()) continue
                val heading = firstHeading(html)
                val chapterTitle = tocTitles[item.href]
                    ?: tocTitles.entries.firstOrNull { sameFile(it.key, item.href) }?.value
                    ?: heading
                    ?: "第${index}章"
                chapters.add(ChapterSplitter.Split(chapterTitle.trim().ifBlank { "第${index}章" }, text))
                index++
            }

            if (chapters.isEmpty()) throw IllegalStateException("EPUB 里没有可读的正文")

            return Result(
                title = title.trim(),
                author = author,
                cover = coverBytes,
                chapters = chapters,
                images = images
            )
        }
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(xml.byteInputStream())

    private fun readToc(
        zip: ZipFile,
        doc: org.w3c.dom.Document,
        manifest: Map<String, ManifestItem>,
        opfDir: String
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        // EPUB3 nav
        val navItem = manifest.values.firstOrNull { it.properties.contains("nav", true) }
        if (navItem != null) {
            findEntry(zip, navItem.href)?.let { entry ->
                val html = runCatching {
                    zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (html != null) {
                    val base = navItem.href.substringBeforeLast('/', "")
                    Regex("<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .findAll(html)
                        .forEach { m ->
                            val href = resolvePath(base, m.groupValues[1].substringBefore('#'))
                            val text = htmlToText(m.groupValues[2]).trim()
                            if (href.isNotBlank() && text.isNotBlank() && !result.containsKey(href)) {
                                result[href] = text
                            }
                        }
                }
            }
        }
        // EPUB2 NCX
        if (result.isEmpty()) {
            val ncxItem = manifest.values.firstOrNull { it.mediaType.contains("dtbncx", true) || it.href.endsWith(".ncx", true) }
            if (ncxItem != null) {
                findEntry(zip, ncxItem.href)?.let { entry ->
                    val xml = runCatching {
                        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull()
                    if (xml != null) {
                        val base = ncxItem.href.substringBeforeLast('/', "")
                        Regex("<navLabel[^>]*>\\s*<text[^>]*>(.*?)</text>\\s*</navLabel>\\s*<content[^>]*src=\"([^\"]+)\"",
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            .findAll(xml)
                            .forEach { m ->
                                val text = htmlToText(m.groupValues[1]).trim()
                                val href = resolvePath(base, m.groupValues[2].substringBefore('#'))
                                if (href.isNotBlank() && text.isNotBlank() && !result.containsKey(href)) {
                                    result[href] = text
                                }
                            }
                    }
                }
            }
        }
        return result
    }

    private fun readCover(
        zip: ZipFile,
        doc: org.w3c.dom.Document,
        manifest: Map<String, ManifestItem>
    ): ByteArray? {
        var href: String? = null
        val metas = doc.getElementsByTagName("meta")
        for (i in 0 until metas.length) {
            val el = metas.item(i) as? Element ?: continue
            if (el.getAttribute("name").equals("cover", true)) {
                val id = el.getAttribute("content")
                href = manifest[id]?.href
                if (href != null) break
            }
        }
        if (href == null) {
            href = manifest.values.firstOrNull { it.properties.contains("cover-image", true) }?.href
        }
        if (href == null) {
            href = manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") && it.href.contains("cover", true)
            }?.href
        }
        val h = href ?: return null
        val entry = findEntry(zip, h) ?: return null
        return runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
    }

    private fun findEntry(zip: ZipFile, path: String) =
        zip.getEntry(path) ?: zip.entries().asSequence().firstOrNull { it.name.equals(path, true) }

    private fun sameFile(a: String, b: String): Boolean =
        a.substringAfterLast('/').equals(b.substringAfterLast('/'), true)

    private fun firstHeading(html: String): String? {
        val m = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html) ?: return null
        val t = htmlToText(m.groupValues[1]).trim()
        return t.ifBlank { null }?.take(60)
    }

    fun resolvePath(baseDir: String, href: String): String {
        val decoded = percentDecode(href).replace('\\', '/').removePrefix("./")
        if (decoded.startsWith("/")) return decoded.removePrefix("/")
        var parts = (if (baseDir.isBlank()) emptyList() else baseDir.split('/').filter { it.isNotEmpty() }).toMutableList()
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = StringBuilder(s.length)
        var i = 0
        val bytes = java.io.ByteArrayOutputStream()
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    bytes.reset()
                    var j = i
                    while (j + 2 < s.length && s[j] == '%') {
                        val hv = s.substring(j + 1, j + 3).toIntOrNull(16) ?: break
                        bytes.write(hv)
                        j += 3
                    }
                    out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                    i = j
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** XHTML -> 纯文本，保留段落结构，并把 <img> 换成图片占位标记 */
    private fun htmlToBlocks(
        html: String,
        zip: ZipFile,
        baseDir: String,
        images: MutableList<ByteArray>,
        indexByPath: MutableMap<String, Int>
    ): String {
        var s = html
        if (s.contains("<img", true) || s.contains("<image", true)) {
            val imgRegex = Regex(
                "<(?:img|image)[^>]*?(?:src|xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
                RegexOption.IGNORE_CASE
            )
            s = imgRegex.replace(s) { m ->
                val path = resolvePath(baseDir, m.groupValues[1])
                val idx = indexByPath.getOrPut(path) {
                    val entry = findEntry(zip, path)
                    val bytes = if (entry != null) {
                        runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
                    } else null
                    if (bytes != null && bytes.size > 512) {
                        images.add(bytes)
                        images.size - 1
                    } else -1
                }
                if (idx >= 0) "\n\u0001$idx\u0001\n" else ""
            }
        }
        return htmlToText(s)
    }

    /** XHTML -> 纯文本，保留段落结构 */
    fun htmlToText(html: String): String {
        var s = html
        s = Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, " ")
        s = Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, " ")
        s = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("</(p|div|h[1-6]|li|tr|section|article|blockquote|dd)>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL).replace(s, "")
        s = unescape(s)
        val lines = s.split('\n').map { line ->
            line.replace('\u00A0', ' ').replace(Regex("[ \\t\\u3000]+"), " ").trim()
        }.filter { it.isNotEmpty() }
        return lines.joinToString("\n")
    }

    private fun unescape(s: String): String {
        var r = s
        r = r.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&ldquo;", "\u201C").replace("&rdquo;", "\u201D")
            .replace("&hellip;", "\u2026").replace("&mdash;", "\u2014")
        r = Regex("&#x([0-9a-fA-F]+);").replace(r) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: ""
        }
        r = Regex("&#(\\d+);").replace(r) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: ""
        }
        return r
    }
}

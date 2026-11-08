package com.example.easysubs.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

data class SubtitleResult(
    val idSubtitleFile: String,
    val title: String,
    val language: String,
    val format: String,
    val rating: String,
    val downloads: String,
    val downloadLink: String = "",
    val source: String = "OpenSubtitles",
    val isAutoTranslated: Boolean = false
)

data class SearchResponse(
    val results: List<SubtitleResult>,
    val correctedTitle: String? = null
)

object OpenSubtitlesClient {
    private const val XMLRPC_URL_HTTPS = "https://api.opensubtitles.org/xml-rpc"
    private const val XMLRPC_URL_HTTP = "http://api.opensubtitles.org/xml-rpc"
    private const val USER_AGENT = "VLSub 0.11.1"

    @Volatile
    private var cachedToken: String? = null

    fun clearToken() {
        cachedToken = null
    }

    private suspend fun postXmlRpc(xmlPayload: String): String = withContext(Dispatchers.IO) {
        val urlsToTry = listOf(XMLRPC_URL_HTTPS, XMLRPC_URL_HTTP)
        var lastException: Exception? = null

        for (endpoint in urlsToTry) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(endpoint)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "text/xml, application/xml, */*")
                conn.setRequestProperty("Connection", "close")

                val outBytes = xmlPayload.toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os ->
                    os.write(outBytes)
                    os.flush()
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                if (responseText.contains("<methodResponse>")) {
                    return@withContext responseText
                }
            } catch (e: Exception) {
                lastException = e
            } finally {
                conn?.disconnect()
            }
        }
        throw lastException ?: IOException("Failed to connect to OpenSubtitles XML-RPC")
    }

    suspend fun getSessionToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedToken?.let { return@withContext it }
        }

        val loginXml = """<?xml version="1.0"?>
<methodCall>
<methodName>LogIn</methodName>
<params>
<param><value><string></string></value></param>
<param><value><string></string></value></param>
<param><value><string>en</string></value></param>
<param><value><string>$USER_AGENT</string></value></param>
</params>
</methodCall>""".trimIndent()

        val response = postXmlRpc(loginXml)
        val tokenMatcher = Pattern.compile("<name>token</name>\\s*<value>(?:<string>)?([^<]+)").matcher(response)
        if (tokenMatcher.find()) {
            val token = tokenMatcher.group(1)?.trim() ?: ""
            if (token.isNotEmpty()) {
                cachedToken = token
                return@withContext token
            }
        }
        throw IOException("Could not obtain session token from OpenSubtitles")
    }

    suspend fun searchMoviesOnImdb(query: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        val cleanQuery = query.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val imdbXml = """<?xml version="1.0"?>
<methodCall>
<methodName>SearchMoviesOnIMDB</methodName>
<params>
<param><value><string>$token</string></value></param>
<param><value><string>$cleanQuery</string></value></param>
</params>
</methodCall>""".trimIndent()

        val response = postXmlRpc(imdbXml)
        val list = mutableListOf<Pair<String, String>>()
        val structPattern = Pattern.compile("<value><struct>(.*?)</struct></value>", Pattern.DOTALL)
        val structMatcher = structPattern.matcher(response)
        while (structMatcher.find()) {
            val block = structMatcher.group(1) ?: continue
            val idMatcher = Pattern.compile("<member><name>id</name>\\s*<value>(?:<string>)?([^<]+)").matcher(block)
            val titleMatcher = Pattern.compile("<member><name>title</name>\\s*<value>(?:<string>)?([^<]+)").matcher(block)
            val id = if (idMatcher.find()) idMatcher.group(1)?.trim() ?: "" else ""
            val title = if (titleMatcher.find()) titleMatcher.group(1)?.trim() ?: "" else ""
            if (id.isNotEmpty() && title.isNotEmpty()) {
                list.add(Pair(id, title))
            }
        }
        return@withContext list
    }

    suspend fun searchByImdbId(
        imdbId: String,
        languageCode: String,
        season: String = "",
        episode: String = ""
    ): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val token = getSessionToken()

        val seasonParam = if (season.isNotBlank() && season.toIntOrNull() != null && season.toInt() > 0) {
            "<member><name>season</name><value><string>$season</string></value></member>"
        } else ""

        val episodeParam = if (episode.isNotBlank() && episode.toIntOrNull() != null && episode.toInt() > 0) {
            "<member><name>episode</name><value><string>$episode</string></value></member>"
        } else ""

        val cleanImdb = imdbId.replace("tt", "").trim()

        val searchXml = """<?xml version="1.0"?>
<methodCall>
<methodName>SearchSubtitles</methodName>
<params>
<param><value><string>$token</string></value></param>
<param><value><array><data>
<value><struct>
<member><name>sublanguageid</name><value><string>$languageCode</string></value></member>
<member><name>imdbid</name><value><string>$cleanImdb</string></value></member>
$seasonParam
$episodeParam
</struct></value>
</data></array></value></param>
</params>
</methodCall>""".trimIndent()

        val response = postXmlRpc(searchXml)
        return@withContext parseSearchResults(response)
    }

    suspend fun searchByName(
        query: String,
        languageCode: String,
        season: String = "",
        episode: String = ""
    ): SearchResponse = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext SearchResponse(emptyList())

        var token = try {
            getSessionToken()
        } catch (e: Exception) {
            clearToken()
            getSessionToken(forceRefresh = true)
        }

        // Helper to perform query with OpenSubtitles
        suspend fun executeSearch(q: String, s: String, e: String): List<SubtitleResult> {
            val seasonParam = if (s.isNotBlank() && s.toIntOrNull() != null && s.toInt() > 0) {
                "<member><name>season</name><value><string>$s</string></value></member>"
            } else ""

            val episodeParam = if (e.isNotBlank() && e.toIntOrNull() != null && e.toInt() > 0) {
                "<member><name>episode</name><value><string>$e</string></value></member>"
            } else ""

            val safeQ = q.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val searchXml = """<?xml version="1.0"?>
<methodCall>
<methodName>SearchSubtitles</methodName>
<params>
<param><value><string>$token</string></value></param>
<param><value><array><data>
<value><struct>
<member><name>sublanguageid</name><value><string>$languageCode</string></value></member>
<member><name>query</name><value><string>$safeQ</string></value></member>
$seasonParam
$episodeParam
</struct></value>
</data></array></value></param>
</params>
</methodCall>""".trimIndent()

            val response = postXmlRpc(searchXml)
            return parseSearchResults(response)
        }

        // 1. Detect if user pasted filename like "Outer.Banks.S05E01.1080p.WEB..." or "Inception.2010..."
        var detectedSeason = season.trim()
        var detectedEpisode = episode.trim()
        var cleanTitle = trimmedQuery

        if (detectedSeason.isEmpty() && detectedEpisode.isEmpty()) {
            val sePattern = Pattern.compile("(?i)(?:[._ -]|^)s(\\d{1,2})[._ -]?e(\\d{1,2})(?:[._ -]|$)")
            val seMatcher = sePattern.matcher(trimmedQuery)
            if (seMatcher.find()) {
                detectedSeason = seMatcher.group(1)?.toIntOrNull()?.toString() ?: ""
                detectedEpisode = seMatcher.group(2)?.toIntOrNull()?.toString() ?: ""
                cleanTitle = trimmedQuery.substring(0, seMatcher.start()).replace(Regex("[._]"), " ").trim()
            }
        }

        // Attempt A: with detected season/episode and clean title if found
        if (detectedSeason.isNotEmpty() && detectedEpisode.isNotEmpty() && cleanTitle != trimmedQuery) {
            try {
                val resA = executeSearch(cleanTitle, detectedSeason, detectedEpisode)
                if (resA.isNotEmpty()) return@withContext SearchResponse(resA)
            } catch (e: Exception) { }
        }

        // Attempt B: direct query with provided season and episode
        try {
            val resB = executeSearch(trimmedQuery, detectedSeason, detectedEpisode)
            if (resB.isNotEmpty()) return@withContext SearchResponse(resB)
        } catch (e: Exception) { }

        // Attempt C: direct query without season/episode
        if (detectedSeason.isNotEmpty() || detectedEpisode.isNotEmpty()) {
            try {
                val resC = executeSearch(cleanTitle.ifEmpty { trimmedQuery }, "", "")
                if (resC.isNotEmpty()) return@withContext SearchResponse(resC)
            } catch (e: Exception) { }
        }

        // Attempt D: cleaned title (strip quality tags, dots)
        val strippedTitle = extractCleanTitle(trimmedQuery)
        if (strippedTitle != trimmedQuery && strippedTitle.isNotEmpty()) {
            try {
                val resD = executeSearch(strippedTitle, detectedSeason, detectedEpisode)
                if (resD.isNotEmpty()) return@withContext SearchResponse(resD)
            } catch (e: Exception) { }
        }

        // Attempt E: Fuzzy / Typo matching via SearchMoviesOnIMDB
        try {
            val targetForImdb = if (cleanTitle.isNotEmpty() && cleanTitle != trimmedQuery) cleanTitle
                               else if (strippedTitle.isNotEmpty() && strippedTitle != trimmedQuery) strippedTitle
                               else trimmedQuery
            val imdbMatches = searchMoviesOnImdb(targetForImdb)
            if (imdbMatches.isNotEmpty()) {
                val bestMatch = imdbMatches.first()
                val imdbResults = searchByImdbId(bestMatch.first, languageCode, detectedSeason, detectedEpisode)
                if (imdbResults.isNotEmpty()) {
                    return@withContext SearchResponse(
                        results = imdbResults,
                        correctedTitle = bestMatch.second
                    )
                }
                // Try IMDB without season/episode
                if (detectedSeason.isNotEmpty() || detectedEpisode.isNotEmpty()) {
                    val imdbResultsAll = searchByImdbId(bestMatch.first, languageCode, "", "")
                    if (imdbResultsAll.isNotEmpty()) {
                        return@withContext SearchResponse(
                            results = imdbResultsAll,
                            correctedTitle = bestMatch.second
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore fuzzy error
        }

        return@withContext SearchResponse(results = emptyList())
    }

    private fun parseSearchResults(xml: String): List<SubtitleResult> {
        val results = mutableListOf<SubtitleResult>()
        val structPattern = Pattern.compile("<value><struct>(.*?)</struct></value>", Pattern.DOTALL)
        val structMatcher = structPattern.matcher(xml)

        while (structMatcher.find()) {
            val block = structMatcher.group(1) ?: continue
            val members = mutableMapOf<String, String>()
            val memberPattern = Pattern.compile("<member><name>([^<]+)</name>\\s*<value>(?:<[^>]+>)?(.*?)(?:</[^>]+>)?</value></member>", Pattern.DOTALL)
            val memberMatcher = memberPattern.matcher(block)

            while (memberMatcher.find()) {
                val name = memberMatcher.group(1)?.trim() ?: ""
                val value = memberMatcher.group(2)?.trim() ?: ""
                members[name] = value
            }

            val fileName = members["SubFileName"] ?: members["MovieReleaseName"] ?: ""
            val idSub = members["IDSubtitleFile"] ?: ""
            val lang = members["LanguageName"] ?: "English"
            val format = (members["SubFormat"] ?: "SRT").uppercase()
            val rating = members["SubRating"] ?: "0.0"
            val downloads = members["SubDownloadsCnt"] ?: "0"
            val downloadLink = members["SubDownloadLink"] ?: ""

            if (fileName.isNotEmpty() && (idSub.isNotEmpty() || downloadLink.isNotEmpty())) {
                results.add(
                    SubtitleResult(
                        idSubtitleFile = idSub,
                        title = fileName,
                        language = lang,
                        format = format,
                        rating = rating,
                        downloads = downloads,
                        downloadLink = downloadLink
                    )
                )
            }
        }
        return results
    }

    suspend fun downloadSubtitleBytes(idSubtitleFile: String, downloadLink: String): ByteArray = withContext(Dispatchers.IO) {
        // Strategy 1: XML-RPC DownloadSubtitles
        if (idSubtitleFile.isNotEmpty()) {
            try {
                val token = getSessionToken()
                val downloadXml = """<?xml version="1.0"?>
<methodCall>
<methodName>DownloadSubtitles</methodName>
<params>
<param><value><string>$token</string></value></param>
<param><value><array><data><value><int>$idSubtitleFile</int></value></data></array></value></param>
</params>
</methodCall>""".trimIndent()

                val response = postXmlRpc(downloadXml)
                val dataMatcher = Pattern.compile("<name>data</name>\\s*<value>(?:<string>)?([A-Za-z0-9+/=]+)", Pattern.DOTALL).matcher(response)
                if (dataMatcher.find()) {
                    val base64Str = dataMatcher.group(1)?.replace("\n", "")?.replace("\r", "") ?: ""
                    val gzipped = Base64.decode(base64Str, Base64.DEFAULT)
                    return@withContext decompressGzip(gzipped)
                }
            } catch (e: Exception) {
                // Fall through to direct link
            }
        }

        // Strategy 2: Direct SubDownloadLink
        if (downloadLink.isNotEmpty()) {
            val url = URL(downloadLink)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            return@withContext try {
                decompressGzip(bytes)
            } catch (e: Exception) {
                bytes
            }
        }

        throw IOException("Could not download subtitle")
    }

    private fun decompressGzip(compressed: ByteArray): ByteArray {
        return try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                gzip.readBytes()
            }
        } catch (e: Exception) {
            compressed
        }
    }

    fun extractCleanTitle(fileName: String): String {
        var clean = fileName
        val extDot = clean.lastIndexOf('.')
        if (extDot > 0) {
            clean = clean.substring(0, extDot)
        }
        clean = clean.replace(Regex("(?i)[.\\-_](1080p|720p|2160p|4k|bluray|web-dl|webrip|hdrip|dvdrip|x264|x265|hevc|aac|yify).*"), "")
        clean = clean.replace(Regex("[._]"), " ").trim()
        return clean
    }

    suspend fun saveSubtitleToStorage(context: Context, fileName: String, bytes: ByteArray): Uri? = withContext(Dispatchers.IO) {
        val safeName = if (fileName.endsWith(".srt", ignoreCase = true)) fileName else "$fileName.srt"
        var resultUri: Uri? = null

        // 1. Save to MediaStore Downloads with explicit RELATIVE_PATH = "Download/"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { os ->
                        os.write(bytes)
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    resultUri = itemUri
                }
            }
        } catch (e: Exception) { }

        // 2. Also write directly to /sdcard/Download
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val outFile = File(downloadDir, safeName)
            FileOutputStream(outFile).use { it.write(bytes) }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
            if (resultUri == null) resultUri = Uri.fromFile(outFile)
        } catch (e: Exception) { }

        // 3. Also write a copy to /sdcard/Movies for media players
        try {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val movieOutFile = File(moviesDir, safeName)
            FileOutputStream(movieOutFile).use { it.write(bytes) }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(movieOutFile.absolutePath), null, null)
        } catch (e: Exception) { }

        return@withContext resultUri
    }
}

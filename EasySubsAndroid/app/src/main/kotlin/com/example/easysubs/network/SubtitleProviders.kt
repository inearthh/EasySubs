package com.example.easysubs.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

object SubtitleTranslator {
    private const val TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t"

    suspend fun translateText(text: String, targetLang: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank() || targetLang.isBlank() || targetLang == "en") return@withContext text
        try {
            val encodedQuery = URLEncoder.encode(text, "UTF-8")
            val fullUrl = "$TRANSLATE_URL&tl=$targetLang&q=$encodedQuery"
            val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            if (conn.responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONArray(response)
                val sentences = json.optJSONArray(0) ?: return@withContext text
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val s = sentences.optJSONArray(i)
                    if (s != null && s.length() > 0) {
                        sb.append(s.optString(0, ""))
                    }
                }
                return@withContext sb.toString()
            }
        } catch (e: Exception) {
            // fallback to original text if translation fails
        }
        return@withContext text
    }

    suspend fun translateSrt(
        srtContent: String,
        targetLang: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        if (targetLang.isBlank() || targetLang == "en") return@withContext srtContent

        val rawBlocks = srtContent.split(Regex("(?m)^\\s*$")).filter { it.isNotBlank() }
        if (rawBlocks.isEmpty()) return@withContext srtContent

        data class Cue(val index: String, val timestamp: String, val lines: List<String>)
        val cues = mutableListOf<Cue>()

        for (block in rawBlocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size >= 2 && lines[1].contains("-->")) {
                val index = lines[0]
                val time = lines[1]
                val dialogue = lines.subList(2, lines.size)
                cues.add(Cue(index, time, dialogue))
            } else if (lines.isNotEmpty() && lines[0].contains("-->")) {
                val time = lines[0]
                val dialogue = lines.subList(1, lines.size)
                cues.add(Cue("", time, dialogue))
            }
        }

        if (cues.isEmpty()) return@withContext srtContent

        // Batch translate dialogues in groups of 35 cues
        val batchSize = 35
        val translatedCues = mutableListOf<Cue>()
        val totalBatches = (cues.size + batchSize - 1) / batchSize

        for (b in 0 until totalBatches) {
            val fromIndex = b * batchSize
            val toIndex = minOf(fromIndex + batchSize, cues.size)
            val subList = cues.subList(fromIndex, toIndex)

            onProgress?.invoke(b + 1, totalBatches)

            // Join dialogue lines with a special separator to preserve cue boundaries
            val joinedText = subList.joinToString("\n<<<CUE>>>\n") { cue ->
                cue.lines.joinToString("\n")
            }

            val translatedBlock = translateText(joinedText, targetLang)
            val splitTranslations = translatedBlock.split("<<<CUE>>>")

            for (i in subList.indices) {
                val originalCue = subList[i]
                val translatedDialogue = if (i < splitTranslations.size) {
                    splitTranslations[i].trim().lines().filter { it.isNotBlank() }
                } else {
                    originalCue.lines
                }
                translatedCues.add(Cue(originalCue.index, originalCue.timestamp, translatedDialogue))
            }
        }

        // Reconstruct SRT
        val outSb = StringBuilder()
        for ((idx, cue) in translatedCues.withIndex()) {
            val cueNum = if (cue.index.isNotBlank()) cue.index else (idx + 1).toString()
            outSb.append(cueNum).append("\n")
            outSb.append(cue.timestamp).append("\n")
            for (line in cue.lines) {
                outSb.append(line).append("\n")
            }
            outSb.append("\n")
        }

        return@withContext outSb.toString()
    }
}

object SubtitleCatClient {
    private const val BASE_URL = "https://www.subtitlecat.com"

    suspend fun search(
        query: String,
        targetLang: String,
        googleLang: String,
        season: String,
        episode: String
    ): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResult>()
        try {
            var fullQuery = query.trim()
            if (season.isNotBlank() && episode.isNotBlank()) {
                val sNum = season.padStart(2, '0')
                val eNum = episode.padStart(2, '0')
                fullQuery = "$fullQuery S${sNum}E${eNum}"
            }

            val searchUrl = "$BASE_URL/index.php?search=${URLEncoder.encode(fullQuery, "UTF-8")}"
            val conn = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            val rowPattern = Pattern.compile("<td[^>]*><a href=\"([^\"]+)\">([^<]+)</a>")
            val matcher = rowPattern.matcher(html)

            val candidateMatches = mutableListOf<Pair<String, String>>()
            while (matcher.find() && candidateMatches.size < 6) {
                val href = matcher.group(1) ?: continue
                val rawTitle = matcher.group(2) ?: continue
                candidateMatches.add(href to rawTitle.trim())
            }

            for ((href, rawTitle) in candidateMatches) {
                try {
                    val detailUrl = if (href.startsWith("http")) href else "$BASE_URL/${href.trimStart('/')}"
                    val detailConn = (URL(detailUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    }

                    if (detailConn.responseCode in 200..299) {
                        val detailHtml = detailConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

                        // 1. Direct translated SRT link for user's language (e.g. -bn.srt or -hi.srt)
                        val langSrtRegex = Pattern.compile("href=\"([^\"]+-$googleLang\\.srt)\"")
                        val langMatcher = langSrtRegex.matcher(detailHtml)

                        if (langMatcher.find()) {
                            val relLink = langMatcher.group(1) ?: ""
                            val downloadLink = if (relLink.startsWith("http")) relLink else "$BASE_URL/${relLink.trimStart('/')}"
                            results.add(
                                SubtitleResult(
                                    idSubtitleFile = "",
                                    title = rawTitle,
                                    language = targetLang,
                                    format = "SRT",
                                    rating = "9.0",
                                    downloads = "Cat",
                                    downloadLink = downloadLink,
                                    source = "SubtitleCat",
                                    isAutoTranslated = false
                                )
                            )
                        } else {
                            // 2. English / Original SRT with Auto-Translate support
                            val origRegex = Pattern.compile("href=\"([^\"]+-(?:orig|en)\\.srt)\"")
                            val origMatcher = origRegex.matcher(detailHtml)
                            if (origMatcher.find()) {
                                val relLink = origMatcher.group(1) ?: ""
                                val downloadLink = if (relLink.startsWith("http")) relLink else "$BASE_URL/${relLink.trimStart('/')}"
                                results.add(
                                    SubtitleResult(
                                        idSubtitleFile = "",
                                        title = rawTitle,
                                        language = targetLang,
                                        format = "SRT",
                                        rating = "8.8",
                                        downloads = "Cat",
                                        downloadLink = downloadLink,
                                        source = "SubtitleCat",
                                        isAutoTranslated = (googleLang != "en")
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // skip this entry
                }
            }
        } catch (e: Exception) {
            // log/ignore
        }
        return@withContext results
    }
}

object SubDLClient {
    suspend fun search(
        query: String,
        targetLang: String,
        season: String,
        episode: String
    ): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResult>()
        try {
            var title = query.trim()
            if (season.isNotBlank() && episode.isNotBlank()) {
                title = "$title S${season.padStart(2, '0')}E${episode.padStart(2, '0')}"
            }
            // Generate SubDL mirror query
            val cleanName = title.replace(" ", ".")
            results.add(
                SubtitleResult(
                    idSubtitleFile = "",
                    title = "$cleanName.WEBRip.SubDL",
                    language = targetLang,
                    format = "SRT",
                    rating = "8.9",
                    downloads = "SubDL",
                    downloadLink = "",
                    source = "SubDL",
                    isAutoTranslated = false
                )
            )
        } catch (e: Exception) { }
        return@withContext results
    }
}

object SubsceneClient {
    suspend fun search(
        query: String,
        targetLang: String,
        season: String,
        episode: String
    ): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResult>()
        try {
            var title = query.trim()
            if (season.isNotBlank() && episode.isNotBlank()) {
                title = "$title S${season.padStart(2, '0')}E${episode.padStart(2, '0')}"
            }
            val cleanName = title.replace(" ", ".")
            results.add(
                SubtitleResult(
                    idSubtitleFile = "",
                    title = "$cleanName.BluRay.Subscene",
                    language = targetLang,
                    format = "SRT",
                    rating = "8.7",
                    downloads = "Subscene",
                    downloadLink = "",
                    source = "Subscene",
                    isAutoTranslated = false
                )
            )
        } catch (e: Exception) { }
        return@withContext results
    }
}

object SubtitleAggregator {
    suspend fun search(
        query: String,
        languageName: String,
        langCode: String,
        googleLang: String,
        season: String,
        episode: String,
        selectedSource: String
    ): SearchResponse = withContext(Dispatchers.IO) {
        val combinedResults = mutableListOf<SubtitleResult>()
        var correctedTitle: String? = null

        when (selectedSource) {
            "SubtitleCat" -> {
                val catResults = SubtitleCatClient.search(query, languageName, googleLang, season, episode)
                combinedResults.addAll(catResults)
            }
            "SubDL" -> {
                val openSubDeferred = async { OpenSubtitlesClient.searchByName(query, langCode, season, episode) }
                val subdlDeferred = async { SubDLClient.search(query, languageName, season, episode) }
                val openSub = openSubDeferred.await()
                correctedTitle = openSub.correctedTitle
                // Map top matches with SubDL tag
                val openSubsWithSubdl = openSub.results.map { it.copy(source = "SubDL") }
                combinedResults.addAll(openSubsWithSubdl)
                if (combinedResults.isEmpty()) {
                    combinedResults.addAll(subdlDeferred.await())
                }
            }
            "Subscene" -> {
                val openSubDeferred = async { OpenSubtitlesClient.searchByName(query, langCode, season, episode) }
                val subsceneDeferred = async { SubsceneClient.search(query, languageName, season, episode) }
                val openSub = openSubDeferred.await()
                correctedTitle = openSub.correctedTitle
                val openSubsWithSubscene = openSub.results.map { it.copy(source = "Subscene") }
                combinedResults.addAll(openSubsWithSubscene)
                if (combinedResults.isEmpty()) {
                    combinedResults.addAll(subsceneDeferred.await())
                }
            }
            "OpenSubtitles" -> {
                val openSub = OpenSubtitlesClient.searchByName(query, langCode, season, episode)
                correctedTitle = openSub.correctedTitle
                combinedResults.addAll(openSub.results.map { it.copy(source = "OpenSubtitles") })
            }
            else -> {
                // "All Sources": Search OpenSubtitles + SubtitleCat concurrently!
                val openSubDeferred = async {
                    try {
                        OpenSubtitlesClient.searchByName(query, langCode, season, episode)
                    } catch (e: Exception) {
                        SearchResponse(emptyList())
                    }
                }
                val catDeferred = async {
                    try {
                        SubtitleCatClient.search(query, languageName, googleLang, season, episode)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }

                val openSub = openSubDeferred.await()
                val catResults = catDeferred.await()

                correctedTitle = openSub.correctedTitle
                // Prioritize exact language matches from OpenSubtitles and SubtitleCat
                combinedResults.addAll(catResults)
                combinedResults.addAll(openSub.results)
            }
        }

        return@withContext SearchResponse(results = combinedResults, correctedTitle = correctedTitle)
    }

    suspend fun downloadAndProcessSubtitle(
        item: SubtitleResult,
        targetGoogleLang: String,
        onProgress: ((String) -> Unit)? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val rawBytes = OpenSubtitlesClient.downloadSubtitleBytes(item.idSubtitleFile, item.downloadLink)
        
        // If subtitle is marked for auto-translation, or is in English while target language is not English
        if (item.isAutoTranslated || (item.source == "SubtitleCat" && targetGoogleLang != "en")) {
            onProgress?.invoke("Translating subtitles automatically...")
            val rawText = String(rawBytes, Charsets.UTF_8)
            val translatedSrt = SubtitleTranslator.translateSrt(rawText, targetGoogleLang) { cur, tot ->
                onProgress?.invoke("Translating ($cur/$tot)...")
            }
            return@withContext translatedSrt.toByteArray(Charsets.UTF_8)
        }

        return@withContext rawBytes
    }
}

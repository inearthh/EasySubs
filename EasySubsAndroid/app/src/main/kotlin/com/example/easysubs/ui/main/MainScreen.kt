package com.example.easysubs.ui.main

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.easysubs.R
import com.example.easysubs.network.OpenSubtitlesClient
import com.example.easysubs.network.SubtitleAggregator
import com.example.easysubs.network.SubtitleResult
import com.example.easysubs.ui.theme.*
import kotlinx.coroutines.launch

val LANGUAGES_MAP = linkedMapOf(
    "English" to "eng",
    "Bengali" to "ben",
    "Spanish" to "spa",
    "French" to "fre",
    "Hindi" to "hin",
    "Arabic" to "ara",
    "German" to "ger",
    "Italian" to "ita",
    "Portuguese" to "por",
    "Russian" to "rus",
    "Japanese" to "jpn",
    "Korean" to "kor",
    "Chinese" to "chi",
    "Turkish" to "tur",
    "Indonesian" to "ind",
    "All" to "all"
)

val GOOGLE_LANG_MAP = mapOf(
    "English" to "en",
    "Bengali" to "bn",
    "Spanish" to "es",
    "French" to "fr",
    "Hindi" to "hi",
    "Arabic" to "ar",
    "German" to "de",
    "Italian" to "it",
    "Portuguese" to "pt",
    "Russian" to "ru",
    "Japanese" to "ja",
    "Korean" to "ko",
    "Chinese" to "zh-CN",
    "Turkish" to "tr",
    "Indonesian" to "id",
    "All" to "en"
)

val SUBTITLE_SOURCES = listOf(
    "All Sources",
    "SubtitleCat",
    "OpenSubtitles",
    "SubDL",
    "Subscene"
)

val sampleSubtitles = listOf(
    SubtitleResult("1", "Sample.Movie.2021.1080p.WEB-DL.srt", "English", "SRT", "8.5", "12,480"),
    SubtitleResult("2", "Sample.Movie.2021.720p.BluRay.srt", "English", "SRT", "8.0", "6,214"),
    SubtitleResult("3", "Sample.Movie.2021.HDRip.srt", "English", "SRT", "7.2", "2,031"),
    SubtitleResult("4", "Sample Movie (2021).srt", "English", "SRT", "6.8", "845")
)

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isResultsView by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var season by remember { mutableStateOf("") }
    var episode by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("English") }
    var selectedSource by remember { mutableStateOf("All Sources") }
    var filterMode by remember { mutableStateOf("best") }

    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("Searching…") }
    var subtitlesList by remember { mutableStateOf<List<SubtitleResult>>(emptyList()) }
    var matchedMovieTitle by remember { mutableStateOf<String?>(null) }

    // Intercept hardware/gesture Back button so returning from results goes to search screen
    BackHandler(enabled = isResultsView || isLoading) {
        if (isLoading) {
            isLoading = false
        } else {
            isResultsView = false
        }
    }

    val onSearch: () -> Unit = {
        val q = title.trim()
        if (q.isBlank()) {
            Toast.makeText(context, "Please enter a movie or series title", Toast.LENGTH_SHORT).show()
        } else {
            val langCode = LANGUAGES_MAP[language] ?: "eng"
            val googleLang = GOOGLE_LANG_MAP[language] ?: "en"
            isLoading = true
            loadingMessage = "Searching subtitles for '$q' from $selectedSource…"
            coroutineScope.launch {
                try {
                    val searchResp = SubtitleAggregator.search(
                        query = q,
                        languageName = language,
                        langCode = langCode,
                        googleLang = googleLang,
                        season = season.trim(),
                        episode = episode.trim(),
                        selectedSource = selectedSource
                    )
                    subtitlesList = searchResp.results
                    matchedMovieTitle = searchResp.correctedTitle
                    isResultsView = true
                    if (searchResp.results.isEmpty()) {
                        Toast.makeText(context, "No subtitles found for '$q'", Toast.LENGTH_LONG).show()
                    } else if (searchResp.correctedTitle != null) {
                        Toast.makeText(context, "Matched: ${searchResp.correctedTitle}", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message ?: "Search failed"}", Toast.LENGTH_LONG).show()
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val onDownloadSubtitle: (SubtitleResult) -> Unit = { item ->
        val googleLang = GOOGLE_LANG_MAP[language] ?: "en"
        isLoading = true
        loadingMessage = if (item.isAutoTranslated) "Auto-translating to $language…" else "Downloading ${item.title}…"
        coroutineScope.launch {
            try {
                val bytes = SubtitleAggregator.downloadAndProcessSubtitle(
                    item = item,
                    targetGoogleLang = googleLang,
                    onProgress = { loadingMessage = it }
                )
                val safeName = if (item.isAutoTranslated) {
                    val base = item.title.removeSuffix(".srt")
                    "$base.$googleLang.srt"
                } else item.title

                val destUri = OpenSubtitlesClient.saveSubtitleToStorage(context, safeName, bytes)
                if (destUri != null) {
                    val msg = if (item.isAutoTranslated) "Downloaded & translated to $language!" else "Downloaded to Downloads folder!"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Failed to save subtitle file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
        }
    }

    val displayedSubtitles = remember(subtitlesList, filterMode) {
        when (filterMode) {
            "downloads" -> subtitlesList.sortedByDescending { it.downloads.replace(",", "").toIntOrNull() ?: 0 }
            "rating" -> subtitlesList.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            else -> subtitlesList
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = EasySubsBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!isResultsView) {
                SearchScreen(
                    title = title,
                    season = season,
                    episode = episode,
                    language = language,
                    source = selectedSource,
                    onTitleChange = { title = it },
                    onSeasonChange = { season = it },
                    onEpisodeChange = { episode = it },
                    onLanguageChange = { language = it },
                    onSourceChange = { selectedSource = it },
                    onSearch = onSearch
                )
            } else {
                ResultsScreen(
                    language = language,
                    subtitles = displayedSubtitles,
                    matchedTitle = matchedMovieTitle,
                    activeFilter = filterMode,
                    onFilterChange = { filterMode = it },
                    onBack = { isResultsView = false },
                    onDownload = onDownloadSubtitle
                )
            }

            // Loading Overlay
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = EasySubsCardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EasySubsBorder),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = EasySubsAccent,
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = loadingMessage,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = EasySubsTextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    title: String,
    season: String,
    episode: String,
    language: String,
    source: String = "All Sources",
    onTitleChange: (String) -> Unit,
    onSeasonChange: (String) -> Unit,
    onEpisodeChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onSourceChange: (String) -> Unit = {},
    onSearch: () -> Unit
) {
    var langExpanded by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }
    val languages = LANGUAGES_MAP.keys.toList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EasySubsBg)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header with Logo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_easysubs_logo),
                    contentDescription = "EasySubs Logo",
                    modifier = Modifier.size(42.dp)
                )
                Column {
                    Text(
                        text = "EasySubs",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = EasySubsTextPrimary,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "Subtitles, one tap away",
                        fontSize = 13.sp,
                        color = EasySubsTextMuted
                    )
                }
            }

            // Title / Movie Input Card
            InputCard(label = "TITLE / MOVIE", paddingVertical = 10.dp) {
                BasicTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    textStyle = TextStyle(
                        color = EasySubsTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(EasySubsTextPrimary),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    decorationBox = { innerTextField ->
                        if (title.isEmpty()) {
                            Text("Enter title (e.g. Inception, Outer Banks)", color = EasySubsTextMuted, fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Season & Episode Card Row (Compact cards)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    InputCard(label = "SEASON", paddingVertical = 8.dp) {
                        BasicTextField(
                            value = season,
                            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) onSeasonChange(it) },
                            textStyle = TextStyle(
                                color = EasySubsTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(EasySubsTextPrimary),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp, bottom = 2.dp),
                            decorationBox = { innerTextField ->
                                if (season.isEmpty()) {
                                    Text("e.g. 1", color = EasySubsTextMuted, fontSize = 13.sp)
                                }
                                innerTextField()
                            }
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    InputCard(label = "EPISODE", paddingVertical = 8.dp) {
                        BasicTextField(
                            value = episode,
                            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) onEpisodeChange(it) },
                            textStyle = TextStyle(
                                color = EasySubsTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(EasySubsTextPrimary),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp, bottom = 2.dp),
                            decorationBox = { innerTextField ->
                                if (episode.isEmpty()) {
                                    Text("e.g. 1", color = EasySubsTextMuted, fontSize = 13.sp)
                                }
                                innerTextField()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Language Dropdown Card
            InputCard(label = "LANGUAGE", paddingVertical = 10.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { langExpanded = true }
                        .padding(top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = language, fontSize = 15.sp, color = EasySubsTextPrimary)
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select Language",
                        tint = EasySubsTextMuted
                    )
                }
                DropdownMenu(
                    expanded = langExpanded,
                    onDismissRequest = { langExpanded = false }
                ) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang) },
                            onClick = {
                                onLanguageChange(lang)
                                langExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Source / Provider Card (OpenSubtitles, SubtitleCat, SubDL, Subscene)
            InputCard(label = "SOURCE / PROVIDER", paddingVertical = 10.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sourceExpanded = true }
                        .padding(top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = source,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = EasySubsTextPrimary
                        )
                        if (source == "SubtitleCat") {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• Auto-Translate",
                                fontSize = 11.sp,
                                color = Color(0xFF4ADE80),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select Source",
                        tint = EasySubsTextMuted
                    )
                }
                DropdownMenu(
                    expanded = sourceExpanded,
                    onDismissRequest = { sourceExpanded = false }
                ) {
                    SUBTITLE_SOURCES.forEach { src ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = src,
                                        fontWeight = if (src == source) FontWeight.Bold else FontWeight.Normal,
                                        color = if (src == source) EasySubsAccentText else EasySubsTextPrimary
                                    )
                                    if (src == "SubtitleCat") {
                                        Text(
                                            text = " (Translate)",
                                            fontSize = 11.sp,
                                            color = Color(0xFF4ADE80),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSourceChange(src)
                                sourceExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Action Button & Footer (Black theme with Solid White Pill Button)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EasySubsAccent,
                    contentColor = EasySubsButtonText
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = EasySubsButtonText,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Search Subtitles",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = EasySubsButtonText
                )
            }
        }
    }
}

@Composable
fun ResultsScreen(
    language: String,
    subtitles: List<SubtitleResult>,
    matchedTitle: String? = null,
    activeFilter: String,
    onFilterChange: (String) -> Unit,
    onBack: () -> Unit,
    onDownload: (SubtitleResult) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EasySubsBg)
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = EasySubsTextPrimary
                )
            }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text("Results", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = EasySubsTextPrimary)
                Text(
                    text = if (matchedTitle != null) "$language · ${subtitles.size} found for \"$matchedTitle\""
                           else "$language · ${subtitles.size} subtitles found",
                    fontSize = 13.sp,
                    color = EasySubsTextMuted
                )
            }
        }

        // Filter Chips (Black theme: active=white, inactive=dark gray)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChipItem("Best match", activeFilter == "best") { onFilterChange("best") }
            FilterChipItem("Most downloaded", activeFilter == "downloads") { onFilterChange("downloads") }
            FilterChipItem("Top rated", activeFilter == "rating") { onFilterChange("rating") }
        }

        // Results List
        if (subtitles.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No subtitles found for this selection.\nTry a different language or title.",
                    fontSize = 14.sp,
                    color = EasySubsTextMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(subtitles) { item ->
                    SubtitleCard(item = item, onDownload = { onDownload(item) })
                }
            }
        }

        // Footer
        Text(
            text = "Saved to your Downloads folder",
            fontSize = 12.sp,
            color = EasySubsTextMuted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun FilterChipItem(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) EasySubsAccent else EasySubsChipInactive)
            .border(1.dp, if (isSelected) EasySubsAccent else EasySubsBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) EasySubsButtonText else EasySubsTextMuted
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleCard(item: SubtitleResult, onDownload: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(EasySubsCardBg)
            .border(1.dp, EasySubsBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EasySubsTextPrimary,
                    lineHeight = 20.sp
                )
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Source Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF262626))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.source,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = EasySubsTextPrimary,
                            maxLines = 1
                        )
                    }

                    // Language / Auto-Translate Badge
                    val displayLang = item.language.replace(Regex("(?i)\\s*\\(auto-translate\\)"), "").trim()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (item.isAutoTranslated) Color(0xFF1B3822) else EasySubsAccentLight)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (item.isAutoTranslated) "⚡ $displayLang" else displayLang,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (item.isAutoTranslated) Color(0xFF4ADE80) else EasySubsAccentText,
                            maxLines = 1
                        )
                    }

                    Text(item.format, fontSize = 11.sp, color = EasySubsTextMuted, maxLines = 1)
                    Text("★ ${item.rating}", fontSize = 11.sp, color = EasySubsTextMuted, maxLines = 1)
                }
            }

            IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(EasySubsAccent)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download Subtitle",
                    tint = EasySubsButtonText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun InputCard(
    label: String,
    modifier: Modifier = Modifier,
    paddingVertical: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EasySubsCardBg)
            .border(1.dp, EasySubsBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = paddingVertical)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = EasySubsTextMuted,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            content()
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 390, heightDp = 844)
@Composable
fun SearchScreenPreview() {
    EasySubsTheme {
        SearchScreen(
            title = "Inception",
            season = "",
            episode = "",
            language = "English",
            onTitleChange = {},
            onSeasonChange = {},
            onEpisodeChange = {},
            onLanguageChange = {},
            onSearch = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 390, heightDp = 844)
@Composable
fun ResultsScreenPreview() {
    EasySubsTheme {
        ResultsScreen(
            language = "English",
            subtitles = sampleSubtitles,
            activeFilter = "best",
            onFilterChange = {},
            onBack = {},
            onDownload = {}
        )
    }
}

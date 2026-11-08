package com.example.easysubs.network

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class OpenSubtitlesClientTest {

    @Test
    fun testMovieSearch() = runBlocking {
        val resp = OpenSubtitlesClient.searchByName("Inception", "eng", "", "")
        println("Inception results: ${resp.results.size}")
        assertTrue("Expected results for Inception", resp.results.isNotEmpty())
    }

    @Test
    fun testTypoTolerance() = runBlocking {
        val resp = OpenSubtitlesClient.searchByName("Incepstion", "eng", "", "")
        println("Incepstion corrected: ${resp.correctedTitle}, count: ${resp.results.size}")
        assertTrue("Expected typo correction", resp.results.isNotEmpty())
    }

    @Test
    fun testFilenameAutoDetect() = runBlocking {
        val resp = OpenSubtitlesClient.searchByName("Outer.Banks.S04E01.1080p.NF", "eng", "", "")
        println("Outer Banks results: ${resp.results.size}")
        assertTrue("Expected results for Outer Banks S04E01", resp.results.isNotEmpty())
    }
}

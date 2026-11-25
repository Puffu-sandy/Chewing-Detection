package com.breakfastbuddy.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 解析各種 YouTube 網址格式並擷取 11 碼影片 ID
 */
fun extractYouTubeVideoId(input: String): String {
    val trimmed = input.trim()
    val patterns = listOf(
        Regex("(?:v=|/v/|youtu\\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})"),
        Regex("^([A-Za-z0-9_-]{11})$")
    )
    for (regex in patterns) {
        val match = regex.find(trimmed)
        if (match != null) return match.groupValues[1]
    }
    return "e_04ZrNroTo" // 預設兒童卡通/兒歌範例影片 ID，家長可隨時替換
}

/**
 * 使用 YouTube IFrame Player API 的 WebView 播放器，
 * 支援由咀嚼狀態機即時控制暫停 (pauseVideo) 與恢復播放 (playVideo)。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayerView(
    videoId: String,
    shouldPlay: Boolean,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadedVideoId by remember { mutableStateOf("") }

    // 當影片 ID 改變時載入新影片
    LaunchedEffect(videoId, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (loadedVideoId != videoId) {
            loadedVideoId = videoId
            val html = buildYouTubeIframeHtml(videoId)
            wv.loadDataWithBaseURL(
                "https://www.youtube.com",
                html,
                "text/html",
                "utf-8",
                null
            )
        }
    }

    // 同步播放/暫停控制指令至 YouTube Player
    LaunchedEffect(shouldPlay, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val js = if (shouldPlay) "window.controlPlay && window.controlPlay();" else "window.controlPause && window.controlPause();"
        wv.evaluateJavascript(js, null)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                webViewRef = this
            }
        }
    )
}

private fun buildYouTubeIframeHtml(videoId: String): String {
    val safeId = videoId.replace(Regex("[^A-Za-z0-9_-]"), "")
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                html, body {
                    margin: 0;
                    padding: 0;
                    width: 100%;
                    height: 100%;
                    background-color: #000000;
                    overflow: hidden;
                }
                #player {
                    width: 100%;
                    height: 100%;
                }
            </style>
        </head>
        <body>
            <div id="player"></div>
            <script>
                var tag = document.createElement('script');
                tag.src = "https://www.youtube.com/iframe_api";
                var firstScriptTag = document.getElementsByTagName('script')[0];
                firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                var player;
                var desiredPlaying = true;
                var playerReady = false;

                function onYouTubeIframeAPIReady() {
                    player = new YT.Player('player', {
                        width: '100%',
                        height: '100%',
                        videoId: '$safeId',
                        playerVars: {
                            'playsinline': 1,
                            'rel': 0,
                            'modestbranding': 1,
                            'autoplay': 0
                        },
                        events: {
                            'onReady': function() {
                                playerReady = true;
                                if (!desiredPlaying) {
                                    player.pauseVideo();
                                }
                            }
                        }
                    });
                }

                window.controlPlay = function() {
                    desiredPlaying = true;
                    if (playerReady && player && player.playVideo) {
                        player.playVideo();
                    }
                };

                window.controlPause = function() {
                    desiredPlaying = false;
                    if (playerReady && player && player.pauseVideo) {
                        player.pauseVideo();
                    }
                };
            </script>
        </body>
        </html>
    """.trimIndent()
}

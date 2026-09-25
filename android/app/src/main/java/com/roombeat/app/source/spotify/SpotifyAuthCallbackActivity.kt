package com.roombeat.app.source.spotify

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Trampoline activity for handling Spotify App Remote redirect authentication callbacks
 * matching URI scheme "roombeat://spotify-callback".
 *
 * Forwards received authorization response URIs to [SpotifyRemoteManager.handleAuthRedirect]
 * and finishes immediately to preserve the back stack.
 */
class SpotifyAuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            SpotifyRemoteManager.handleAuthRedirect(uri)
        }
    }
}

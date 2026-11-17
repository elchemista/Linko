package it.linko.app

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** Keep large pasted text across rotation without placing it in Android's size-limited Bundle. */
class LinkoDrafts : ViewModel() {
    val pasteText = mutableStateOf("")
    val bulkUrls = mutableStateOf(emptyList<String>())
}

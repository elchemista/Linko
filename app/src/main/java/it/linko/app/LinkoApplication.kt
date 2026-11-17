package it.linko.app

import android.app.Application
import it.linko.app.data.LibraryRepository

class LinkoApplication : Application() {
    val repository: LibraryRepository by lazy { LibraryRepository(this) }
}

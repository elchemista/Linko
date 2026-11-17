@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package it.linko.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import it.linko.app.LinkoDrafts
import it.linko.app.data.*
import it.linko.app.server.ShareServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Page { Collections, Links, Computer, Settings }
private val filterSelectionSaver = listSaver<List<String>, String>(save = { it }, restore = { it.toList() })

@Composable
fun LinkoApp(repository: LibraryRepository, sharedText: String?, drafts: LinkoDrafts, onShareHandled: () -> Unit) {
    val library by repository.state.collectAsState()
    val smartCollections = remember(library) { SmartCollections.build(library) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(Page.Collections) }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var filterKeys by rememberSaveable(stateSaver = filterSelectionSaver) { mutableStateOf(emptyList<String>()) }
    var filterQualities by rememberSaveable(stateSaver = filterSelectionSaver) { mutableStateOf(emptyList<String>()) }
    var filterDomains by rememberSaveable(stateSaver = filterSelectionSaver) { mutableStateOf(emptyList<String>()) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val filters = LinkFilters(query, categoryId, filterKeys.toSet(), filterQualities.toSet(), filterDomains.toSet(), favorites)
    val filterCount = filterKeys.size + filterQualities.size + filterDomains.size
    val hasFilters = filterCount > 0 || favorites
    var sort by rememberSaveable { mutableStateOf(0) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var initialUrl by rememberSaveable { mutableStateOf("") }
    var editingLinkId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingLink = library.links.find { it.id == editingLinkId }
    var editingCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingCategory = library.categories.find { it.id == editingCategoryId }
    var categoryDialog by rememberSaveable { mutableStateOf(false) }
    var applyRulesDialog by remember { mutableStateOf(false) }
    var deleteLink by remember { mutableStateOf<VideoLink?>(null) }
    var deleteCategory by remember { mutableStateOf<Category?>(null) }
    var saving by remember { mutableStateOf(false) }
    var pasteText by drafts.pasteText
    var fromQuickPaste by rememberSaveable { mutableStateOf(false) }
    var bulkUrls by drafts.bulkUrls
    var bulkSaving by remember { mutableStateOf(false) }
    val parsedPaste = remember(pasteText) { runCatching { MetadataExtractor.extractUrls(pasteText) } }
    val pastedUrls = parsedPaste.getOrDefault(emptyList())

    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun mutate(success: String? = null, operation: () -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(operation) }
            result.fold({ if (success != null) snackbar.showSnackbar(success) }, { snackbar.showSnackbar(it.message ?: "Operazione non riuscita") })
        }
    }
    fun openLink(link: VideoLink) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
            .onFailure { message("Nessuna app disponibile per aprire questo link") }
    }
    fun shareLink(link: VideoLink) {
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link.url)
                putExtra(Intent.EXTRA_SUBJECT, link.title)
            }, "Condividi il video"))
        }.onFailure { message("Condivisione non disponibile") }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(repository.exportJson()) }
                    ?: error("Impossibile scrivere il file")
            } }
            snackbar.showSnackbar(result.fold({ "Backup esportato" }, { "Esportazione non riuscita: ${it.message}" }))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 10 * 1024 * 1024) { "Il backup supera 10 MB" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                } ?: error("File non leggibile")
                repository.importJson(json)
            } }
            snackbar.showSnackbar(result.fold({ "Importazione completata: $it link" }, { "Importazione non riuscita: ${it.message}" }))
        }
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            keyboard?.hide()
            bulkUrls = emptyList()
            showSave = false
            editingLinkId = null
            fromQuickPaste = false
            val sharedResult = runCatching { MetadataExtractor.extractUrls(sharedText) }
            if (sharedResult.isFailure) {
                pasteText = sharedText
                page = Page.Collections
                onShareHandled()
                snackbar.showSnackbar(sharedResult.exceptionOrNull()?.message ?: "Impossibile leggere il testo condiviso")
                return@LaunchedEffect
            }
            val sharedUrls = sharedResult.getOrThrow()
            if (sharedUrls.size in 2..100) {
                showSave = false
                bulkUrls = sharedUrls
            } else if (sharedUrls.size > 100) {
                pasteText = sharedText
                page = Page.Collections
                onShareHandled()
                snackbar.showSnackbar("Più di 100 link: dividili in gruppi più piccoli.")
            } else {
                initialUrl = sharedUrls.singleOrNull() ?: sharedText
                showSave = true
            }
        }
    }
    BackHandler(page != Page.Collections || categoryId != null) {
        if (categoryId != null) categoryId = null else page = Page.Collections
        query = ""
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Lavender), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Bookmarks, null, tint = Color(0xFF281F3A), modifier = Modifier.size(21.dp))
                }
                Text("linko", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, modifier = Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(CircleShape).background(Panel).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFA4D5BF)))
                    Text("LA TUA RACCOLTA", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 7.dp))
                }
                IconButton(onClick = { page = if (page == Page.Settings) Page.Collections else Page.Settings }) {
                    Icon(Icons.Rounded.Tune, "Impostazioni", tint = if (page == Page.Settings) Lavender else Muted)
                }
            }
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                if (page == Page.Collections || page == Page.Links) QuickPasteBar(
                    value = pasteText, onValueChange = { pasteText = it }, linkCount = pastedUrls.size,
                    error = parsedPaste.exceptionOrNull()?.message, enabled = !saving && !bulkSaving,
                    onSend = {
                        if (pastedUrls.size in 1..100) {
                            keyboard?.hide()
                            if (pastedUrls.size == 1) {
                                initialUrl = pastedUrls.single(); editingLinkId = null; fromQuickPaste = true; showSave = true
                            } else bulkUrls = pastedUrls
                        }
                    })
            if (!keyboardVisible) {
            NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                listOf(Triple(Page.Collections, Icons.Rounded.GridView, "Raccolte"), Triple(Page.Links, Icons.Rounded.PlayCircleOutline, "Tutti i link"), Triple(Page.Computer, Icons.Rounded.Devices, "Sul PC")).forEach { (destination, icon, label) ->
                    NavigationBarItem(selected = page == destination, onClick = { page = destination; categoryId = null; query = "" },
                        icon = { Icon(icon, null) }, label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Lavender, selectedTextColor = Lavender, indicatorColor = Color(0xFF282131), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
            }
            }
        }
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (page) {
            Page.Collections, Page.Links -> {
                val selectedCategory = library.categories.find { it.id == categoryId }
                val showCollections = page == Page.Collections && query.isBlank() && !hasFilters
                val visible = library.links.filter { link -> LinkMatcher.matches(link, filters, library.categories.find { it.id == link.categoryId }?.name.orEmpty()) }
                    .let { links -> when (sort) { 1 -> links.sortedBy { it.createdAt }; 2 -> links.sortedBy { it.title.lowercase() }; else -> links.sortedByDescending { it.createdAt } } }
                LazyColumn(modifier, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        if (showCollections) Hero(library.links.size)
                        else Column(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
                            if (selectedCategory != null) TextButton(onClick = { categoryId = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Tutti i link") }
                            Text(selectedCategory?.name ?: if (hasFilters) "I video che cerchi." else "Ogni link, al suo posto.", style = MaterialTheme.typography.headlineMedium)
                            Text("${visible.size} video nella tua raccolta", color = Muted, modifier = Modifier.padding(top = 7.dp))
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            SearchBox(query, { query = it }, Modifier.weight(1f))
                            FilledTonalButton(onClick = { showFilters = true }, modifier = Modifier.height(56.dp), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 13.dp)) {
                                Icon(Icons.Rounded.FilterList, null, Modifier.size(19.dp))
                                Text(if (filterCount > 0) "Filtri · $filterCount" else "Filtri", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    if (selectedCategory != null && smartCollections.any { it.categoryId == selectedCategory.id }) item {
                        SmartCollectionSection(smartCollections.filter { it.categoryId == selectedCategory.id }, library.categories) { collection ->
                            categoryId = collection.categoryId; filterKeys = listOf(collection.key); query = ""; page = Page.Links
                        }
                    }
                    if (filterCount > 0) item {
                        Column {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                filterKeys.forEach { value -> ActiveFilterChip("#$value") { filterKeys = filterKeys - value } }
                                filterQualities.forEach { value -> ActiveFilterChip(value) { filterQualities = filterQualities - value } }
                                filterDomains.forEach { value -> ActiveFilterChip(value) { filterDomains = filterDomains - value } }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Tutte le keys · Una delle qualità · Uno dei siti", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { filterKeys = emptyList(); filterQualities = emptyList(); filterDomains = emptyList() }) { Text("Azzera", fontSize = 12.sp) }
                            }
                        }
                    }
                    if (showCollections) {
                        if (smartCollections.isNotEmpty()) item {
                            SmartCollectionSection(smartCollections, library.categories) { collection ->
                                categoryId = collection.categoryId; filterKeys = listOf(collection.key); query = ""; page = Page.Links
                            }
                        }
                        item { SectionTitle("Le tue raccolte", "${library.categories.size}") { editingCategoryId = null; categoryDialog = true } }
                        items(library.categories.chunked(2), key = { it.first().id }) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { category ->
                                    CategoryCard(category, library.links.count { it.categoryId == category.id }, Modifier.weight(1f)) {
                                        categoryId = category.id; page = Page.Links; favorites = false; query = ""
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (library.links.isNotEmpty()) item { SectionTitle("Salvati di recente", "") { page = Page.Links; categoryId = null } }
                        if (library.links.isEmpty()) item {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.IosShare, null, tint = Lavender, modifier = Modifier.size(25.dp))
                                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                    Text("Un bel video? Tienilo qui.", fontWeight = FontWeight.SemiBold)
                                    Text("Da Chrome: Condividi → Linko.\nOppure incolla uno o più link nella barra qui sotto.", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    } else item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = favorites, onClick = { favorites = !favorites }, label = { Text("Preferiti") }, leadingIcon = { Icon(if (favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, Modifier.size(16.dp)) })
                            Spacer(Modifier.weight(1f))
                            var sortMenu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(listOf("Più recenti", "Meno recenti", "Titolo")[sort], fontSize = 12.sp) }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    listOf("Più recenti", "Meno recenti", "Titolo").forEachIndexed { i, text -> DropdownMenuItem(text = { Text(text) }, onClick = { sort = i; sortMenu = false }) }
                                }
                            }
                        }
                    }
                    val displayed = if (showCollections) library.links.sortedByDescending { it.createdAt }.take(4) else visible
                    items(displayed, key = { "link-${it.id}" }) { link ->
                        VideoCard(link, library.categories.find { it.id == link.categoryId }, onOpen = { openLink(link) }, onShare = { shareLink(link) }, onFavorite = { mutate { repository.toggleFavorite(link.id) } }, onEdit = { editingLinkId = link.id; initialUrl = link.url; fromQuickPaste = false; showSave = true }, onDelete = { deleteLink = link },
                            onKeyClick = { value -> filterKeys = (filterKeys + value).distinct(); page = Page.Links },
                            onQualityClick = { value -> filterQualities = (filterQualities + value).distinct(); page = Page.Links },
                            onDomainClick = { value -> filterDomains = (filterDomains + value).distinct(); page = Page.Links })
                    }
                    if (displayed.isEmpty() && !showCollections) item {
                        EmptyState(if (filterCount > 0) "Nessun video con questi filtri" else if (query.isNotBlank()) "Nessun risultato" else if (favorites) "I tuoi preferiti vivranno qui" else "Spazio alle tue scoperte", if (filterCount > 0) "Togli una key o scegli altre qualità e siti. Puoi cambiare tutto con un tocco." else if (query.isNotBlank()) "Prova un titolo, una parola chiave o un sito diverso." else "Salva il primo link e scegli le parole chiave per ritrovarlo.")
                    }
                }
            }
            Page.Computer -> ComputerScreen(library, modifier, ::message)
            Page.Settings -> SettingsScreen(library, modifier,
                onCreateCategory = { editingCategoryId = null; categoryDialog = true },
                onEditCategory = { editingCategoryId = it.id; categoryDialog = true },
                onDeleteCategory = { deleteCategory = it },
                onApplyRules = { applyRulesDialog = true },
                onSmartThresholdChange = { threshold -> mutate { repository.setSmartCollectionThreshold(threshold) } },
                onAddKey = { mutate("Parola chiave creata") { repository.addKey(it) } },
                onDeleteKey = { mutate("Parola chiave eliminata") { repository.deleteKey(it) } },
                onExport = { exportLauncher.launch("linko-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(Date())}.json") },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
        }
    }
    if (showFilters) FilterSheet(library = library, filters = filters,
        onChange = { changed -> filterKeys = changed.keys.toList(); filterQualities = changed.qualities.toList(); filterDomains = changed.domains.toList() },
        onDismiss = { showFilters = false })
    if (bulkUrls.isNotEmpty()) BulkImportSheet(urls = bulkUrls, library = library, isSaving = bulkSaving,
        onDismiss = { if (!bulkSaving) { bulkUrls = emptyList(); onShareHandled() } },
        onCreateCategory = { mutate { repository.saveCategory(it) } },
        onSave = { links ->
            if (!bulkSaving && links.isNotEmpty()) {
                bulkSaving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { repository.saveLinks(links) } }
                    bulkSaving = false
                    result.onSuccess { bulkUrls = emptyList(); pasteText = ""; onShareHandled() }
                    snackbar.showSnackbar(result.fold({ "$it link organizzati nella raccolta" }, { "Salvataggio non riuscito: ${it.message}" }))
                }
            }
        })
    if (showSave) key(editingLink?.id, initialUrl) {
        AddLinkSheet(initialUrl = initialUrl, initialLink = editingLink, library = library,
            onDismiss = { if (!saving) { showSave = false; fromQuickPaste = false; onShareHandled() } },
            onSave = { link ->
                if (!saving) {
                    saving = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { repository.saveLink(link) } }
                        saving = false
                        result.onSuccess { showSave = false; if (fromQuickPaste) pasteText = ""; fromQuickPaste = false; onShareHandled() }
                        snackbar.showSnackbar(result.fold({ "${if (editingLink == null) "Link salvato" else "Link aggiornato"} nella raccolta" }, { "Salvataggio non riuscito: ${it.message}" }))
                    }
                }
            },
            onCreateCategory = { mutate { repository.saveCategory(it) } },
            onCreateKey = { mutate { repository.addKey(it) } }, isSaving = saving)
    }
    if (categoryDialog) CategoryEditor(editingCategory, onDismiss = { categoryDialog = false }, existingCategories = library.categories) { name, icon, color, matchKeywords ->
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.saveCategory(name, icon, color, editingCategory?.id ?: 0, matchKeywords) } }
            result.onSuccess { categoryDialog = false }
            snackbar.showSnackbar(result.fold({ "Raccolta salvata" }, { it.message ?: "Impossibile salvare" }))
        }
    }
    deleteLink?.let { link -> ConfirmDialog("Eliminare il link?", "«${link.title}» verrà rimosso dalla raccolta.", { deleteLink = null }) { mutate("Link eliminato") { repository.deleteLink(link.id) }; deleteLink = null } }
    deleteCategory?.let { category -> ConfirmDialog("Eliminare ${category.name}?", "I suoi link verranno spostati in ${library.categories.find { it.id == 1L }?.name ?: "Da organizzare"}.", { deleteCategory = null }) { mutate("Raccolta eliminata") { repository.deleteCategory(category.id) }; deleteCategory = null } }
    if (applyRulesDialog) {
        val changedCount = library.links.count { link ->
            CategoryRules.suggest(link.url, library.categories, listOf(link.title, link.description, link.keys.joinToString(" ")).joinToString(" "))?.id?.let { it != link.categoryId } ?: false
        }
        AlertDialog(onDismissRequest = { applyRulesDialog = false }, title = { Text("Applica le regole alla raccolta?") },
            text = { Text(if (changedCount == 0) "Le categorie dei link salvati sono già coerenti con le regole attuali." else "$changedCount link verranno riorganizzati secondo le parole chiave delle categorie.") },
            confirmButton = { TextButton(onClick = {
                applyRulesDialog = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { repository.applyCategoryRules() } }
                    snackbar.showSnackbar(result.fold({ "$it link riorganizzati" }, { it.message ?: "Impossibile applicare le regole" }))
                }
            }, enabled = changedCount > 0) { Text("Riorganizza") } },
            dismissButton = { TextButton(onClick = { applyRulesDialog = false }) { Text("Chiudi") } })
    }
}

@Composable
private fun Hero(count: Int) {
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
        Column(Modifier.fillMaxWidth(0.82f)) {
            Text("SALVA. ORGANIZZA. RITROVA.", style = MaterialTheme.typography.labelSmall, color = Lavender, letterSpacing = 1.5.sp)
            Text("Le tue scoperte.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 11.dp))
            Text(if (count == 0) "Tutte in ordine, a un tocco." else "$count ${if (count == 1) "scoperta da rivedere" else "scoperte da rivedere"}, quando vuoi.", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(top = 8.dp))
        }
        Canvas(Modifier.size(82.dp).align(Alignment.TopEnd).padding(top = 30.dp)) {
            drawCircle(Lavender.copy(alpha = 0.05f), radius = size.minDimension / 2)
            drawCircle(Lavender.copy(alpha = 0.09f), radius = size.minDimension / 2.7f)
            val path = Path().apply { moveTo(size.width * .43f, size.height * .30f); lineTo(size.width * .70f, size.height * .50f); lineTo(size.width * .43f, size.height * .70f); close() }
            drawPath(path, Lavender.copy(alpha = .8f))
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value = query, onValueChange = onChange, modifier = modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text("Cerca nella raccolta…", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted, modifier = Modifier.size(21.dp)) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Cancella ricerca") } },
        shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, unfocusedContainerColor = Panel, focusedContainerColor = Panel))
}

@Composable
private fun ActiveFilterChip(label: String, onRemove: () -> Unit) {
    InputChip(selected = true, onClick = onRemove,
        label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp)) },
        trailingIcon = { Icon(Icons.Rounded.Close, "Rimuovi $label", Modifier.size(13.dp)) })
}

@Composable
private fun SmartCollectionSection(collections: List<SmartCollection>, categories: List<Category>, onPick: (SmartCollection) -> Unit) {
    var expanded by rememberSaveable(collections.map { it.categoryId }.distinct()) { mutableStateOf(false) }
    val showParent = collections.map { it.categoryId }.distinct().size > 1
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Lavender, modifier = Modifier.size(18.dp))
            Text("Raccolte intelligenti", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 9.dp))
        }
        Text("Le keys più ricorrenti, già raggruppate per te.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 7.dp)) {
            (if (expanded) collections else collections.take(8)).forEach { collection ->
                val parent = if (showParent) categories.find { it.id == collection.categoryId }?.name?.let { "$it › " }.orEmpty() else ""
                AssistChip(onClick = { onPick(collection) }, label = {
                    Text("$parent#${collection.key} · ${collection.count}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
                })
            }
        }
        if (collections.size > 8) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Mostra meno" else "Mostra tutte (${collections.size})", fontSize = 12.sp) }
    }
}

@Composable
private fun SectionTitle(title: String, count: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count.isNotEmpty()) Text(count, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(start = 9.dp).clip(CircleShape).background(Panel).padding(horizontal = 8.dp, vertical = 3.dp))
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(if (count.isEmpty()) "Vedi tutti" else "+ Nuova", fontSize = 12.sp) }
    }
}

fun categoryIcon(icon: String): ImageVector = when (icon) {
    "food", "cooking" -> Icons.Rounded.Restaurant
    "code", "tech" -> Icons.Rounded.Code
    "travel" -> Icons.Rounded.Explore
    "spark", "sparkles" -> Icons.Rounded.AutoAwesome
    "inbox" -> Icons.Rounded.Inbox
    "music" -> Icons.Rounded.MusicNote
    "sport" -> Icons.Rounded.FitnessCenter
    "learn" -> Icons.Rounded.School
    else -> Icons.Rounded.FolderOpen
}

@Composable
private fun CategoryCard(category: Category, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val tint = Color(category.color)
    Column(modifier.clip(RoundedCornerShape(21.dp)).background(Brush.linearGradient(listOf(tint.copy(alpha = .13f), Panel))).clickable(onClick = onClick).padding(17.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(39.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .13f)), contentAlignment = Alignment.Center) { Icon(categoryIcon(category.icon), null, tint = tint, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.NorthEast, null, tint = tint.copy(alpha = .5f), modifier = Modifier.size(16.dp))
        }
        Text(category.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp))
        Text("$count ${if (count == 1) "video" else "video"}", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun VideoCard(link: VideoLink, category: Category?, onOpen: () -> Unit, onShare: () -> Unit, onFavorite: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onKeyClick: (String) -> Unit, onQualityClick: (String) -> Unit, onDomainClick: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val host = LinkMatcher.domainOf(link.url) ?: "Video"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(13.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(width = 100.dp, height = 88.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(Color(0xFF3B304D), Color(0xFF252632)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayCircleOutline, null, tint = Lavender.copy(alpha = .7f), modifier = Modifier.size(34.dp))
                if (!link.thumbnail.isNullOrBlank()) AsyncImage(model = link.thumbnail, contentDescription = "Anteprima di ${link.title}", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                if (!link.quality.isNullOrBlank()) Text(link.quality, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .7f)).clickable { onQualityClick(link.quality) }.padding(horizontal = 5.dp, vertical = 2.dp))
            }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text(host, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onDomainClick(host) })
                Text(link.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(category?.name ?: "Da organizzare", color = category?.let { Color(it.color) } ?: Lavender, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                link.keys.take(2).forEach { key ->
                    Text("#$key", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF27262F)).clickable { onKeyClick(key) }.padding(horizontal = 7.dp, vertical = 4.dp))
                }
                if (link.keys.size > 2) Text("+${link.keys.size - 2}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(40.dp)) { Icon(if (link.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (link.favorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti", tint = if (link.favorite) Color(0xFFE9A3B7) else Muted, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Share, "Condividi link", tint = Lavender, modifier = Modifier.size(18.dp)) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, "Altre azioni", tint = Muted, modifier = Modifier.size(20.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Modifica") }, onClick = { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem(text = { Text("Elimina", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 42.dp, horizontal = 15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp).clip(RoundedCornerShape(22.dp)).background(Panel), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Bookmarks, null, tint = Lavender, modifier = Modifier.size(29.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 19.dp))
        Text(body, color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ComputerScreen(library: LibraryState, modifier: Modifier, message: (String) -> Unit) {
    val context = LocalContext.current
    val status by ShareServerManager.state.collectAsState()
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    var allowEditing by rememberSaveable { mutableStateOf(false) }
    val selectedIds = (if (status.running || status.starting) status.sharedCategoryIds.toList() else selected ?: library.categories.map { it.id })
        .filter { id -> library.categories.any { it.id == id } }.toSet()
    val editingEnabled = if (status.running || status.starting) status.allowEditing else allowEditing
    var pendingServerStart by remember { mutableStateOf<Triple<String, Set<Long>, Boolean>?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingServerStart?.let { (chosenPassword, categories, editing) ->
            ShareServerManager.start(context, chosenPassword, categories, editing)
            if (!granted) message("Notifiche disattivate: puoi fermare il server dalla scheda Sul PC.")
        }
        pendingServerStart = null
    }
    LaunchedEffect(Unit) { ShareServerManager.refreshAddresses() }
    LazyColumn(modifier, contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Box(Modifier.size(57.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2C243B)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Devices, null, tint = Lavender, modifier = Modifier.size(28.dp)) }
            Text("Dal telefono.\nAl tuo schermo grande.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 20.dp))
            Text("Apri la tua raccolta dal browser del PC.\nDecidi tu quali categorie mostrare.", color = Muted, modifier = Modifier.padding(top = 12.dp))
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = Lavender, modifier = Modifier.size(20.dp))
                    Text("Accesso con password", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
                }
                OutlinedTextField(password, { password = it.take(128) }, label = { Text("Password del server") }, supportingText = { Text("Almeno 8 caratteri. Non viene salvata.", fontSize = 11.sp) }, singleLine = true, enabled = !status.running && !status.starting,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Mostra o nascondi password") } },
                    modifier = Modifier.fillMaxWidth().padding(top = 15.dp), shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                Row(Modifier.fillMaxWidth().padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Consenti modifiche dal PC", style = MaterialTheme.typography.titleMedium)
                        Text("Chi accede con la password può aggiungere link e gestire le categorie condivise.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Switch(checked = editingEnabled, onCheckedChange = { allowEditing = it }, enabled = !status.running && !status.starting,
                        modifier = Modifier.semantics { contentDescription = "Consenti modifiche dal PC" })
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Categorie visibili", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = if (selectedIds.size == library.categories.size) emptyList() else library.categories.map { it.id } }, enabled = !status.running && !status.starting) { Text(if (selectedIds.size == library.categories.size) "Nessuna" else "Tutte", fontSize = 12.sp) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    library.categories.forEach { category -> FilterChip(selected = category.id in selectedIds, enabled = !status.running && !status.starting,
                        onClick = { selected = if (category.id in selectedIds) selectedIds.minus(category.id).toList() else selectedIds.plus(category.id).toList() },
                        label = { Text(category.name, fontSize = 12.sp) }, leadingIcon = { if (category.id in selectedIds) Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)) }) }
                }
                Text("${library.links.count { it.categoryId in selectedIds }} link nelle categorie selezionate", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = {
                    if (status.running) ShareServerManager.stop(context) else {
                        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            pendingServerStart = Triple(password, selectedIds, allowEditing)
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ShareServerManager.start(context, password, selectedIds, allowEditing)
                        }
                    }
                }, enabled = !status.starting && (status.running || (password.length >= 8 && selectedIds.isNotEmpty())), modifier = Modifier.fillMaxWidth().padding(top = 19.dp), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(15.dp)) {
                    Icon(if (status.running) Icons.Rounded.StopCircle else Icons.Rounded.PowerSettingsNew, null, Modifier.size(19.dp))
                    Text(if (status.starting) "Avvio in corso…" else if (status.running) "Ferma condivisione" else "Avvia condivisione", modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.SemiBold)
                }
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
        if (status.running) item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF1D2B27)).padding(19.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFA4D5BF))); Text("SERVER ATTIVO", color = Color(0xFFA4D5BF), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 9.dp)) }
                Text(if (status.allowEditing) "Lettura e modifica dal PC" else "Solo lettura", color = Color(0xFFA4D5BF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 9.dp))
                Text("Sul PC apri uno di questi indirizzi", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                if (status.addresses.isEmpty()) Text("Nessun indirizzo disponibile. Attiva hotspot, Wi-Fi o VPN e aggiorna.", color = Muted, fontSize = 12.sp)
                status.addresses.forEach { address ->
                    Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(address, fontSize = 13.sp, color = Color(0xFFA4D5BF), modifier = Modifier.weight(1f))
                        IconButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Linko", address)); message("Indirizzo copiato") }) { Icon(Icons.Rounded.ContentCopy, "Copia indirizzo", tint = Color(0xFFA4D5BF), modifier = Modifier.size(18.dp)) }
                    }
                }
                TextButton(onClick = { ShareServerManager.refreshAddresses() }) { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Text("Aggiorna indirizzi", fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp)) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text("COME COLLEGARTI", color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                Instruction("01", "Con hotspot o Wi-Fi", "Collega il PC all’hotspot del telefono oppure alla stessa rete Wi-Fi. Avvia la condivisione e apri dal PC l’indirizzo del telefono.")
                Instruction("02", "Da lontano, anche in 4G", "L’IP mobile spesso non è raggiungibile per i limiti dell’operatore. Installa Tailscale su telefono e PC, accedi allo stesso account e usa l’indirizzo VPN del telefono con porta :8787.")
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tailscale.com/download"))) }.onFailure { message("Impossibile aprire il browser") } }) { Text("Apri Tailscale"); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(start = 7.dp).size(15.dp)) }
                Text("Il server usa HTTP: utilizzalo su una rete fidata o dentro la VPN Tailscale. La password protegge l’accesso, la VPN cifra la connessione. Il telefono deve restare acceso; il server si ferma dalla notifica o da questa schermata.", fontSize = 11.sp, color = Muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Instruction(number: String, title: String, body: String) {
    Row(Modifier.padding(top = 18.dp)) {
        Text(number, color = Lavender, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
        Column(Modifier.padding(start = 15.dp)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(body, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp)) }
    }
}

@Composable
private fun SettingsScreen(library: LibraryState, modifier: Modifier, onCreateCategory: () -> Unit, onEditCategory: (Category) -> Unit, onDeleteCategory: (Category) -> Unit, onApplyRules: () -> Unit, onSmartThresholdChange: (Int) -> Unit, onAddKey: (String) -> Unit, onDeleteKey: (String) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var newKey by remember { mutableStateOf("") }
    var keyToDelete by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        item { Text("Falla tua.", style = MaterialTheme.typography.headlineLarge); Text("Categorie, parole chiave e una copia delle tue scoperte.", color = Muted, modifier = Modifier.padding(top = 8.dp)) }
        item { SectionTitle("Categorie", "${library.categories.size}", onCreateCategory) }
        items(library.categories, key = { it.id }) { category ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel).padding(start = 15.dp, end = 5.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(categoryIcon(category.icon), null, tint = Color(category.color), modifier = Modifier.size(21.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(category.name, fontSize = 14.sp)
                    Text("${library.links.count { it.categoryId == category.id }} video", color = Muted, fontSize = 11.sp)
                    if (category.matchKeywords.isNotEmpty()) Text(category.matchKeywords.joinToString(" · "), color = Lavender, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onEditCategory(category) }) { Icon(Icons.Rounded.Edit, "Modifica ${category.name}", tint = Muted, modifier = Modifier.size(18.dp)) }
                if (category.id != 1L) IconButton(onClick = { onDeleteCategory(category) }) { Icon(Icons.Rounded.DeleteOutline, "Elimina ${category.name}", tint = Muted, modifier = Modifier.size(19.dp)) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Lavender, modifier = Modifier.size(19.dp))
                    Text("Categorie automatiche", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
                }
                Text("Nella modifica di una categoria puoi indicare le parole da cercare nell’URL e nei metadati. Basta una corrispondenza per proporre quella categoria.", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                if (library.categories.any { it.matchKeywords.isNotEmpty() }) TextButton(onClick = onApplyRules) { Text("Applica anche ai link già salvati", fontSize = 12.sp) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Lavender, modifier = Modifier.size(19.dp))
                    Text("Raccolte intelligenti", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
                }
                Text("Dentro ogni categoria, le keys che si ripetono creano sottoraccolte automatiche. Un video può comparire in più sottoraccolte.", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Almeno ${library.smartThreshold} video", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onSmartThresholdChange(library.smartThreshold - 1) }, enabled = library.smartThreshold > 2) { Icon(Icons.Rounded.RemoveCircleOutline, "Riduci soglia raccolte intelligenti") }
                    IconButton(onClick = { onSmartThresholdChange(library.smartThreshold + 1) }, enabled = library.smartThreshold < 20) { Icon(Icons.Rounded.AddCircleOutline, "Aumenta soglia raccolte intelligenti") }
                }
            }
        }
        item {
            Text("Parole chiave", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text("Tocca una parola per eliminarla anche dai link.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newKey, { newKey = it.take(60) }, placeholder = { Text("Una nuova parola chiave", fontSize = 13.sp) }, singleLine = true, shape = RoundedCornerShape(13.dp), modifier = Modifier.weight(1f))
                IconButton(onClick = { onAddKey(newKey.trim()); newKey = "" }, enabled = newKey.isNotBlank()) { Icon(Icons.Rounded.AddCircle, "Crea parola chiave", tint = Lavender) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 9.dp)) {
                library.keys.forEach { key -> InputChip(selected = false, onClick = { keyToDelete = key }, label = { Text("#$key", fontSize = 12.sp) }, trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(13.dp)) }) }
            }
            if (library.keys.isEmpty()) Text("Le parole chiave che crei e salvi compariranno qui.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 10.dp))
            Text("La raccolta è tua", style = MaterialTheme.typography.titleMedium)
            Text("I link restano sul telefono. Esporta un backup JSON per conservarli o trasferirli. L’importazione unisce i link senza cancellare la raccolta.", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Rounded.FileDownload, null, Modifier.size(17.dp)); Text("Esporta", modifier = Modifier.padding(start = 6.dp)) }
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Rounded.FileUpload, null, Modifier.size(17.dp)); Text("Importa", modifier = Modifier.padding(start = 6.dp)) }
            }
            Text("Linko 1.1.0 · Fatto per le tue scoperte", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 26.dp, bottom = 20.dp))
        }
    }
    keyToDelete?.let { key -> ConfirmDialog("Eliminare #$key?", "La parola chiave verrà rimossa da tutti i link.", { keyToDelete = null }) { onDeleteKey(key); keyToDelete = null } }
}

@Composable
private fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(onClick = onConfirm) { Text("Elimina", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

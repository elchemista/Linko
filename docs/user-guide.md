# User guide

Linko stores video links on your Android device. The instructions use the current Italian interface labels.

## Save links

Open **Raccolte** (Collections) or **Tutti i link** (All links), paste a URL into the bar at the bottom, then tap the arrow to review it. You can also use Android's share menu and select **Salva in Linko** (Save to Linko).

You can review the title, category, keywords, and notes before you save. When the page provides metadata, Linko uses it for suggestions and a preview. When fetching fails, Linko uses words in the URL and you can still save. Quality and duration are based on values found in the page metadata or URL. You can set quality manually with **Imposta qualità** (Set quality).

### Import a batch

The paste bar also accepts numbered lists, comments, and web addresses without a scheme.

```text
1. https://example.com/street-food-480p Lunch ideas
2. example.org/mountain-travel-720p Trip ideas
Same link again https://example.com/street-food-480p
```

Linko extracts two distinct addresses from this example. In the review sheet, use **Categorie suggerite** (Suggested categories) to keep individual suggestions, or **Una categoria per tutti** (One category for all) to set the destination for the batch. Tap the category on an individual row to override it. You can also change titles and exclude rows.

Use **Salta analisi** (Skip analysis) to proceed with URL suggestions. You can save while metadata is still loading. Linko preserves your manual edits, and saving an existing URL keeps its original date and favorite status. Each batch accepts up to 100 distinct URLs and 256 KiB of input. Split larger inputs into smaller batches.

## Organize your library

Linko starts with starter categories and an empty library. Open **Impostazioni** (Settings) to manage categories and keywords, which Linko calls **keys**. Deleting a category moves its links to **Da organizzare** (Unsorted). Deleting a key removes it from all links that use it.

### Category rules

Enter words or phrases in **Parole per assegnare la categoria** (Words for assigning the category) when creating or editing a category. Separate entries with commas, semicolons, or line breaks. For example, add `street food, recipe` to **Cucina** (Cooking).

Rules check both the URL and available metadata. Matching ignores case and treats punctuation as a separator, so `street food` also matches `street-food`. When several categories match, Linko suggests the one with the most distinct matches and breaks ties in favor of the earliest category. Manual selections and a category chosen for the entire batch take precedence over suggestions.

To apply rules to existing links, open Settings and choose **Applica anche ai link già salvati** (Apply to saved links). Review the number of affected links before confirming.

### Filters and smart collections

Open **Filtri** (Filters) and select any combination of keywords, qualities, and source domains. A link must match all selected keywords, any selected quality, and any selected source. Each group narrows the results. Source filters accept full domains such as `example.com` and suffixes such as `.org`.

Smart collections automatically group links that share a key within a category. For example, when three distinct links in **Cucina** have `#food`, Linko shows a `#food` smart collection for that category. You can set the threshold between 2 and 20 under **Raccolte intelligenti** (Smart collections) in Settings.

## Open your library on a computer

Connect the computer and phone to the same Wi-Fi network, the phone's hotspot, or a private VPN that allows the computer to reach the phone.

1. Open **Sul PC** (On PC) and select the categories to share.
2. Set a password between 8 and 128 characters.
3. Turn on **Consenti modifiche dal PC** (Allow editing from the computer) if you want browser users with the password to add or edit links. With the toggle off, the library is read-only.
4. Tap **Avvia condivisione** (Start sharing). Android may ask for permission to show the sharing notification.
5. Open one of the displayed addresses on your computer and enter the password. The server listens on port `8787`.

Use **Aggiorna indirizzi** (Refresh addresses) if the network changes. If the browser cannot connect over Wi-Fi, check whether the network isolates devices from one another. With a private VPN, use the phone's VPN address and confirm that the network allows the connection.

The server uses HTTP. Use it on a trusted local network or through an encrypted VPN, and keep port `8787` private. To change the password, category selection, or editing mode, stop sharing and start it again. Tap **Ferma condivisione** (Stop sharing) or use the notification's **Ferma** (Stop) action. Stopping clears the password and browser sessions from memory.

### Edit from the browser

With editing enabled, paste a link or batch into **Incolla link o un testo** (Paste links or text), then select **Organizza** (Organize). Review the categories, titles, keywords, qualities, and notes before saving. Select **Salva tempo** (Save time) to use URL suggestions without fetching metadata.

Use **Modifica link** (Edit link) or **Elimina** (Delete) on a link card, and **Gestisci categorie** (Manage categories) to create or edit a shared category. Categories created in the browser become visible during the current sharing session. Changes are saved to the phone's library.

## Back up and restore

In **Impostazioni**, choose **Esporta** (Export) to save a JSON backup, or **Importa** (Import) to merge a Linko backup into your library. Import can update entries with matching URLs. Export before changing phones or uninstalling the app.

The backup contains your URLs, notes, categories, keys, and smart collection threshold in plain text. Treat it as you would the library itself. Video files, remote image files, and sharing credentials are excluded.

Your saved library is available offline. Fetching new metadata or remote thumbnails and opening video links requires a connection to the relevant site. Pages that require a login or load metadata only through JavaScript may produce incomplete previews.

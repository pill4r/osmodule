package dev.pillar.osmodule.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Persisted Storage Access Framework destination used for video downloads. */
object VideoSaveDirectory {
    private const val PREFS = "osmosis_dl"
    private const val KEY_TREE_URI = "video_tree_uri"

    fun selectedTree(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return uri.takeIf { wanted ->
            context.contentResolver.persistedUriPermissions.any {
                it.uri == wanted && it.isReadPermission && it.isWritePermission
            }
        }
    }

    fun set(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val old = selectedTree(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
        if (old != null && old != uri) {
            runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        }
    }

    fun clear(context: Context) {
        val old = selectedTree(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TREE_URI).apply()
        if (old != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        }
    }

    fun label(context: Context): String? {
        val tree = selectedTree(context) ?: return null
        val document = documentUri(tree) ?: return null
        return runCatching {
            context.contentResolver.query(
                document,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    fun documentUri(tree: Uri): Uri? = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()

    fun find(context: Context, displayName: String): Uri? {
        val tree = selectedTree(context) ?: return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == displayName &&
                        cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR
                    ) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    }
                }
                null
            }
        }.getOrNull()
    }

    fun create(context: Context, displayName: String, mimeType: String): Uri? {
        val tree = selectedTree(context) ?: return null
        val parent = documentUri(tree) ?: return null
        return runCatching {
            DocumentsContract.createDocument(context.contentResolver, parent, mimeType, displayName)
        }.getOrNull()
    }
}

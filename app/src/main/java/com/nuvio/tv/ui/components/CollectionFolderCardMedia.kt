package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.CollectionFolder

fun collectionFolderCardImageUrl(
    folder: CollectionFolder,
    isFocused: Boolean
): String? {
    return if (isFocused) {
        firstNonBlank(folder.focusGifUrl, folder.coverImageUrl)
    } else {
        firstNonBlank(folder.coverImageUrl, folder.focusGifUrl)
    }
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}
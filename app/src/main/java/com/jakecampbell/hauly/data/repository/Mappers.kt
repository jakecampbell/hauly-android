package com.jakecampbell.hauly.data.repository

import com.jakecampbell.hauly.data.local.RecipeBlockEntity
import com.jakecampbell.hauly.data.local.RecipeEntity
import com.jakecampbell.hauly.data.local.ShoppingItemEntity
import com.jakecampbell.hauly.data.remote.RemoteItem
import com.jakecampbell.hauly.domain.model.BlockType
import com.jakecampbell.hauly.domain.model.Recipe
import com.jakecampbell.hauly.domain.model.RecipeBlock
import com.jakecampbell.hauly.domain.model.ShoppingItem
import com.jakecampbell.hauly.domain.model.SyncStatus

fun ShoppingItemEntity.toDomain(recipeIds: List<String> = emptyList()): ShoppingItem =
    ShoppingItem(
        localId = localId,
        remoteId = remoteId,
        name = name,
        stores = stores,
        tags = tags,
        quantity = quantity,
        shopped = shopped,
        syncStatus = syncStatus,
        recipeIds = recipeIds,
    )

/** Snapshot from Notion; the DAO resolves/preserves the local id on upsert. */
fun RemoteItem.toEntity(localId: String, now: Long): ShoppingItemEntity =
    ShoppingItemEntity(
        localId = localId,
        remoteId = pageId,
        name = name,
        stores = stores,
        tags = tags,
        quantity = quantity,
        shopped = shopped,
        syncStatus = SyncStatus.SYNCED,
        updatedAt = now,
    )

fun RemoteItem.toDomain(): ShoppingItem =
    ShoppingItem(
        localId = "",
        remoteId = pageId,
        name = name,
        stores = stores,
        tags = tags,
        quantity = quantity,
        shopped = shopped,
        syncStatus = SyncStatus.SYNCED,
        recipeIds = recipeIds,
    )

fun RecipeEntity.toDomain(): Recipe =
    Recipe(
        id = id,
        name = name,
        ingredients = ingredients,
        instructions = instructions,
        url = url,
        planned = planned,
        lastEditedAt = lastEditedAt,
    )

fun RecipeBlockEntity.toDomain(ordinal: Int): RecipeBlock =
    RecipeBlock(
        id = id,
        type = when (type) {
            "paragraph" -> BlockType.PARAGRAPH
            "heading_1" -> BlockType.HEADING_1
            "heading_2" -> BlockType.HEADING_2
            "heading_3" -> BlockType.HEADING_3
            "bulleted_list_item" -> BlockType.BULLETED_LIST_ITEM
            "numbered_list_item" -> BlockType.NUMBERED_LIST_ITEM
            "to_do" -> BlockType.TODO
            "quote" -> BlockType.QUOTE
            "callout" -> BlockType.CALLOUT
            "divider" -> BlockType.DIVIDER
            else -> BlockType.UNSUPPORTED
        },
        text = text,
        checked = checked,
        ordinal = ordinal,
    )

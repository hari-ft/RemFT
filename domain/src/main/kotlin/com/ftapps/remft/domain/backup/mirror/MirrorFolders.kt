package com.ftapps.remft.domain.backup.mirror

import com.ftapps.remft.domain.backup.JsonText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class MirrorFolder(val itemId: String, val path: String, val want: String)

object MirrorFolders {
    fun folderKey(id: String): String = "folder:$id"

    fun ownerKey(targetType: String, id: String): String = "$targetType:$id"

    fun encode(folders: Map<String, MirrorFolder>): String = JsonText.encode(
        buildJsonObject {
            for ((key, f) in folders) {
                put(
                    key,
                    buildJsonObject {
                        put("i", JsonPrimitive(f.itemId))
                        put("p", JsonPrimitive(f.path))
                        put("w", JsonPrimitive(f.want))
                    },
                )
            }
        },
    )

    fun decode(raw: String?): LinkedHashMap<String, MirrorFolder> {
        val out = LinkedHashMap<String, MirrorFolder>()
        val json = raw?.let { JsonText.parseOrNull(it) } as? JsonObject ?: return out
        for ((key, value) in json) {
            val o = value as? JsonObject ?: continue
            val itemId = JsonText.stringOf(o["i"])?.takeIf { it.isNotEmpty() } ?: continue
            val path = JsonText.stringOf(o["p"])?.takeIf { MirrorPaths.isMirrorPath(it) } ?: continue
            out[key] = MirrorFolder(itemId, path, JsonText.stringOf(o["w"]) ?: path)
        }
        return out
    }

    fun encodePaths(paths: Collection<String>): String = JsonText.encode(JsonArray(paths.map { JsonPrimitive(it) }))

    fun decodePaths(raw: String?): List<String> {
        val json = raw?.let { JsonText.parseOrNull(it) } as? JsonArray ?: return emptyList()
        return json.mapNotNull { JsonText.stringOf(it)?.takeIf { p -> MirrorPaths.isMirrorPath(p) } }
    }
}

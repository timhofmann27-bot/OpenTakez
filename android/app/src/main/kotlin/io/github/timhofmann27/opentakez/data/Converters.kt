package io.github.timhofmann27.opentakez.data

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The file list is stored as a JSON array of URI strings. */
class Converters {
    @TypeConverter
    fun fromFiles(files: List<String>): String = json.encodeToString(serializer, files)

    @TypeConverter
    fun toFiles(value: String): List<String> =
        runCatching { json.decodeFromString(serializer, value) }.getOrDefault(emptyList())

    private companion object {
        val json = Json
        val serializer = ListSerializer(String.serializer())
    }
}

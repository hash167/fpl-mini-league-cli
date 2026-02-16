import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

fun JsonObject.obj(key: String): JsonObject =
    this[key]?.jsonObject ?: JsonObject(emptyMap())

fun JsonObject.arr(key: String): JsonArray =
    this[key] as? JsonArray ?: JsonArray(emptyList())

fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

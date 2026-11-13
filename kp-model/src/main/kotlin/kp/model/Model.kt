package kp.model
import kotlinx.serialization.json.JsonObject
class Message private constructor(val json: JsonObject) {
    companion object { fun read(json: JsonObject): Message = error("message model is not implemented") }
}
class Record private constructor(val json: JsonObject) {
    companion object { fun read(json: JsonObject): Record = error("record model is not implemented") }
}

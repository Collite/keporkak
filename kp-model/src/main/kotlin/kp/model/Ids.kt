package kp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
@JvmInline
value class MessageId(val value: String) {
    init { require((messageId)(JsonPrimitive(value))) { "invalid MessageId" } }
}

@Serializable
@JvmInline
value class StageId(val value: String) {
    init { require((stageId)(JsonPrimitive(value))) { "invalid StageId" } }
}

@Serializable
@JvmInline
value class Party(val value: String) {
    init { require((party)(JsonPrimitive(value))) { "invalid Party" } }
}

@Serializable
@JvmInline
value class Pointer(val value: String) {
    init { require(pointer(JsonPrimitive(value))) { "invalid Pointer" } }
}

@Serializable
@JvmInline
value class EffortId(val value: String) {
    init { require((pattern("^[A-Z][A-Z0-9-]*$"))(JsonPrimitive(value))) { "invalid EffortId" } }
}

@Serializable
@JvmInline
value class OfficeId(val value: String) {
    init { require((pattern("^[a-z0-9]+$"))(JsonPrimitive(value))) { "invalid OfficeId" } }
}

@Serializable
@JvmInline
value class SeatId(val value: String) {
    init { require((pattern("^[a-z]+(-[a-z0-9]+)*$"))(JsonPrimitive(value))) { "invalid SeatId" } }
}

@Serializable
@JvmInline
value class TicketId(val value: String) {
    init { require((pattern("^#[0-9]+$"))(JsonPrimitive(value))) { "invalid TicketId" } }
}

@Serializable
@JvmInline
value class TransitionId(val value: String) {
    init { require((pattern("^T-[0-9]+$"))(JsonPrimitive(value))) { "invalid TransitionId" } }
}

@Serializable
@JvmInline
value class RobotRuleId(val value: String) {
    init { require((pattern("^[A-Z]+-[0-9]+$"))(JsonPrimitive(value))) { "invalid RobotRuleId" } }
}

@Serializable
@JvmInline
value class Timestamp(val value: String) {
    init { require((timestamp)(JsonPrimitive(value))) { "invalid Timestamp" } }
}


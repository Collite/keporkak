package kp.model
import com.agentclientprotocol.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
class SdkCompatibilitySpec : StringSpec({
    "one Kotlin compiler builds against both pinned SDK APIs" {
        Client::class.java.name shouldBe "com.agentclientprotocol.client.Client"
        Server::class.java.name shouldBe "io.modelcontextprotocol.kotlin.sdk.server.Server"
    }
})

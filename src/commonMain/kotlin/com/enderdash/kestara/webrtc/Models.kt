package com.enderdash.kestara.webrtc

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

public enum class DataChannelState { CONNECTING, OPEN, CLOSING, CLOSED }

public enum class PeerConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

public enum class IceConnectionState { NEW, CHECKING, CONNECTED, COMPLETED, DISCONNECTED, FAILED, CLOSED }

public enum class IceGatheringState { NEW, GATHERING, COMPLETE }

public enum class SignalingState {
  UNSPECIFIED,
  STABLE,
  HAVE_LOCAL_OFFER,
  HAVE_REMOTE_OFFER,
  HAVE_LOCAL_PRANSWER,
  HAVE_REMOTE_PRANSWER,
  CLOSED,
}

public enum class IceTransportPolicy { ALL, RELAY }

public enum class IceNetworkType { UDP4, UDP6, TCP4, TCP6 }

public enum class IceMdnsMode { DISABLED, QUERY_ONLY, QUERY_AND_GATHER }

public enum class IceNatMappingType { HOST, SERVER_REFLEXIVE }

public enum class SessionDescriptionType { OFFER, ANSWER, PRANSWER, ROLLBACK }

public enum class DtlsRole { AUTO, CLIENT, SERVER }

public enum class DtlsCipherSuite {
  ECDHE_ECDSA_AES_128_CCM,
  ECDHE_ECDSA_AES_128_CCM_8,
  ECDHE_ECDSA_AES_128_GCM_SHA256,
  ECDHE_RSA_AES_128_GCM_SHA256,
  ECDHE_ECDSA_AES_256_CBC_SHA,
  ECDHE_RSA_AES_256_CBC_SHA,
  ECDHE_RSA_CHACHA20_POLY1305_SHA256,
  ECDHE_ECDSA_CHACHA20_POLY1305_SHA256,
}

public data class IceServer(
  public val urls: List<String>,
  public val username: String = "",
  public val credential: String = "",
) {
  init {
    require(urls.isNotEmpty() && urls.none(String::isBlank)) {
      "urls must contain at least one non-blank URL"
    }
  }

  public constructor(vararg urls: String) : this(urls.toList())

  public companion object {
    public fun authenticated(
      username: String,
      credential: String,
      vararg urls: String,
    ): IceServer = IceServer(urls.toList(), username, credential)
  }
}

public data class IceCandidate(
  public val candidate: String,
  public val sdpMid: String? = null,
  public val sdpMLineIndex: Int? = null,
) {
  init {
    require(sdpMLineIndex == null || sdpMLineIndex >= 0) {
      "sdpMLineIndex must not be negative"
    }
  }
}

public data class SessionDescription(
  public val sdp: String,
  public val type: SessionDescriptionType,
)

public data class IceCredentials(
  public val usernameFragment: String,
  public val password: String,
) {
  init {
    require(usernameFragment.length in 4..256) {
      "ICE username fragment must contain 4 to 256 characters"
    }
    require(password.length in 22..256) {
      "ICE password must contain 22 to 256 characters"
    }
  }
}

public data class IceNatMapping(
  public val externalAddresses: List<String>,
  public val type: IceNatMappingType,
) {
  init {
    require(externalAddresses.isNotEmpty() && externalAddresses.none(String::isBlank)) {
      "A NAT mapping needs at least one external address"
    }
  }
}

public data class DataChannelOptions(
  public val ordered: Boolean = true,
  public val maxPacketLifeTime: Int? = null,
  public val maxRetransmits: Int? = null,
  public val protocol: String = "",
  public val negotiatedId: Int? = null,
) {
  init {
    require(maxPacketLifeTime == null || maxPacketLifeTime in 0..65_535) {
      "maxPacketLifeTime must be between 0 and 65535"
    }
    require(maxRetransmits == null || maxRetransmits in 0..65_535) {
      "maxRetransmits must be between 0 and 65535"
    }
    require(negotiatedId == null || negotiatedId in 0..65_535) {
      "negotiatedId must be between 0 and 65535"
    }
    require(maxPacketLifeTime == null || maxRetransmits == null) {
      "maxPacketLifeTime and maxRetransmits cannot both be set"
    }
  }
}

public data class SctpOptions(
  public val sendBufferLimit: Int = 16 * 1024 * 1024,
  public val receiveBufferSize: Int = 1024 * 1024,
  public val maximumMessageSize: Int = 65_536,
  public val receiveQueueCapacity: Int = 64,
) {
  init {
    require(sendBufferLimit >= 0) { "SCTP send buffer limit must not be negative" }
    require(maximumMessageSize in 1..256 * 1024) {
      "SCTP maximum message size must be between 1 and 262144"
    }
    require(receiveBufferSize >= 1_500 && receiveBufferSize >= maximumMessageSize) {
      "SCTP receive buffer size must be at least 1500 and not less than the maximum message size"
    }
    require(receiveQueueCapacity in 1..65_536) {
      "DataChannel receive queue capacity must be between 1 and 65536"
    }
  }
}

public data class DtlsOptions(
  public val answeringRole: DtlsRole = DtlsRole.AUTO,
  public val mediaLevelFingerprints: Boolean = false,
  public val replayProtectionWindow: Int = 64,
  public val cipherSuites: List<DtlsCipherSuite> = listOf(
    DtlsCipherSuite.ECDHE_ECDSA_AES_128_GCM_SHA256,
    DtlsCipherSuite.ECDHE_ECDSA_CHACHA20_POLY1305_SHA256,
    DtlsCipherSuite.ECDHE_ECDSA_AES_256_CBC_SHA,
  ),
) {
  init {
    require(replayProtectionWindow > 0) { "DTLS replay protection window must be positive" }
    require(cipherSuites.isNotEmpty()) { "At least one DTLS cipher suite is required" }
  }
}

public data class TransportOptions(
  public val udpBindAddresses: List<String> = emptyList(),
  public val tcpBindAddresses: List<String> = emptyList(),
  public val receiveMtu: Int = 0,
) {
  init {
    require(receiveMtu == 0 || receiveMtu >= 576) {
      "Receive MTU must be zero or at least 576 bytes"
    }
  }
}

public data class SharedSocketOptions(
  public val udpBindAddresses: List<String>,
  public val tcpBindAddresses: List<String> = emptyList(),
  public val minPort: Int = 0,
  public val maxPort: Int = 0,
) {
  init {
    require(udpBindAddresses.isNotEmpty() || tcpBindAddresses.isNotEmpty()) {
      "At least one UDP or TCP bind address is required"
    }
    require(udpBindAddresses.none(String::isBlank)) { "UDP bind address must not be blank" }
    require(tcpBindAddresses.none(String::isBlank)) { "TCP bind address must not be blank" }
    require(minPort in 0..65_535 && maxPort in 0..65_535 && (minPort == 0) == (maxPort == 0) && minPort <= maxPort) {
      "Invalid shared socket port range"
    }
  }

  public companion object {
    public val UDP4: SharedSocketOptions = SharedSocketOptions(listOf("0.0.0.0"))
  }
}

public class DtlsCertificate private constructor(public val pem: String) {
  public companion object {
    public fun fromPem(pem: String): DtlsCertificate {
      require(pem.isNotBlank()) { "Certificate PEM must not be blank" }
      return DtlsCertificate(pem)
    }
  }

  override fun toString(): String = "DtlsCertificate[redacted]"
}

public data class WebRtcRuntimeOptions(
  public val workerThreads: Int = 2,
  public val reactorThreads: Int = 1,
  public val shutdownTimeout: Duration = 5.seconds,
  public val certificate: DtlsCertificate? = null,
  public val sharedSockets: SharedSocketOptions? = null,
) {
  init {
    require(workerThreads >= 1) { "Worker thread count must be at least one" }
    require(reactorThreads in 1..1_024) { "Reactor thread count must be between 1 and 1024" }
    require(shutdownTimeout.inWholeMilliseconds >= 1) {
      "Shutdown timeout must be at least one millisecond"
    }
  }
}

public data class IceOptions(
  public val disconnectedTimeout: Duration? = null,
  public val failedTimeout: Duration? = null,
  public val keepAliveInterval: Duration? = null,
  public val checkInterval: Duration? = null,
  public val maxBindingRequests: Int? = null,
  public val hostAcceptanceMinWait: Duration? = null,
  public val serverReflexiveAcceptanceMinWait: Duration? = null,
  public val peerReflexiveAcceptanceMinWait: Duration? = null,
  public val relayAcceptanceMinWait: Duration? = null,
  public val networkTypes: Set<IceNetworkType> = setOf(IceNetworkType.UDP4),
  public val mdnsMode: IceMdnsMode = IceMdnsMode.QUERY_ONLY,
  public val mdnsQueryTimeout: Duration? = null,
  public val lite: Boolean = false,
  public val natMapping: IceNatMapping? = null,
  public val discardLocalCandidatesOnRestart: Boolean = true,
  public val candidatePoolSize: Int = 0,
  public val includeLoopbackCandidate: Boolean = false,
  public val mdnsLocalName: String? = null,
  public val mdnsLocalAddress: String? = null,
  public val credentials: IceCredentials? = null,
) {
  init {
    listOf(disconnectedTimeout, failedTimeout, keepAliveInterval, checkInterval, mdnsQueryTimeout)
      .filterNotNull()
      .forEach { require(it.inWholeMilliseconds >= 1) { "ICE timeout must be at least one millisecond" } }
    listOf(
      hostAcceptanceMinWait,
      serverReflexiveAcceptanceMinWait,
      peerReflexiveAcceptanceMinWait,
      relayAcceptanceMinWait,
    ).filterNotNull().forEach { require(!it.isNegative()) { "ICE acceptance wait must not be negative" } }
    require(maxBindingRequests == null || maxBindingRequests in 0..65_535) {
      "Maximum ICE binding requests must be between 0 and 65535"
    }
    require(networkTypes.isNotEmpty()) { "At least one ICE network type is required" }
    require(candidatePoolSize in 0..1) { "ICE candidate pool size must be 0 or 1" }
    require((mdnsLocalName == null) == (mdnsLocalAddress == null)) {
      "mDNS local name and address must be set together"
    }
    require(mdnsLocalName == null || mdnsLocalName.endsWith(".local")) {
      "mDNS local name must end with .local"
    }
    require(mdnsLocalAddress == null || mdnsLocalAddress.isNotBlank()) {
      "mDNS local address must not be blank"
    }
  }
}

public data class PeerConnectionConfiguration(
  public val iceServers: List<IceServer> = emptyList(),
  public val minPort: Int = 0,
  public val maxPort: Int = 0,
  public val iceTransportPolicy: IceTransportPolicy = IceTransportPolicy.ALL,
  public val iceOptions: IceOptions = IceOptions(),
  public val sctpOptions: SctpOptions = SctpOptions(),
  public val dtlsOptions: DtlsOptions = DtlsOptions(),
  public val transportOptions: TransportOptions = TransportOptions(),
  public val operationTimeout: Duration = 10.seconds,
) {
  init {
    require(
      minPort in 0..65_535 && maxPort in 0..65_535 &&
        (minPort == 0) == (maxPort == 0) && minPort <= maxPort,
    ) { "Invalid transport port range" }
    require(operationTimeout.inWholeMilliseconds >= 1) {
      "Operation timeout must be at least one millisecond"
    }
  }
}

public sealed interface DataChannelMessage {
  public data class Text(public val value: String) : DataChannelMessage

  public class Binary(data: ByteArray) : DataChannelMessage {
    public val data: ByteArray = data.copyOf()

    override fun equals(other: Any?): Boolean = other is Binary && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "Binary(size=${data.size})"
  }
}

public sealed interface DataChannelEvent {
  public data object Open : DataChannelEvent
  public data object Closing : DataChannelEvent
  public data object Closed : DataChannelEvent
  public data class Error(public val message: String) : DataChannelEvent
  public data object BufferedAmountLow : DataChannelEvent
  public data object BufferedAmountHigh : DataChannelEvent
}

public data class DataChannelStats(
  public val identifier: Int,
  public val label: String,
  public val protocol: String,
  public val state: String,
  public val messagesSent: Long,
  public val bytesSent: Long,
  public val messagesReceived: Long,
  public val bytesReceived: Long,
)

public data class IceCandidateStats(
  public val id: String,
  public val address: String,
  public val port: Int,
  public val protocol: String,
  public val candidateType: String,
  public val priority: Long,
  public val url: String,
  public val relayProtocol: String,
  public val foundation: String,
  public val relatedAddress: String,
  public val relatedPort: Int,
  public val usernameFragment: String,
  public val tcpType: String,
)

public data class IceCandidatePairStats(
  public val id: String,
  public val localCandidateId: String,
  public val remoteCandidateId: String,
  public val localCandidate: IceCandidateStats?,
  public val remoteCandidate: IceCandidateStats?,
  public val packetsSent: Long,
  public val packetsReceived: Long,
  public val bytesSent: Long,
  public val bytesReceived: Long,
  public val currentRoundTripTimeSeconds: Double,
  public val totalRoundTripTimeSeconds: Double,
  public val requestsSent: Long,
  public val requestsReceived: Long,
  public val responsesSent: Long,
  public val responsesReceived: Long,
  public val state: String,
  public val nominated: Boolean,
)

public data class TransportStats(
  public val packetsSent: Long,
  public val packetsReceived: Long,
  public val bytesSent: Long,
  public val bytesReceived: Long,
  public val iceRole: String,
  public val iceState: String,
  public val dtlsRole: String,
  public val dtlsState: String,
  public val tlsVersion: String,
  public val dtlsCipher: String,
  public val selectedCandidatePairChanges: Long,
  public val selectedCandidatePair: IceCandidatePairStats?,
)

public data class PeerConnectionStats(
  public val timestamp: Instant,
  public val dataChannelsOpened: Long,
  public val dataChannelsClosed: Long,
  public val transport: TransportStats,
  public val dataChannels: List<DataChannelStats>,
)

public data class WebRtcRuntimeDiagnostics(
  public val workerThreads: Int,
  public val reactorThreads: Int,
  public val peerConnections: Int,
  public val dataChannels: Int,
  public val pendingOperations: Int,
  public val closing: Boolean,
  public val closed: Boolean,
)

public class WebRtcException : RuntimeException {
  public constructor(message: String) : super(message)
  public constructor(message: String, cause: Throwable) : super(message, cause)
}

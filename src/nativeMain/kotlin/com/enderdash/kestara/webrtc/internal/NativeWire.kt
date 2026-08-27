package com.enderdash.kestara.webrtc.internal

import com.enderdash.kestara.webrtc.DataChannelOptions
import com.enderdash.kestara.webrtc.PeerConnectionConfiguration
import com.enderdash.kestara.webrtc.WebRtcRuntimeOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object NativeWire {
  private val json = Json { encodeDefaults = true }

  fun runtime(options: WebRtcRuntimeOptions): ByteArray {
    val sockets = options.sharedSockets
    return json.encodeToString(
      RuntimeDto(
        workerThreads = options.workerThreads,
        reactorThreads = options.reactorThreads,
        certificatePem = options.certificate?.pem,
        sharedUdpAddresses = sockets?.udpBindAddresses.orEmpty(),
        sharedTcpAddresses = sockets?.tcpBindAddresses.orEmpty(),
        sharedMinPort = sockets?.minPort ?: 0,
        sharedMaxPort = sockets?.maxPort ?: 0,
      ),
    ).encodeToByteArray()
  }

  fun peer(configuration: PeerConnectionConfiguration): ByteArray {
    val ice = configuration.iceOptions
    val sctp = configuration.sctpOptions
    val dtls = configuration.dtlsOptions
    val transport = configuration.transportOptions
    return json.encodeToString(
      PeerDto(
        iceServers = configuration.iceServers.map {
          IceServerDto(it.urls, it.username, it.credential)
        },
        minPort = configuration.minPort,
        maxPort = configuration.maxPort,
        relayOnly = configuration.iceTransportPolicy.ordinal == 1,
        disconnectedTimeoutMillis = ice.disconnectedTimeout.optionalMillis(),
        failedTimeoutMillis = ice.failedTimeout.optionalMillis(),
        keepAliveIntervalMillis = ice.keepAliveInterval.optionalMillis(),
        checkIntervalMillis = ice.checkInterval.optionalMillis(),
        maxBindingRequests = ice.maxBindingRequests ?: -1,
        hostAcceptanceMinWaitMillis = ice.hostAcceptanceMinWait.optionalMillis(),
        serverReflexiveAcceptanceMinWaitMillis = ice.serverReflexiveAcceptanceMinWait.optionalMillis(),
        peerReflexiveAcceptanceMinWaitMillis = ice.peerReflexiveAcceptanceMinWait.optionalMillis(),
        relayAcceptanceMinWaitMillis = ice.relayAcceptanceMinWait.optionalMillis(),
        networkTypeMask = ice.networkTypes.fold(0) { mask, type -> mask or (1 shl type.ordinal) },
        mdnsMode = ice.mdnsMode.ordinal,
        mdnsQueryTimeoutMillis = ice.mdnsQueryTimeout.optionalMillis(),
        iceLite = ice.lite,
        natMapping = ice.natMapping?.let { NatMappingDto(it.externalAddresses, it.type.ordinal) },
        discardLocalCandidatesOnRestart = ice.discardLocalCandidatesOnRestart,
        candidatePoolSize = ice.candidatePoolSize,
        includeLoopbackCandidate = ice.includeLoopbackCandidate,
        mdnsLocalName = ice.mdnsLocalName,
        mdnsLocalAddress = ice.mdnsLocalAddress,
        credentials = ice.credentials?.let { CredentialsDto(it.usernameFragment, it.password) },
        sctpSendBufferLimit = sctp.sendBufferLimit,
        sctpReceiveBufferSize = sctp.receiveBufferSize,
        sctpMaximumMessageSize = sctp.maximumMessageSize,
        receiveQueueCapacity = sctp.receiveQueueCapacity,
        dtlsAnsweringRole = dtls.answeringRole.ordinal,
        mediaLevelFingerprints = dtls.mediaLevelFingerprints,
        dtlsReplayProtectionWindow = dtls.replayProtectionWindow,
        dtlsCipherSuiteMask = dtls.cipherSuites.fold(0) { mask, suite ->
          mask or (1 shl suite.ordinal)
        },
        udpBindAddresses = transport.udpBindAddresses,
        tcpBindAddresses = transport.tcpBindAddresses,
        receiveMtu = transport.receiveMtu,
      ),
    ).encodeToByteArray()
  }

  fun dataChannel(options: DataChannelOptions): ByteArray = json.encodeToString(
    DataChannelDto(
      ordered = options.ordered,
      maxPacketLifeTime = options.maxPacketLifeTime ?: -1,
      maxRetransmits = options.maxRetransmits ?: -1,
      protocol = options.protocol,
      negotiatedId = options.negotiatedId ?: -1,
    ),
  ).encodeToByteArray()

  private fun kotlin.time.Duration?.optionalMillis(): Long = this?.inWholeMilliseconds ?: -1
}

@Serializable
private data class RuntimeDto(
  val workerThreads: Int,
  val reactorThreads: Int,
  val certificatePem: String?,
  val sharedUdpAddresses: List<String>,
  val sharedTcpAddresses: List<String>,
  val sharedMinPort: Int,
  val sharedMaxPort: Int,
)

@Serializable
private data class IceServerDto(val urls: List<String>, val username: String, val credential: String)

@Serializable
private data class NatMappingDto(val addresses: List<String>, val mappingType: Int)

@Serializable
private data class CredentialsDto(val usernameFragment: String, val password: String)

@Serializable
private data class PeerDto(
  val iceServers: List<IceServerDto>,
  val minPort: Int,
  val maxPort: Int,
  val relayOnly: Boolean,
  val disconnectedTimeoutMillis: Long,
  val failedTimeoutMillis: Long,
  val keepAliveIntervalMillis: Long,
  val checkIntervalMillis: Long,
  val maxBindingRequests: Int,
  val hostAcceptanceMinWaitMillis: Long,
  val serverReflexiveAcceptanceMinWaitMillis: Long,
  val peerReflexiveAcceptanceMinWaitMillis: Long,
  val relayAcceptanceMinWaitMillis: Long,
  val networkTypeMask: Int,
  val mdnsMode: Int,
  val mdnsQueryTimeoutMillis: Long,
  val iceLite: Boolean,
  val natMapping: NatMappingDto?,
  val discardLocalCandidatesOnRestart: Boolean,
  val candidatePoolSize: Int,
  val includeLoopbackCandidate: Boolean,
  val mdnsLocalName: String?,
  val mdnsLocalAddress: String?,
  val credentials: CredentialsDto?,
  val sctpSendBufferLimit: Int,
  val sctpReceiveBufferSize: Int,
  val sctpMaximumMessageSize: Int,
  val receiveQueueCapacity: Int,
  val dtlsAnsweringRole: Int,
  val mediaLevelFingerprints: Boolean,
  val dtlsReplayProtectionWindow: Int,
  val dtlsCipherSuiteMask: Int,
  val udpBindAddresses: List<String>,
  val tcpBindAddresses: List<String>,
  val receiveMtu: Int,
)

@Serializable
private data class DataChannelDto(
  val ordered: Boolean,
  val maxPacketLifeTime: Int,
  val maxRetransmits: Int,
  val protocol: String,
  val negotiatedId: Int,
)

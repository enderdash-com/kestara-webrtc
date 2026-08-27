package com.enderdash.kestara.webrtc.internal

import java.nio.ByteBuffer

internal data class JvmNativeEvent(
  val kind: Int,
  val peerHandle: Long,
  val channelHandle: Long,
  val operationHandle: Long,
  val text: String?,
  val secondaryText: String?,
  val number: Int,
  val data: ByteArray?,
  val messageHandle: Long,
  val directData: ByteBuffer?,
)

internal object NativeBindings {
  init {
    NativeLibraryLoader.load()
  }

  @JvmStatic external fun nativeCreateRuntime(
    workerThreads: Int,
    reactorThreads: Int,
    certificatePem: String,
    sharedUdpAddresses: Array<String>,
    sharedTcpAddresses: Array<String>,
    sharedMinPort: Int,
    sharedMaxPort: Int,
  ): Long

  @JvmStatic external fun nativeRuntimeCertificateFingerprint(runtime: Long): String
  @JvmStatic external fun nativeRuntimeCertificatePem(runtime: Long): String
  @JvmStatic external fun nativeSubmitCreatePeer(
    runtime: Long,
    operation: Long,
    urls: Array<String>,
    usernames: Array<String>,
    credentials: Array<String>,
    minPort: Int,
    maxPort: Int,
    iceTransportPolicy: Int,
    disconnectedTimeoutMillis: Long,
    failedTimeoutMillis: Long,
    keepAliveIntervalMillis: Long,
    checkIntervalMillis: Long,
    maxBindingRequests: Int,
    hostAcceptanceMinWaitMillis: Long,
    serverReflexiveAcceptanceMinWaitMillis: Long,
    peerReflexiveAcceptanceMinWaitMillis: Long,
    relayAcceptanceMinWaitMillis: Long,
    networkTypeMask: Int,
    mdnsMode: Int,
    mdnsQueryTimeoutMillis: Long,
    iceLite: Boolean,
    natAddresses: Array<String>,
    natMappingType: Int,
    discardLocalCandidatesOnRestart: Boolean,
    candidatePoolSize: Int,
    includeLoopbackCandidate: Boolean,
    mdnsLocalName: String,
    mdnsLocalAddress: String,
    iceUsernameFragment: String,
    icePassword: String,
    sctpSendBufferLimit: Int,
    sctpReceiveBufferSize: Int,
    sctpMaximumMessageSize: Int,
    dataChannelReceiveQueueCapacity: Int,
    dtlsAnsweringRole: Int,
    mediaLevelFingerprints: Boolean,
    dtlsReplayProtectionWindow: Int,
    dtlsCipherSuiteMask: Int,
    udpBindAddresses: Array<String>,
    tcpBindAddresses: Array<String>,
    receiveMtu: Int,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitRestartIce(
    runtime: Long,
    operation: Long,
    peer: Long,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitSetConfiguration(
    runtime: Long,
    operation: Long,
    peer: Long,
    urls: Array<String>,
    usernames: Array<String>,
    credentials: Array<String>,
    iceTransportPolicy: Int,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitCreateDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    type: Int,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitSetLocalDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    sdp: String,
    type: Int,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitSetRemoteDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    sdp: String,
    type: Int,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitAddIceCandidate(
    runtime: Long,
    operation: Long,
    peer: Long,
    candidate: String,
    sdpMid: String?,
    sdpMLineIndex: Int,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitCreateDataChannel(
    runtime: Long,
    operation: Long,
    peer: Long,
    label: String,
    ordered: Boolean,
    maxPacketLifeTime: Int,
    maxRetransmits: Int,
    protocol: String,
    negotiatedId: Int,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitSendDataChannelText(
    runtime: Long,
    operation: Long,
    channel: Long,
    text: String,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitSendDataChannelBinary(
    runtime: Long,
    operation: Long,
    channel: Long,
    data: ByteArray,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitTrySendDataChannelText(
    runtime: Long,
    operation: Long,
    channel: Long,
    text: String,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitTrySendDataChannelBinary(
    runtime: Long,
    operation: Long,
    channel: Long,
    data: ByteArray,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeReleaseBuffer(runtime: Long, buffer: Long)
  @JvmStatic external fun nativeSubmitDataChannelWritable(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitDataChannelOutstandingBytes(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitSetDataChannelThresholds(
    runtime: Long,
    operation: Long,
    channel: Long,
    low: Long,
    high: Long,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitGetStats(
    runtime: Long,
    operation: Long,
    peer: Long,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitRotateCertificate(
    runtime: Long,
    operation: Long,
    certificatePem: String,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitCloseDataChannel(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitClosePeer(
    runtime: Long,
    operation: Long,
    peer: Long,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativeSubmitCloseRuntime(
    runtime: Long,
    operation: Long,
    timeoutMillis: Long,
  )
  @JvmStatic external fun nativePollRuntimeEvent(runtime: Long, timeoutMillis: Long): JvmNativeEvent?
  @JvmStatic external fun nativeWakeRuntime(runtime: Long)
  @JvmStatic external fun nativeReleaseRuntime(runtime: Long)
  @JvmStatic external fun nativeAbiVersion(): Int
  @JvmStatic external fun nativeLibraryVersion(): String
}

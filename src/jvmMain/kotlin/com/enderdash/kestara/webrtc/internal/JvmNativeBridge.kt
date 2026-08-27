package com.enderdash.kestara.webrtc.internal

import com.enderdash.kestara.webrtc.DataChannelOptions
import com.enderdash.kestara.webrtc.DtlsCertificate
import com.enderdash.kestara.webrtc.PeerConnectionConfiguration
import com.enderdash.kestara.webrtc.SessionDescription
import com.enderdash.kestara.webrtc.SessionDescriptionType
import com.enderdash.kestara.webrtc.WebRtcRuntimeOptions
import kotlin.time.Duration

internal actual fun platformNativeBridge(): NativeBridge = JvmNativeBridge

private object JvmNativeBridge : NativeBridge {
  override val abiVersion: Int get() = NativeBindings.nativeAbiVersion()
  override val libraryVersion: String get() = NativeBindings.nativeLibraryVersion()

  override fun createRuntime(options: WebRtcRuntimeOptions): Long {
    val sockets = options.sharedSockets
    return NativeBindings.nativeCreateRuntime(
      options.workerThreads,
      options.reactorThreads,
      options.certificate?.pem.orEmpty(),
      sockets?.udpBindAddresses?.toTypedArray() ?: emptyArray(),
      sockets?.tcpBindAddresses?.toTypedArray() ?: emptyArray(),
      sockets?.minPort ?: 0,
      sockets?.maxPort ?: 0,
    )
  }

  override fun runtimeCertificateFingerprint(runtime: Long): String =
    NativeBindings.nativeRuntimeCertificateFingerprint(runtime)

  override fun runtimeCertificatePem(runtime: Long): String =
    NativeBindings.nativeRuntimeCertificatePem(runtime)

  override fun submitCreatePeer(
    runtime: Long,
    operation: Long,
    configuration: PeerConnectionConfiguration,
  ) {
    val servers = configuration.flattenedServers()
    val ice = configuration.iceOptions
    val sctp = configuration.sctpOptions
    val dtls = configuration.dtlsOptions
    val transport = configuration.transportOptions
    NativeBindings.nativeSubmitCreatePeer(
      runtime,
      operation,
      servers.urls,
      servers.usernames,
      servers.credentials,
      configuration.minPort,
      configuration.maxPort,
      configuration.iceTransportPolicy.ordinal,
      ice.disconnectedTimeout.optionalMillis(),
      ice.failedTimeout.optionalMillis(),
      ice.keepAliveInterval.optionalMillis(),
      ice.checkInterval.optionalMillis(),
      ice.maxBindingRequests ?: -1,
      ice.hostAcceptanceMinWait.optionalMillis(),
      ice.serverReflexiveAcceptanceMinWait.optionalMillis(),
      ice.peerReflexiveAcceptanceMinWait.optionalMillis(),
      ice.relayAcceptanceMinWait.optionalMillis(),
      ice.networkTypes.fold(0) { mask, type -> mask or (1 shl type.ordinal) },
      ice.mdnsMode.ordinal,
      ice.mdnsQueryTimeout.optionalMillis(),
      ice.lite,
      ice.natMapping?.externalAddresses?.toTypedArray() ?: emptyArray(),
      ice.natMapping?.type?.ordinal ?: -1,
      ice.discardLocalCandidatesOnRestart,
      ice.candidatePoolSize,
      ice.includeLoopbackCandidate,
      ice.mdnsLocalName.orEmpty(),
      ice.mdnsLocalAddress.orEmpty(),
      ice.credentials?.usernameFragment.orEmpty(),
      ice.credentials?.password.orEmpty(),
      sctp.sendBufferLimit,
      sctp.receiveBufferSize,
      sctp.maximumMessageSize,
      sctp.receiveQueueCapacity,
      dtls.answeringRole.ordinal,
      dtls.mediaLevelFingerprints,
      dtls.replayProtectionWindow,
      dtls.cipherSuites.fold(0) { mask, suite -> mask or (1 shl suite.ordinal) },
      transport.udpBindAddresses.toTypedArray(),
      transport.tcpBindAddresses.toTypedArray(),
      transport.receiveMtu,
      configuration.operationTimeout.inWholeMilliseconds,
    )
  }

  override fun submitRestartIce(runtime: Long, operation: Long, peer: Long, timeout: Duration) {
    NativeBindings.nativeSubmitRestartIce(runtime, operation, peer, timeout.inWholeMilliseconds)
  }

  override fun submitSetConfiguration(
    runtime: Long,
    operation: Long,
    peer: Long,
    configuration: PeerConnectionConfiguration,
    timeout: Duration,
  ) {
    val servers = configuration.flattenedServers()
    NativeBindings.nativeSubmitSetConfiguration(
      runtime,
      operation,
      peer,
      servers.urls,
      servers.usernames,
      servers.credentials,
      configuration.iceTransportPolicy.ordinal,
      timeout.inWholeMilliseconds,
    )
  }

  override fun submitCreateDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    type: SessionDescriptionType,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitCreateDescription(
      runtime, operation, peer, type.ordinal, timeout.inWholeMilliseconds,
    )
  }

  override fun submitSetLocalDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    description: SessionDescription,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitSetLocalDescription(
      runtime, operation, peer, description.sdp, description.type.ordinal, timeout.inWholeMilliseconds,
    )
  }

  override fun submitSetRemoteDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    description: SessionDescription,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitSetRemoteDescription(
      runtime, operation, peer, description.sdp, description.type.ordinal, timeout.inWholeMilliseconds,
    )
  }

  override fun submitAddIceCandidate(
    runtime: Long,
    operation: Long,
    peer: Long,
    candidate: String,
    sdpMid: String?,
    sdpMLineIndex: Int?,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitAddIceCandidate(
      runtime,
      operation,
      peer,
      candidate,
      sdpMid,
      sdpMLineIndex ?: -1,
      timeout.inWholeMilliseconds,
    )
  }

  override fun submitCreateDataChannel(
    runtime: Long,
    operation: Long,
    peer: Long,
    label: String,
    options: DataChannelOptions,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitCreateDataChannel(
      runtime,
      operation,
      peer,
      label,
      options.ordered,
      options.maxPacketLifeTime ?: -1,
      options.maxRetransmits ?: -1,
      options.protocol,
      options.negotiatedId ?: -1,
      timeout.inWholeMilliseconds,
    )
  }

  override fun submitSendText(
    runtime: Long,
    operation: Long,
    channel: Long,
    text: String,
    timeout: Duration,
    trySend: Boolean,
  ) {
    if (trySend) {
      NativeBindings.nativeSubmitTrySendDataChannelText(
        runtime, operation, channel, text, timeout.inWholeMilliseconds,
      )
    } else {
      NativeBindings.nativeSubmitSendDataChannelText(
        runtime, operation, channel, text, timeout.inWholeMilliseconds,
      )
    }
  }

  override fun submitSendBinary(
    runtime: Long,
    operation: Long,
    channel: Long,
    data: ByteArray,
    timeout: Duration,
    trySend: Boolean,
  ) {
    if (trySend) {
      NativeBindings.nativeSubmitTrySendDataChannelBinary(
        runtime, operation, channel, data, timeout.inWholeMilliseconds,
      )
    } else {
      NativeBindings.nativeSubmitSendDataChannelBinary(
        runtime, operation, channel, data, timeout.inWholeMilliseconds,
      )
    }
  }

  override fun submitDataChannelWritable(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitDataChannelWritable(
      runtime, operation, channel, timeout.inWholeMilliseconds,
    )
  }

  override fun submitDataChannelOutstandingBytes(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitDataChannelOutstandingBytes(
      runtime, operation, channel, timeout.inWholeMilliseconds,
    )
  }

  override fun submitSetDataChannelThresholds(
    runtime: Long,
    operation: Long,
    channel: Long,
    low: Long,
    high: Long,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitSetDataChannelThresholds(
      runtime, operation, channel, low, high, timeout.inWholeMilliseconds,
    )
  }

  override fun submitGetStats(runtime: Long, operation: Long, peer: Long, timeout: Duration) {
    NativeBindings.nativeSubmitGetStats(runtime, operation, peer, timeout.inWholeMilliseconds)
  }

  override fun submitRotateCertificate(
    runtime: Long,
    operation: Long,
    certificate: DtlsCertificate?,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitRotateCertificate(
      runtime, operation, certificate?.pem.orEmpty(), timeout.inWholeMilliseconds,
    )
  }

  override fun submitCloseDataChannel(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeout: Duration,
  ) {
    NativeBindings.nativeSubmitCloseDataChannel(runtime, operation, channel, timeout.inWholeMilliseconds)
  }

  override fun submitClosePeer(runtime: Long, operation: Long, peer: Long, timeout: Duration) {
    NativeBindings.nativeSubmitClosePeer(runtime, operation, peer, timeout.inWholeMilliseconds)
  }

  override fun submitCloseRuntime(runtime: Long, operation: Long, timeout: Duration) {
    NativeBindings.nativeSubmitCloseRuntime(runtime, operation, timeout.inWholeMilliseconds)
  }

  override fun pollRuntimeEvent(runtime: Long, timeout: Duration): NativeEvent? {
    val event = NativeBindings.nativePollRuntimeEvent(runtime, timeout.inWholeMilliseconds) ?: return null
    return try {
      val copiedData = event.directData?.let { buffer ->
        val view = buffer.duplicate()
        ByteArray(view.remaining()).also(view::get)
      } ?: event.data
      NativeEvent(
        kind = event.kind,
        peerHandle = event.peerHandle,
        channelHandle = event.channelHandle,
        operationHandle = event.operationHandle,
        text = event.text,
        secondaryText = event.secondaryText,
        number = event.number,
        data = copiedData,
      )
    } finally {
      if (event.messageHandle != 0L) {
        NativeBindings.nativeReleaseBuffer(runtime, event.messageHandle)
      }
    }
  }

  override fun wakeRuntime(runtime: Long) = NativeBindings.nativeWakeRuntime(runtime)
  override fun releaseRuntime(runtime: Long) = NativeBindings.nativeReleaseRuntime(runtime)

  private fun PeerConnectionConfiguration.flattenedServers(): FlattenedServers {
    val urls = mutableListOf<String>()
    val usernames = mutableListOf<String>()
    val credentials = mutableListOf<String>()
    iceServers.forEach { server ->
      server.urls.forEach { url ->
        urls += url
        usernames += server.username
        credentials += server.credential
      }
    }
    return FlattenedServers(urls.toTypedArray(), usernames.toTypedArray(), credentials.toTypedArray())
  }

  private fun Duration?.optionalMillis(): Long = this?.inWholeMilliseconds ?: -1

  private data class FlattenedServers(
    val urls: Array<String>,
    val usernames: Array<String>,
    val credentials: Array<String>,
  )
}

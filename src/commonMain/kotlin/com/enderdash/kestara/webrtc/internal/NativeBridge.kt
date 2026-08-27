package com.enderdash.kestara.webrtc.internal

import com.enderdash.kestara.webrtc.DataChannelOptions
import com.enderdash.kestara.webrtc.DtlsCertificate
import com.enderdash.kestara.webrtc.PeerConnectionConfiguration
import com.enderdash.kestara.webrtc.SessionDescription
import com.enderdash.kestara.webrtc.SessionDescriptionType
import com.enderdash.kestara.webrtc.WebRtcRuntimeOptions
import kotlin.time.Duration

internal data class NativeEvent(
  val kind: Int,
  val peerHandle: Long = 0,
  val channelHandle: Long = 0,
  val operationHandle: Long = 0,
  val text: String? = null,
  val secondaryText: String? = null,
  val number: Int = 0,
  val data: ByteArray? = null,
)

internal object NativeEventKind {
  const val LOCAL_CANDIDATE = 1
  const val PEER_STATE = 2
  const val ICE_CONNECTION_STATE = 3
  const val ICE_GATHERING_STATE = 4
  const val DATA_CHANNEL = 5
  const val DATA_CHANNEL_OPEN = 6
  const val DATA_CHANNEL_CLOSING = 7
  const val DATA_CHANNEL_CLOSED = 8
  const val DATA_CHANNEL_ERROR = 9
  const val DATA_CHANNEL_TEXT = 10
  const val DATA_CHANNEL_BINARY = 11
  const val OPERATION_COMPLETE = 12
  const val DATA_CHANNEL_BUFFERED_AMOUNT_LOW = 13
  const val DATA_CHANNEL_BUFFERED_AMOUNT_HIGH = 14
  const val NEGOTIATION_NEEDED = 15
  const val SIGNALING_STATE = 16
}

internal interface NativeBridge {
  val abiVersion: Int
  val libraryVersion: String

  fun createRuntime(options: WebRtcRuntimeOptions): Long
  fun runtimeCertificateFingerprint(runtime: Long): String
  fun runtimeCertificatePem(runtime: Long): String
  fun submitCreatePeer(
    runtime: Long,
    operation: Long,
    configuration: PeerConnectionConfiguration,
  )
  fun submitRestartIce(runtime: Long, operation: Long, peer: Long, timeout: Duration)
  fun submitSetConfiguration(
    runtime: Long,
    operation: Long,
    peer: Long,
    configuration: PeerConnectionConfiguration,
    timeout: Duration,
  )
  fun submitCreateDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    type: SessionDescriptionType,
    timeout: Duration,
  )
  fun submitSetLocalDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    description: SessionDescription,
    timeout: Duration,
  )
  fun submitSetRemoteDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    description: SessionDescription,
    timeout: Duration,
  )
  fun submitAddIceCandidate(
    runtime: Long,
    operation: Long,
    peer: Long,
    candidate: String,
    sdpMid: String?,
    sdpMLineIndex: Int?,
    timeout: Duration,
  )
  fun submitCreateDataChannel(
    runtime: Long,
    operation: Long,
    peer: Long,
    label: String,
    options: DataChannelOptions,
    timeout: Duration,
  )
  fun submitSendText(
    runtime: Long,
    operation: Long,
    channel: Long,
    text: String,
    timeout: Duration,
    trySend: Boolean,
  )
  fun submitSendBinary(
    runtime: Long,
    operation: Long,
    channel: Long,
    data: ByteArray,
    timeout: Duration,
    trySend: Boolean,
  )
  fun submitDataChannelWritable(runtime: Long, operation: Long, channel: Long, timeout: Duration)
  fun submitDataChannelOutstandingBytes(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeout: Duration,
  )
  fun submitSetDataChannelThresholds(
    runtime: Long,
    operation: Long,
    channel: Long,
    low: Long,
    high: Long,
    timeout: Duration,
  )
  fun submitGetStats(runtime: Long, operation: Long, peer: Long, timeout: Duration)
  fun submitRotateCertificate(
    runtime: Long,
    operation: Long,
    certificate: DtlsCertificate?,
    timeout: Duration,
  )
  fun submitCloseDataChannel(runtime: Long, operation: Long, channel: Long, timeout: Duration)
  fun submitClosePeer(runtime: Long, operation: Long, peer: Long, timeout: Duration)
  fun submitCloseRuntime(runtime: Long, operation: Long, timeout: Duration)
  fun pollRuntimeEvent(runtime: Long, timeout: Duration): NativeEvent?
  fun wakeRuntime(runtime: Long)
  fun releaseRuntime(runtime: Long)
}

internal expect fun platformNativeBridge(): NativeBridge

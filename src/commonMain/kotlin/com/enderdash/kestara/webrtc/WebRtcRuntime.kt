package com.enderdash.kestara.webrtc

import com.enderdash.kestara.webrtc.internal.NativeBridge
import com.enderdash.kestara.webrtc.internal.NativeEvent
import com.enderdash.kestara.webrtc.internal.NativeEventKind
import com.enderdash.kestara.webrtc.internal.StatsDecoder
import com.enderdash.kestara.webrtc.internal.platformNativeBridge
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public class WebRtcRuntime private constructor(
  public val options: WebRtcRuntimeOptions,
  private val bridge: NativeBridge,
  private val handle: Long,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val lifecycleMutex = Mutex()
  private val peers = mutableMapOf<Long, PeerConnection>()
  private val queuedPeerEvents = mutableMapOf<Long, MutableList<NativeEvent>>()
  private val operations = mutableMapOf<Long, CompletableDeferred<NativeEvent>>()
  private val nextOperation = atomic(1L)
  private val closing = atomic(false)
  private val closed = atomic(false)
  private val closeCompletion = CompletableDeferred<Unit>()
  private val eventLoop = scope.launch { dispatchEvents() }

  private val fingerprint = atomic(bridge.runtimeCertificateFingerprint(handle))

  public val certificateFingerprint: String get() = fingerprint.value

  public val certificate: DtlsCertificate
    get() = DtlsCertificate.fromPem(bridge.runtimeCertificatePem(handle))

  public suspend fun rotateCertificate(certificate: DtlsCertificate? = null): String {
    val rotated = submit(options.shutdownTimeout) { operation ->
      bridge.submitRotateCertificate(handle, operation, certificate, options.shutdownTimeout)
    }.text ?: throw WebRtcException("The native runtime did not return a certificate fingerprint")
    fingerprint.value = rotated
    return rotated
  }

  public suspend fun createPeerConnection(
    configuration: PeerConnectionConfiguration = PeerConnectionConfiguration(),
  ): PeerConnection {
    val event = submit(configuration.operationTimeout) { operation ->
      bridge.submitCreatePeer(handle, operation, configuration)
    }
    val peer = PeerConnection(this, event.peerHandle, configuration)
    val queued = lifecycleMutex.withLock {
      check(!closed.value) { "WebRtcRuntime is closed" }
      peers[event.peerHandle] = peer
      queuedPeerEvents.remove(event.peerHandle).orEmpty()
    }
    queued.forEach { peer.handleNativeEvent(it) }
    return peer
  }

  public suspend fun diagnostics(): WebRtcRuntimeDiagnostics = lifecycleMutex.withLock {
    WebRtcRuntimeDiagnostics(
      workerThreads = options.workerThreads,
      reactorThreads = options.reactorThreads,
      peerConnections = peers.size,
      dataChannels = peers.values.sumOf(PeerConnection::dataChannelCount),
      pendingOperations = operations.size,
      closing = closing.value,
      closed = closed.value,
    )
  }

  public suspend fun close() {
    if (closing.compareAndSet(expect = false, update = true)) {
      try {
        submit(options.shutdownTimeout, allowClosing = true) { operation ->
          bridge.submitCloseRuntime(handle, operation, options.shutdownTimeout)
        }
        completeShutdown(null)
      } catch (error: Throwable) {
        completeShutdown(error)
      }
    }
    closeCompletion.await()
  }

  internal suspend fun createDescription(
    peer: Long,
    type: SessionDescriptionType,
    timeout: Duration,
  ): String = submit(timeout) { operation ->
    bridge.submitCreateDescription(handle, operation, peer, type, timeout)
  }.text ?: throw WebRtcException("The native runtime did not return a session description")

  internal suspend fun setLocalDescription(
    peer: Long,
    description: SessionDescription,
    timeout: Duration,
  ) {
    submit(timeout) { operation ->
      bridge.submitSetLocalDescription(handle, operation, peer, description, timeout)
    }
  }

  internal suspend fun setRemoteDescription(
    peer: Long,
    description: SessionDescription,
    timeout: Duration,
  ) {
    submit(timeout) { operation ->
      bridge.submitSetRemoteDescription(handle, operation, peer, description, timeout)
    }
  }

  internal suspend fun addIceCandidate(peer: Long, candidate: IceCandidate, timeout: Duration) {
    submit(timeout) { operation ->
      bridge.submitAddIceCandidate(
        handle,
        operation,
        peer,
        candidate.candidate,
        candidate.sdpMid,
        candidate.sdpMLineIndex,
        timeout,
      )
    }
  }

  internal suspend fun restartIce(peer: Long, timeout: Duration) {
    submit(timeout) { operation -> bridge.submitRestartIce(handle, operation, peer, timeout) }
  }

  internal suspend fun setConfiguration(
    peer: Long,
    configuration: PeerConnectionConfiguration,
    timeout: Duration,
  ) {
    submit(timeout) { operation ->
      bridge.submitSetConfiguration(handle, operation, peer, configuration, timeout)
    }
  }

  internal suspend fun createDataChannel(
    peer: Long,
    label: String,
    options: DataChannelOptions,
    timeout: Duration,
  ): ChannelRegistration {
    val event = submit(timeout) { operation ->
      bridge.submitCreateDataChannel(handle, operation, peer, label, options, timeout)
    }
    return ChannelRegistration(event.channelHandle, event.text?.toIntOrNull() ?: -1)
  }

  internal suspend fun sendText(
    channel: Long,
    text: String,
    timeout: Duration,
    trySend: Boolean,
  ): Boolean {
    val event = submit(timeout) { operation ->
      bridge.submitSendText(handle, operation, channel, text, timeout, trySend)
    }
    return !trySend || event.text.toBoolean()
  }

  internal suspend fun sendBinary(
    channel: Long,
    data: ByteArray,
    timeout: Duration,
    trySend: Boolean,
  ): Boolean {
    val event = submit(timeout) { operation ->
      bridge.submitSendBinary(handle, operation, channel, data, timeout, trySend)
    }
    return !trySend || event.text.toBoolean()
  }

  internal suspend fun awaitWritable(channel: Long, timeout: Duration) {
    submit(timeout) { operation ->
      bridge.submitDataChannelWritable(handle, operation, channel, timeout)
    }
  }

  internal suspend fun outstandingBytes(channel: Long, timeout: Duration): Long =
    submit(timeout) { operation ->
      bridge.submitDataChannelOutstandingBytes(handle, operation, channel, timeout)
    }.text?.toULongOrNull()?.toLong() ?: 0

  internal suspend fun setDataChannelThresholds(
    channel: Long,
    low: Long,
    high: Long,
    timeout: Duration,
  ) {
    submit(timeout) { operation ->
      bridge.submitSetDataChannelThresholds(handle, operation, channel, low, high, timeout)
    }
  }

  internal suspend fun getStats(peer: Long, timeout: Duration): PeerConnectionStats {
    val data = submit(timeout) { operation ->
      bridge.submitGetStats(handle, operation, peer, timeout)
    }.data ?: throw WebRtcException("The native runtime did not return peer statistics")
    return StatsDecoder.decode(data)
  }

  internal suspend fun closeDataChannel(channel: Long, timeout: Duration) {
    submit(timeout) { operation ->
      bridge.submitCloseDataChannel(handle, operation, channel, timeout)
    }
  }

  internal suspend fun closePeer(peer: Long, timeout: Duration) {
    submit(timeout) { operation -> bridge.submitClosePeer(handle, operation, peer, timeout) }
  }

  internal suspend fun unregisterPeer(handle: Long, peer: PeerConnection) {
    lifecycleMutex.withLock {
      if (peers[handle] === peer) peers.remove(handle)
    }
  }

  private suspend fun submit(
    timeout: Duration,
    allowClosing: Boolean = false,
    submission: (Long) -> Unit,
  ): NativeEvent {
    require(timeout.inWholeMilliseconds >= 1) { "Operation timeout must be at least one millisecond" }
    val operation = nextOperation.getAndIncrement()
    val result = CompletableDeferred<NativeEvent>()
    lifecycleMutex.withLock {
      check(!closed.value && (allowClosing || !closing.value)) { "WebRtcRuntime is closed" }
      operations[operation] = result
      try {
        submission(operation)
      } catch (error: Throwable) {
        operations.remove(operation)
        throw error
      }
    }
    return result.await()
  }

  private suspend fun dispatchEvents() {
    try {
      while (scope.isActive && !closed.value) {
        val event = bridge.pollRuntimeEvent(handle, 1.seconds) ?: continue
        if (event.kind == NativeEventKind.OPERATION_COMPLETE) {
          handleOperationCompletion(event)
        } else {
          val peer = lifecycleMutex.withLock {
            peers[event.peerHandle] ?: run {
              queuedPeerEvents.getOrPut(event.peerHandle, ::mutableListOf).add(event)
              null
            }
          }
          peer?.handleNativeEvent(event)
        }
      }
    } catch (error: Throwable) {
      if (!closed.value) completeShutdown(error)
    }
  }

  private suspend fun handleOperationCompletion(event: NativeEvent) {
    val operation = lifecycleMutex.withLock { operations.remove(event.operationHandle) } ?: return
    if (event.number == 0) {
      operation.complete(event)
    } else {
      operation.completeExceptionally(
        WebRtcException(event.secondaryText ?: "WebRTC operation failed"),
      )
    }
  }

  private suspend fun completeShutdown(failure: Throwable?) {
    if (!closed.compareAndSet(expect = false, update = true)) return
    closing.value = true
    val (registeredPeers, pending) = lifecycleMutex.withLock {
      val registeredPeers = peers.values.toList()
      peers.clear()
      queuedPeerEvents.clear()
      val pending = operations.values.toList()
      operations.clear()
      registeredPeers to pending
    }
    registeredPeers.forEach { it.closeForRuntimeShutdown() }
    pending.forEach {
      it.completeExceptionally(
        failure ?: IllegalStateException("WebRtcRuntime closed before the operation completed"),
      )
    }
    runCatching { bridge.wakeRuntime(handle) }
    val releaseFailure = runCatching { bridge.releaseRuntime(handle) }.exceptionOrNull()
    scope.cancel()
    val finalFailure = failure ?: releaseFailure
    if (finalFailure == null) closeCompletion.complete(Unit)
    else closeCompletion.completeExceptionally(finalFailure)
  }

  internal data class ChannelRegistration(val handle: Long, val id: Int)

  public companion object {
    public fun create(options: WebRtcRuntimeOptions = WebRtcRuntimeOptions()): WebRtcRuntime {
      KestaraWebRtc.ensureNativeAbi()
      val bridge = platformNativeBridge()
      val handle = bridge.createRuntime(options)
      return try {
        WebRtcRuntime(options, bridge, handle)
      } catch (error: Throwable) {
        runCatching { bridge.releaseRuntime(handle) }
        throw error
      }
    }
  }
}

package com.enderdash.kestara.webrtc

import com.enderdash.kestara.webrtc.internal.NativeEvent
import com.enderdash.kestara.webrtc.internal.NativeEventKind
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

public class PeerConnection internal constructor(
  private val runtime: WebRtcRuntime,
  private val handle: Long,
  configuration: PeerConnectionConfiguration,
) {
  private val timeout: Duration = configuration.operationTimeout
  private val receiveQueueCapacity: Int = configuration.sctpOptions.receiveQueueCapacity
  private val channelsMutex = Mutex()
  private val channels = mutableMapOf<Long, DataChannel>()
  private val channelCount = atomic(0)
  private val queuedChannelEvents = mutableMapOf<Long, MutableList<NativeEvent>>()
  private val closing = atomic(false)
  private val closed = atomic(false)
  private val closeCompletion = CompletableDeferred<Unit>()
  private val mutableState = MutableStateFlow(PeerConnectionState.NEW)
  private val mutableIceConnectionState = MutableStateFlow(IceConnectionState.NEW)
  private val mutableIceGatheringState = MutableStateFlow(IceGatheringState.NEW)
  private val mutableSignalingState = MutableStateFlow(SignalingState.STABLE)
  private val localDescriptionChannel = Channel<SessionDescription>(Channel.BUFFERED)
  private val localCandidateChannel = Channel<IceCandidate>(Channel.BUFFERED)
  private val incomingDataChannelChannel = Channel<DataChannel>(Channel.BUFFERED)
  private val negotiationNeededChannel = Channel<Unit>(Channel.CONFLATED)

  public val state: StateFlow<PeerConnectionState> = mutableState.asStateFlow()
  public val iceConnectionState: StateFlow<IceConnectionState> = mutableIceConnectionState.asStateFlow()
  public val iceGatheringState: StateFlow<IceGatheringState> = mutableIceGatheringState.asStateFlow()
  public val signalingState: StateFlow<SignalingState> = mutableSignalingState.asStateFlow()
  public val localDescriptions: Flow<SessionDescription> = localDescriptionChannel.receiveAsFlow()
  public val localCandidates: Flow<IceCandidate> = localCandidateChannel.receiveAsFlow()
  public val incomingDataChannels: Flow<DataChannel> = incomingDataChannelChannel.receiveAsFlow()
  public val negotiationNeeded: Flow<Unit> = negotiationNeededChannel.receiveAsFlow()

  public suspend fun createOffer(): SessionDescription {
    requireOpen()
    return SessionDescription(
      runtime.createDescription(handle, SessionDescriptionType.OFFER, timeout),
      SessionDescriptionType.OFFER,
    )
  }

  public suspend fun createAnswer(): SessionDescription {
    requireOpen()
    return SessionDescription(
      runtime.createDescription(handle, SessionDescriptionType.ANSWER, timeout),
      SessionDescriptionType.ANSWER,
    )
  }

  public suspend fun setLocalDescription(description: SessionDescription) {
    requireOpen()
    runtime.setLocalDescription(handle, description, timeout)
    localDescriptionChannel.send(description)
  }

  public suspend fun createAndSetLocalDescription(type: SessionDescriptionType): SessionDescription {
    val description = when (type) {
      SessionDescriptionType.OFFER -> createOffer()
      SessionDescriptionType.ANSWER -> createAnswer()
      SessionDescriptionType.PRANSWER,
      SessionDescriptionType.ROLLBACK,
      -> throw UnsupportedOperationException("Automatic local description does not support $type")
    }
    setLocalDescription(description)
    return description
  }

  public suspend fun setRemoteDescription(description: SessionDescription) {
    requireOpen()
    runtime.setRemoteDescription(handle, description, timeout)
  }

  public suspend fun addIceCandidate(candidate: IceCandidate) {
    requireOpen()
    runtime.addIceCandidate(handle, candidate, timeout)
  }

  public suspend fun restartIce() {
    requireOpen()
    runtime.restartIce(handle, timeout)
  }

  public suspend fun setConfiguration(configuration: PeerConnectionConfiguration) {
    requireOpen()
    runtime.setConfiguration(handle, configuration, timeout)
  }

  public suspend fun getStats(): PeerConnectionStats {
    requireOpen()
    return runtime.getStats(handle, timeout)
  }

  public suspend fun selectedIceCandidatePair(): IceCandidatePairStats? =
    getStats().transport.selectedCandidatePair

  public suspend fun createDataChannel(
    label: String,
    options: DataChannelOptions = DataChannelOptions(),
  ): DataChannel {
    requireOpen()
    val registration = runtime.createDataChannel(handle, label, options, timeout)
    val channel = DataChannel(
      runtime = runtime,
      handle = registration.handle,
      id = registration.id,
      label = label,
      protocol = options.protocol,
      ordered = options.ordered,
      negotiated = options.negotiatedId != null,
      maxPacketLifeTime = options.maxPacketLifeTime,
      maxRetransmits = options.maxRetransmits,
      receiveQueueCapacity = receiveQueueCapacity,
      timeout = timeout,
      initiallyOpen = false,
      onTerminal = { channelHandle -> unregisterDataChannel(channelHandle) },
    )
    val queued = channelsMutex.withLock {
      channels[registration.handle] = channel
      channelCount.value = channels.size
      queuedChannelEvents.remove(registration.handle).orEmpty()
    }
    queued.forEach { channel.handleNativeEvent(it) }
    return channel
  }

  public suspend fun close() {
    if (closing.compareAndSet(expect = false, update = true)) {
      try {
        runtime.closePeer(handle, timeout)
        markClosed(runtimeShutdown = false)
        closeCompletion.complete(Unit)
      } catch (error: Throwable) {
        markClosed(runtimeShutdown = false)
        closeCompletion.completeExceptionally(error)
      }
    }
    closeCompletion.await()
  }

  internal suspend fun handleNativeEvent(event: NativeEvent) {
    when (event.kind) {
      NativeEventKind.LOCAL_CANDIDATE -> localCandidateChannel.send(
        IceCandidate(
          candidate = event.text.orEmpty(),
          sdpMid = event.secondaryText,
          sdpMLineIndex = event.number.takeIf { it >= 0 },
        ),
      )
      NativeEventKind.PEER_STATE -> mutableState.value = enumValue(event.number, "peer connection state")
      NativeEventKind.ICE_CONNECTION_STATE -> {
        mutableIceConnectionState.value = enumValue(event.number, "ICE connection state")
      }
      NativeEventKind.ICE_GATHERING_STATE -> {
        mutableIceGatheringState.value = enumValue(event.number, "ICE gathering state")
      }
      NativeEventKind.SIGNALING_STATE -> {
        mutableSignalingState.value = enumValue(event.number, "signaling state")
      }
      NativeEventKind.NEGOTIATION_NEEDED -> negotiationNeededChannel.send(Unit)
      NativeEventKind.DATA_CHANNEL -> registerRemoteDataChannel(event)
      NativeEventKind.DATA_CHANNEL_OPEN,
      NativeEventKind.DATA_CHANNEL_CLOSING,
      NativeEventKind.DATA_CHANNEL_CLOSED,
      NativeEventKind.DATA_CHANNEL_ERROR,
      NativeEventKind.DATA_CHANNEL_TEXT,
      NativeEventKind.DATA_CHANNEL_BINARY,
      NativeEventKind.DATA_CHANNEL_BUFFERED_AMOUNT_LOW,
      NativeEventKind.DATA_CHANNEL_BUFFERED_AMOUNT_HIGH,
      -> routeDataChannelEvent(event)
      else -> throw WebRtcException("Unsupported peer event: ${event.kind}")
    }
  }

  internal fun dataChannelCount(): Int = channelCount.value

  internal suspend fun closeForRuntimeShutdown() {
    markClosed(runtimeShutdown = true)
    closeCompletion.complete(Unit)
  }

  private suspend fun registerRemoteDataChannel(event: NativeEvent) {
    val metadata = decodeChannelMetadata(event.data)
    val ordered = event.number and 1 != 0
    val initiallyOpen = event.number and 2 != 0
    val id = event.number ushr 2
    val channel = DataChannel(
      runtime = runtime,
      handle = event.channelHandle,
      id = id,
      label = event.text.orEmpty(),
      protocol = event.secondaryText.orEmpty(),
      ordered = ordered,
      negotiated = metadata.negotiated,
      maxPacketLifeTime = metadata.maxPacketLifeTime,
      maxRetransmits = metadata.maxRetransmits,
      receiveQueueCapacity = receiveQueueCapacity,
      timeout = timeout,
      initiallyOpen = initiallyOpen,
      onTerminal = { channelHandle -> unregisterDataChannel(channelHandle) },
    )
    val queued = channelsMutex.withLock {
      channels[event.channelHandle] = channel
      channelCount.value = channels.size
      queuedChannelEvents.remove(event.channelHandle).orEmpty()
    }
    incomingDataChannelChannel.send(channel)
    queued.forEach { channel.handleNativeEvent(it) }
    if (initiallyOpen) channel.notifyOpen()
  }

  private suspend fun routeDataChannelEvent(event: NativeEvent) {
    val channel = channelsMutex.withLock {
      channels[event.channelHandle] ?: run {
        queuedChannelEvents.getOrPut(event.channelHandle, ::mutableListOf).add(event)
        null
      }
    }
    channel?.handleNativeEvent(event)
  }

  private suspend fun unregisterDataChannel(channelHandle: Long) {
    channelsMutex.withLock {
      channels.remove(channelHandle)
      channelCount.value = channels.size
    }
  }

  private suspend fun markClosed(runtimeShutdown: Boolean) {
    if (!closed.compareAndSet(expect = false, update = true)) return
    closing.value = true
    val ownedChannels = channelsMutex.withLock {
      val copy = channels.values.toList()
      channels.clear()
      channelCount.value = 0
      queuedChannelEvents.clear()
      copy
    }
    ownedChannels.forEach { it.markClosed(runtimeShutdown) }
    mutableState.value = PeerConnectionState.CLOSED
    mutableSignalingState.value = SignalingState.CLOSED
    localDescriptionChannel.close()
    localCandidateChannel.close()
    incomingDataChannelChannel.close()
    negotiationNeededChannel.close()
    runtime.unregisterPeer(handle, this)
  }

  private fun requireOpen() {
    check(!closing.value && !closed.value) { "PeerConnection is closed" }
  }

  private inline fun <reified T : Enum<T>> enumValue(ordinal: Int, name: String): T =
    enumValues<T>().getOrNull(ordinal)
      ?: throw WebRtcException("Unknown $name: $ordinal")

  private data class ChannelMetadata(
    val negotiated: Boolean,
    val maxPacketLifeTime: Int?,
    val maxRetransmits: Int?,
  )

  private companion object {
    fun decodeChannelMetadata(data: ByteArray?): ChannelMetadata {
      require(data != null && data.size == 9) { "Invalid native DataChannel metadata" }
      fun intAt(offset: Int): Int =
        (data[offset].toInt() and 0xff) or
          ((data[offset + 1].toInt() and 0xff) shl 8) or
          ((data[offset + 2].toInt() and 0xff) shl 16) or
          ((data[offset + 3].toInt() and 0xff) shl 24)
      val lifetime = intAt(1)
      val retransmits = intAt(5)
      return ChannelMetadata(
        negotiated = data[0].toInt() != 0,
        maxPacketLifeTime = lifetime.takeIf { it >= 0 },
        maxRetransmits = retransmits.takeIf { it >= 0 },
      )
    }
  }
}

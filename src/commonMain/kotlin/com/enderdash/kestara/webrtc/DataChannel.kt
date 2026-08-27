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
import kotlin.time.Duration

public class DataChannel internal constructor(
  private val runtime: WebRtcRuntime,
  private val handle: Long,
  public val id: Int,
  public val label: String,
  public val protocol: String,
  public val ordered: Boolean,
  public val negotiated: Boolean,
  public val maxPacketLifeTime: Int?,
  public val maxRetransmits: Int?,
  receiveQueueCapacity: Int,
  private val timeout: Duration,
  initiallyOpen: Boolean,
  private val onTerminal: suspend (Long) -> Unit,
) {
  private val closing = atomic(false)
  private val closed = atomic(false)
  private val closeCompletion = CompletableDeferred<Unit>()
  private val mutableState = MutableStateFlow(
    if (initiallyOpen) DataChannelState.OPEN else DataChannelState.CONNECTING,
  )
  private val messageChannel = Channel<DataChannelMessage>(receiveQueueCapacity)
  private val eventChannel = Channel<DataChannelEvent>(Channel.BUFFERED)
  private val lowThreshold = atomic(0L)
  private val highThreshold = atomic(UInt.MAX_VALUE.toLong())

  public val state: StateFlow<DataChannelState> = mutableState.asStateFlow()
  public val messages: Flow<DataChannelMessage> = messageChannel.receiveAsFlow()
  public val events: Flow<DataChannelEvent> = eventChannel.receiveAsFlow()
  public val bufferedAmountLowThreshold: Long get() = lowThreshold.value
  public val bufferedAmountHighThreshold: Long get() = highThreshold.value
  public val isOpen: Boolean get() = mutableState.value == DataChannelState.OPEN

  public suspend fun send(text: String) {
    requireOpen()
    runtime.sendText(handle, text, timeout, trySend = false)
  }

  public suspend fun send(data: ByteArray) {
    requireOpen()
    runtime.sendBinary(handle, data.copyOf(), timeout, trySend = false)
  }

  public suspend fun trySend(text: String): Boolean {
    requireOpen()
    return runtime.sendText(handle, text, timeout, trySend = true)
  }

  public suspend fun trySend(data: ByteArray): Boolean {
    requireOpen()
    return runtime.sendBinary(handle, data.copyOf(), timeout, trySend = true)
  }

  public suspend fun awaitWritable() {
    requireOpen()
    runtime.awaitWritable(handle, timeout)
  }

  public suspend fun outstandingBytes(): Long = runtime.outstandingBytes(handle, timeout)

  public suspend fun bufferedAmount(): Long = outstandingBytes()

  public suspend fun setBufferedAmountThresholds(low: Long, high: Long) {
    requireOpen()
    require(low >= 0 && high >= 0 && low <= high && high <= UInt.MAX_VALUE.toLong()) {
      "Buffered amount thresholds must satisfy 0 <= low <= high <= 4294967295"
    }
    runtime.setDataChannelThresholds(handle, low, high, timeout)
    lowThreshold.value = low
    highThreshold.value = high
  }

  public suspend fun close() {
    if (closing.compareAndSet(expect = false, update = true)) {
      mutableState.value = DataChannelState.CLOSING
      try {
        runtime.closeDataChannel(handle, timeout)
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
      NativeEventKind.DATA_CHANNEL_OPEN -> notifyOpen()
      NativeEventKind.DATA_CHANNEL_CLOSING -> {
        mutableState.value = DataChannelState.CLOSING
        eventChannel.send(DataChannelEvent.Closing)
      }
      NativeEventKind.DATA_CHANNEL_CLOSED -> markClosed(runtimeShutdown = false)
      NativeEventKind.DATA_CHANNEL_ERROR -> eventChannel.send(
        DataChannelEvent.Error(event.text ?: "DataChannel error"),
      )
      NativeEventKind.DATA_CHANNEL_TEXT -> messageChannel.send(
        DataChannelMessage.Text(event.text.orEmpty()),
      )
      NativeEventKind.DATA_CHANNEL_BINARY -> messageChannel.send(
        DataChannelMessage.Binary(event.data ?: ByteArray(0)),
      )
      NativeEventKind.DATA_CHANNEL_BUFFERED_AMOUNT_LOW -> {
        eventChannel.send(DataChannelEvent.BufferedAmountLow)
      }
      NativeEventKind.DATA_CHANNEL_BUFFERED_AMOUNT_HIGH -> {
        eventChannel.send(DataChannelEvent.BufferedAmountHigh)
      }
      else -> throw WebRtcException("Unsupported DataChannel event: ${event.kind}")
    }
  }

  internal suspend fun notifyOpen() {
    if (closed.value || mutableState.value == DataChannelState.OPEN) return
    mutableState.value = DataChannelState.OPEN
    eventChannel.send(DataChannelEvent.Open)
  }

  internal suspend fun markClosed(runtimeShutdown: Boolean) {
    if (!closed.compareAndSet(expect = false, update = true)) return
    closing.value = true
    mutableState.value = DataChannelState.CLOSED
    if (!runtimeShutdown) eventChannel.send(DataChannelEvent.Closed)
    messageChannel.close()
    eventChannel.close()
    onTerminal(handle)
    closeCompletion.complete(Unit)
  }

  private fun requireOpen() {
    check(isOpen && !closing.value && !closed.value) { "DataChannel is not open" }
  }
}

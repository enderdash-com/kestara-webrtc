package com.enderdash.kestara.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PeerConnectionIntegrationTest {
  @Test
  fun loadsCompatibleNativeLibrary() {
    assertEquals(KestaraWebRtc.REQUIRED_NATIVE_ABI, KestaraWebRtc.nativeAbiVersion)
    assertTrue(KestaraWebRtc.nativeLibraryVersion.isNotBlank())
  }

  @Test
  fun exchangesBinaryDataOverAnOrderedChannel(): Unit = runBlocking {
    withTimeout(20.seconds) {
      val configuration = PeerConnectionConfiguration(
        iceOptions = IceOptions(
          mdnsMode = IceMdnsMode.DISABLED,
          mdnsQueryTimeout = 1.seconds,
          includeLoopbackCandidate = true,
        ),
        operationTimeout = 5.seconds,
      )
      val offererRuntime = WebRtcRuntime.create()
      val answererRuntime = WebRtcRuntime.create()
      try {
        val offerer = offererRuntime.createPeerConnection(configuration)
        val answerer = answererRuntime.createPeerConnection(configuration)
        val offererRelay = relayCandidates(this, offerer, answerer)
        val answererRelay = relayCandidates(this, answerer, offerer)
        val incoming = async { answerer.incomingDataChannels.first() }
        val channel = offerer.createDataChannel(
          "kestara-test",
          DataChannelOptions(ordered = false, maxRetransmits = 3, protocol = "kestara-test-protocol"),
        )

        val offer = offerer.createAndSetLocalDescription(SessionDescriptionType.OFFER)
        answerer.setRemoteDescription(offer)
        offererRelay.ready.value = true
        val answer = answerer.createAndSetLocalDescription(SessionDescriptionType.ANSWER)
        offerer.setRemoteDescription(answer)
        answererRelay.ready.value = true

        channel.state.first { it == DataChannelState.OPEN }
        val remoteChannel = incoming.await()
        remoteChannel.state.first { it == DataChannelState.OPEN }
        assertEquals(channel.id, remoteChannel.id)
        assertEquals("kestara-test-protocol", remoteChannel.protocol)
        assertFalse(remoteChannel.ordered)
        assertEquals(3, remoteChannel.maxRetransmits)

        val received = async { remoteChannel.messages.first() }
        val payload = byteArrayOf(0, 1, 3, 3, 7)
        channel.send(payload)
        val message = received.await()
        assertTrue(message is DataChannelMessage.Binary)
        assertContentEquals(payload, message.data)

        val stats = offerer.getStats()
        assertTrue(stats.transport.selectedCandidatePair != null)
        assertTrue(stats.dataChannels.isNotEmpty())

        offererRelay.cancel()
        answererRelay.cancel()
        offerer.close()
        answerer.close()
      } finally {
        offererRuntime.close()
        answererRuntime.close()
      }
    }
  }

  private fun relayCandidates(
    scope: CoroutineScope,
    source: PeerConnection,
    target: PeerConnection,
  ): CandidateRelay {
    val ready = MutableStateFlow(false)
    val job = scope.launch {
      source.localCandidates.collect { candidate ->
        ready.first { it }
        target.addIceCandidate(candidate)
      }
    }
    return CandidateRelay(ready) { job.cancel() }
  }

  private class CandidateRelay(
    val ready: MutableStateFlow<Boolean>,
    private val cancelAction: () -> Unit,
  ) {
    fun cancel() = cancelAction()
  }
}

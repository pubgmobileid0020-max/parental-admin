package com.example.admin

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.provider.Settings
import org.webrtc.*
import org.json.JSONObject

class WebRtcClient(
    private val context: Context,
    private val observer: PeerConnection.Observer
) {
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context).setEnableInternalTracer(true).createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        createPeerConnection()
    }

    private fun createPeerConnection() {
        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = factory.createPeerConnection(config, observer)
    }

    fun startScreenCapture(data: Intent) {
        videoCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() { override fun onStop() {} })
        startCapture()
    }

    fun startCameraCapture(facing: Boolean) {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.first { if (facing) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
        videoCapturer = enumerator.createCapturer(deviceName, null)
        startCapture()
    }

    private fun startCapture() {
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(false)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(720, 1280, 15)
        videoTrack = factory.createVideoTrack("VIDEO_TRACK", videoSource)
        peerConnection?.addTrack(videoTrack, listOf("stream1"))
        createOffer()
    }

    private fun createOffer() {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // ONLY SEND THE OFFER AFTER LOCAL DESCRIPTION IS SET
                        ConnectionService.push(JSONObject().put("type", "webrtc_offer").put("device_id", deviceId).put("sdp", sdp.description).toString())
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    fun onAnswerReceived(sdp: String) { peerConnection?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
    fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, sdp: String) { peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp)) }

    fun stop() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()
        videoTrack?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
    }
}

class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

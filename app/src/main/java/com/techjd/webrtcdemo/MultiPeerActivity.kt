package com.techjd.webrtcdemo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.media.AudioManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.techjd.webrtcdemo.databinding.ActivityMainBinding
import com.techjd.webrtcdemo.databinding.ActivityMultiPeerBinding
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.*
import java.net.URISyntaxException

class MultiPeerActivity : AppCompatActivity() {

    private val TAG = "MultiPeerActivity"

    private var binding: ActivityMultiPeerBinding? = null
    private var socket: Socket? = null
    private var isMuted = false

    private var peerConnection1: PeerConnection? = null
    private var peerConnection2: PeerConnection? = null

    private var rootEglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var videoTrackFromCamera: VideoTrack? = null
    private var audioTrackFromLocal: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null

    private var isUser1: Boolean = false
    private var isUser2: Boolean = false
    private var isUser3: Boolean = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiPeerBinding.inflate(layoutInflater)
        setContentView(binding!!.root)

        val audioManager = this.getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        connectToSignallingServer()
        initializeSurfaceViews()


        initializePeerConnectionFactory();
        createVideoTrackFromCameraAndShowIt();

        initializePeerConnections();
        startStreamingVideo();

    }

    private fun connectToSignallingServer() {
        try {
            runBlocking {
                socket = IO.socket("http://192.168.2.3:3000")
            }
            socket!!.on(Socket.EVENT_CONNECT) { args: Array<Any?>? ->
                socket!!.emit("create or join", "foo")
            }.on("created") {
                isUser1 = true
            }.on("second") {
                if (isUser1) {
                    doCall1()
                } else {
                    isUser2 = true
                    lifecycleScope.launch {
                        delay(3000L)
                        doAnswer1()
                    }
                }
            }.on("third") {
                if (isUser1) {
                    doCall2()
                }
                if (isUser2) {
                    lifecycleScope.launch {
                        delay(2000L)
                        doCall2()
                    }
                }
                if (!isUser1 && !isUser2) {
                    isUser3 = true
                    lifecycleScope.launch {
                        doAnswer2()
                    }
                }

            }
            socket!!.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    private fun doCall1() {
        peerConnection1!!.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                peerConnection1!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", "answer")
                    message.put("sdp", sessionDescription!!.description)
                    sendMessage(message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, MediaConstraints())
    }

    private fun doCall2() {

    }

    private fun doAnswer1() {

    }

    private fun doAnswer2() {

    }

//    private fun doAnswer() {
//        peerConnection!!.createAnswer(object : SimpleSdpObserver() {
//            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
//                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
//                val message = JSONObject()
//                try {
//                    message.put("type", "answer")
//                    message.put("sdp", sessionDescription!!.description)
//                    sendMessage(message)
//                } catch (e: JSONException) {
//                    e.printStackTrace()
//                }
//            }
//        }, MediaConstraints())
//    }


//    private fun doCall() {
//        val sdpMediaConstraints = MediaConstraints()
//        peerConnection!!.createOffer(object : SimpleSdpObserver() {
//            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
//                Log.d(TAG, "onCreateSuccess: ")
//                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
//                val message = JSONObject()
//                try {
//                    message.put("type", "offer")
//                    message.put("sdp", sessionDescription!!.description)
//                    sendMessage(message)
//                } catch (e: JSONException) {
//                    e.printStackTrace()
//                }
//            }
//        }, sdpMediaConstraints)
//    }

    private fun sendMessage(message: Any) {
        socket!!.emit("message", message)
    }

    private fun initializeSurfaceViews() {
        rootEglBase = EglBase.create()
        binding!!.localUser.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.localUser.setEnableHardwareScaler(true)
        binding!!.localUser.setMirror(true)

        binding!!.rM1.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.rM1.setEnableHardwareScaler(true)
        binding!!.rM1.setMirror(true)

        binding!!.rM2.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.rM2.setEnableHardwareScaler(true)
        binding!!.rM2.setMirror(true)

    }

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initializeAndroidGlobals(this, true, true, true);
        factory = PeerConnectionFactory(null);
        factory!!.setVideoHwAccelerationOptions(
            rootEglBase!!.getEglBaseContext(),
            rootEglBase!!.getEglBaseContext()
        );
    }

    private fun createVideoTrackFromCameraAndShowIt() {
        videoCapturer = createVideoCapturer()
        val videoSource = factory!!.createVideoSource(videoCapturer)
        videoCapturer!!.startCapture(1280, 720, 30)
        videoTrackFromCamera = factory!!.createVideoTrack("ARDAMSv0", videoSource)
        videoTrackFromCamera!!.setEnabled(true)


        val audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrackFromLocal = factory!!.createAudioTrack("ARDAMSa0", audioSource)
        audioTrackFromLocal!!.setEnabled(true)
        isMuted = false
        videoTrackFromCamera!!.addRenderer(VideoRenderer(binding!!.localUser))
    }

    private fun initializePeerConnections() {
        peerConnection1 = createPeerConnection1(factory!!)
        peerConnection2 = createPeerConnection2(factory!!)
    }


    private fun startStreamingVideo() {
        val mediaStream = factory!!.createLocalMediaStream("ARDAMS")
        mediaStream.addTrack(videoTrackFromCamera)
        mediaStream.addTrack(audioTrackFromLocal)
        peerConnection1!!.addStream(mediaStream)
        peerConnection2!!.addStream(mediaStream)
    }

    private fun createPeerConnection1(factory: PeerConnectionFactory): PeerConnection? {
        Log.d(TAG, "createPeerConnection: Called")
        val iceServers: ArrayList<PeerConnection.IceServer> = ArrayList()
        iceServers.add(PeerConnection.IceServer("stun:stun.l.google.com:19302"))
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        val pcConstraints = MediaConstraints()
        val pcObserver: PeerConnection.Observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: ")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: ")
//                binding!!.remoteStream.setEnabled(false)
//                binding!!.remoteStream.release()
            }

            override fun onIceConnectionReceivingChange(b: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: ")
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: ")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.d(TAG, "onIceCandidate: ")
                val message = JSONObject()
                try {
                    message.put("type", "candidate")
                    message.put("label", iceCandidate.sdpMLineIndex)
                    message.put("id", iceCandidate.sdpMid)
                    message.put("candidate", iceCandidate.sdp)
                    Log.d(TAG, "onIceCandidate: sending candidate $message")
                    socket!!.emit("ice", message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Log.d(TAG, "onIceCandidatesRemoved: ")
            }

            override fun onAddStream(mediaStream: MediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.videoTracks.size)
                if (isUser1 || isUser2 || isUser3) {
                    mediaStream.videoTracks.first.addRenderer(VideoRenderer(binding!!.rM1))
                }
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Log.d(TAG, "onRemoveStream: ")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.d(TAG, "onDataChannel: ")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded: ")
            }
        }
        return factory.createPeerConnection(rtcConfig, pcConstraints, pcObserver)
    }

    private fun createPeerConnection2(factory: PeerConnectionFactory): PeerConnection? {
        Log.d(TAG, "createPeerConnection: Called")
        val iceServers: ArrayList<PeerConnection.IceServer> = ArrayList()
        iceServers.add(PeerConnection.IceServer("stun:stun.l.google.com:19302"))
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        val pcConstraints = MediaConstraints()
        val pcObserver: PeerConnection.Observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: ")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: ")
//                binding!!.remoteStream.setEnabled(false)
//                binding!!.remoteStream.release()
            }

            override fun onIceConnectionReceivingChange(b: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: ")
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: ")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.d(TAG, "onIceCandidate: ")
                val message = JSONObject()
                try {
                    message.put("type", "candidate")
                    message.put("label", iceCandidate.sdpMLineIndex)
                    message.put("id", iceCandidate.sdpMid)
                    message.put("candidate", iceCandidate.sdp)
                    Log.d(TAG, "onIceCandidate: sending candidate $message")
                    socket!!.emit("ice", message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Log.d(TAG, "onIceCandidatesRemoved: ")
            }

            override fun onAddStream(mediaStream: MediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.videoTracks.size)
                if (isUser1 || isUser2) {
                    mediaStream.videoTracks.first.addRenderer(VideoRenderer(binding!!.rM2))
                }
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Log.d(TAG, "onRemoveStream: ")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.d(TAG, "onDataChannel: ")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded: ")
            }
        }
        return factory.createPeerConnection(rtcConfig, pcConstraints, pcObserver)
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val videoCapturer: VideoCapturer?
        videoCapturer = if (useCamera2()) {
            createCameraCapturer(Camera2Enumerator(this))
        } else {
            createCameraCapturer(Camera1Enumerator(true))
        }
        return videoCapturer
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        return null
    }

    private fun useCamera2(): Boolean {
        return Camera2Enumerator.isSupported(this)
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }
}
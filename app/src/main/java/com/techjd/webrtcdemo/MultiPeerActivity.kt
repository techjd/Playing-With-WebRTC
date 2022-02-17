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
    private var isInitiator = false
    private var isChannelReady = false
    private var isStarted = false
    private var isMuted = false

    private var peerConnection: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var videoTrackFromCamera: VideoTrack? = null
    private var audioTrackFromLocal: AudioTrack? = null
    private var cameraVideoCapturer: CameraVideoCapturer? = null
    private var videoCapturer: VideoCapturer? = null
    var cnt = 0
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
                Log.d(TAG, "connectToSignallingServer: connect")
                socket!!.emit("create or join", "foo")
            }.on("ipaddr") { args: Array<Any?>? ->
                Log.d(
                    TAG,
                    "connectToSignallingServer: ipaddr"
                )
            }.on(
                "created"
            ) { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: created")
                if (args != null) {
                    for (arg in args) {
                        Log.d(TAG, "connectToSignallingServer: $arg")
                    }
                }
                isInitiator = true
            }.on("full") { args: Array<Any?>? ->
                Log.d(
                    TAG,
                    "connectToSignallingServer: full"
                )
            }.on(
                "join"
            ) { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: join")
                Log.d(
                    TAG,
                    "connectToSignallingServer: Another peer made a request to join room"
                )
                Log.d(
                    TAG,
                    "connectToSignallingServer: This peer is the initiator of room"
                )
//                doCall()
                isChannelReady = true
            }.on("joined") { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: joined ${args.toString()}")
                isChannelReady = true
            }.on("log") { args: Array<Any> ->
                for (arg in args) {
                    Log.d(TAG, "connectToSignallingServer: $arg")
                }
            }.on(
                "message"
            ) { args: Array<Any?>? ->
                Log.d(
                    TAG,
                    "connectToSignallingServer: got a message"
                )
            }.on("out") {
                Log.d(TAG, "connectToSignallingServer: Called Bye")
//                peerConnection!!.close()
                CoroutineScope(Dispatchers.Main).launch {
                    withContext(Dispatchers.Main) {
                        peerConnection!!.close()
                        socket!!.disconnect()
                        Toast.makeText(applicationContext, "Call Ended", Toast.LENGTH_LONG).show()
//                        delay(2000L)
                        super.onBackPressed()
                    }
                }
//                binding!!.remoteStream.setEnabled(false)
//                binding!!.remoteStream.release()
//                binding!!.remoteStream
            }.on(
                "message"
            ) { args: Array<Any> ->
                try {
                    if (args[0] is String) {
                        val message = args[0] as String
                        if (message == "got user media") {
                            Log.d(TAG, "connectToSignallingServer: Got User Media")
                            maybeStart()
                        }
                    } else {
                        val message = args[0] as JSONObject
                        Log.d(TAG, "connectToSignallingServer: got message $message")
                        if (message.getString("type") == "offer") {
                            Log.d(
                                TAG,
                                "connectToSignallingServer: received an offer $isInitiator $isStarted"
                            )
                            if (!isInitiator!! && !isStarted!!) {
                                maybeStart()
                            }
//                            doCall()
                            peerConnection!!.setRemoteDescription(
                                SimpleSdpObserver(),
                                SessionDescription(
                                    SessionDescription.Type.OFFER,
                                    message.getString("sdp")
                                )
                            )
                            doAnswer()
                        } else if (message.getString("type") == "answer" && isStarted!!) {
                            peerConnection!!.setRemoteDescription(
                                SimpleSdpObserver(),
                                SessionDescription(
                                    SessionDescription.Type.ANSWER,
                                    message.getString("sdp")
                                )
                            )
                        } else if (message.getString("type") == "candidate" && isStarted!!) {
                            Log.d(
                                TAG,
                                "connectToSignallingServer: receiving candidates"
                            )
                            val candidate = IceCandidate(
                                message.getString("id"),
                                message.getInt("label"),
                                message.getString("candidate")
                            )
                            peerConnection!!.addIceCandidate(candidate)
                        }
                        /*else if (message === 'bye' && isStarted) {
        handleRemoteHangup();
    }*/
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }.on(
                Socket.EVENT_DISCONNECT
            ) { args: Array<Any?>? ->
                Log.d(
                    TAG,
                    "connectToSignallingServer: disconnect"
                )
            }
            socket!!.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    private fun doAnswer() {
        peerConnection!!.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
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

    private fun maybeStart() {
        Log.d(TAG, "maybeStart: $isStarted $isChannelReady")
        if (!isStarted!! && isChannelReady!!) {
            isStarted = true
            if (isInitiator!!) {
                doCall()
            }
        }
    }

    private fun doCall() {
        val sdpMediaConstraints = MediaConstraints()
        peerConnection!!.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                Log.d(TAG, "onCreateSuccess: ")
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", "offer")
                    message.put("sdp", sessionDescription!!.description)
                    sendMessage(message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, sdpMediaConstraints)
    }

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

        binding!!.rM3.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.rM3.setEnableHardwareScaler(true)
        binding!!.rM3.setMirror(true)
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
        peerConnection = createPeerConnection(factory!!)
    }

    private fun startStreamingVideo() {
        val mediaStream = factory!!.createLocalMediaStream("ARDAMS")
        mediaStream.addTrack(videoTrackFromCamera)
        mediaStream.addTrack(audioTrackFromLocal)
        peerConnection!!.addStream(mediaStream)
        sendMessage("got user media")
    }

    private fun createPeerConnection(factory: PeerConnectionFactory): PeerConnection? {
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
                    sendMessage(message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Log.d(TAG, "onIceCandidatesRemoved: ")
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onAddStream(mediaStream: MediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.videoTracks.size)
                Log.d(TAG, "onAddStream cnttttttt: ${cnt}")
                cnt++
                isStarted = false
//                mediaStream.videoTracks[0].addRenderer(VideoRenderer(binding!!.rM1))
//                if (cnt == 0) {
//                    mediaStream.videoTracks[0].addRenderer(VideoRenderer(binding!!.rM1))
//                    cnt++
//                } else if (cnt == 1) {
//                    mediaStream.videoTracks[1].addRenderer(VideoRenderer(binding!!.rM2))
//                }

//                mediaStream.videoTracks[1].addRenderer(VideoRenderer(binding!!.rM2))
//                mediaStream.videoTracks[2].addRenderer(VideoRenderer(binding!!.rM3))
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
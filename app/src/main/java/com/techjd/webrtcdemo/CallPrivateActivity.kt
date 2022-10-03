package com.techjd.webrtcdemo

import android.media.AudioManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.techjd.webrtcdemo.databinding.ActivityCallPrivateBinding
import com.techjd.webrtcdemo.databinding.ActivityMainBinding
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.*
import java.net.URISyntaxException

class CallPrivateActivity : AppCompatActivity() {

    private val TAG = "CallPrivateActivity"

    private var binding: ActivityCallPrivateBinding? = null
    private var socket: Socket? = null
    private var isMuted = false
    private var peerConnection: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var videoTrackFromCamera: VideoTrack? = null
    private var audioTrackFromLocal: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallPrivateBinding.inflate(layoutInflater)
        val view = binding!!.root
        setContentView(view)

        val audioManager = this.getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true


        runBlocking {
            connectToSignallingServer()
            initializeSurfaceViews()


            initializePeerConnectionFactory();
            createVideoTrackFromCameraAndShowIt();

            initializePeerConnections();
            startStreamingVideo();
        }
        
        binding!!.call.setOnClickListener {
            doCall()
        }
    }

    private fun connectToSignallingServer() {
        try {
            runBlocking {
                socket = IO.socket("http://192.168.2.3:3000")
            }
            socket!!.on(Socket.EVENT_CONNECT) { args: Array<Any?>? ->
                socket!!.emit("join", "foo")
            }.on("offer") { args: Array<Any> ->

                val message = args[0] as JSONObject

                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        binding!!.receive.setOnClickListener {
                            doAnswer()
                            it.visibility = View.GONE
                        }
                        binding!!.call.visibility = View.GONE
                    }
                }

                peerConnection!!.setRemoteDescription(
                    SimpleSdpObserver(),
                    SessionDescription(
                        SessionDescription.Type.OFFER,
                        message.getString("sdp")
                    )
                )
            }.on("answer") { args: Array<Any> ->

                val message = args[0] as JSONObject

                peerConnection!!.setRemoteDescription(
                    SimpleSdpObserver(),
                    SessionDescription(
                        SessionDescription.Type.ANSWER,
                        message.getString("sdp")
                    )
                )

                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        binding!!.call.visibility = View.GONE
                        binding!!.receive.visibility = View.GONE
                    }
                }

            }.on("ice") { args: Array<Any> ->

                val message = args[0] as JSONObject

                val candidate = IceCandidate(
                    message.getString("id"),
                    message.getInt("label"),
                    message.getString("candidate")
                )

                peerConnection!!.addIceCandidate(candidate)
            }.on("out") {
                CoroutineScope(Dispatchers.Main).launch {
                    withContext(Dispatchers.Main) {
                        peerConnection!!.close()
                        socket!!.disconnect()
                        Toast.makeText(applicationContext, "Call Ended", Toast.LENGTH_LONG).show()
                        super.onBackPressed()
                    }
                }
            }
            socket!!.connect()

        } catch (e: URISyntaxException) {
            e.printStackTrace()
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
                    socket!!.emit("offer", message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, sdpMediaConstraints)
    }

    private fun doAnswer() {
        peerConnection!!.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", "answer")
                    message.put("sdp", sessionDescription!!.description)
                    socket!!.emit("answer", message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, MediaConstraints())
    }


    private fun initializeSurfaceViews() {
        rootEglBase = EglBase.create()
        binding!!.localStream.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.localStream.setEnableHardwareScaler(true)
        binding!!.localStream.setMirror(true)
        binding!!.remoteStream.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.remoteStream.setEnableHardwareScaler(true)
        binding!!.remoteStream.setMirror(true)
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
        videoTrackFromCamera!!.addRenderer(VideoRenderer(binding!!.localStream))
    }

    private fun initializePeerConnections() {
        peerConnection = createPeerConnection(factory!!)
    }

    private fun startStreamingVideo() {
        val mediaStream = factory!!.createLocalMediaStream("ARDAMS")
        mediaStream.addTrack(videoTrackFromCamera)
        mediaStream.addTrack(audioTrackFromLocal)
        peerConnection!!.addStream(mediaStream)
    }

    private fun createPeerConnection(factory: PeerConnectionFactory): PeerConnection? {
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

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onAddStream(mediaStream: MediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.videoTracks.size)
                if (binding!!.remoteStream.isActivated)
                mediaStream.videoTracks.first.addRenderer(VideoRenderer(binding!!.remoteStream))

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
        socket!!.disconnect()
        super.onBackPressed()
    }
}
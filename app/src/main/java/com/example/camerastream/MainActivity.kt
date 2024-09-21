package com.example.camerastream

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Base64
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camerastream.data.PacketData
import com.example.camerastream.databinding.ActivityMainBinding
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val CAMERA_PERMISSION_CODE = 1001

    private lateinit var binding: ActivityMainBinding
    private var webSocket: WebSocket? = null
    private var resultData = ""

    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up the button to start the camera
        binding.startCameraButton.setOnClickListener {
            if (isCameraPermissionGranted()) {
                startCamera()
            } else {
                requestCameraPermission()
            }
        }
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            // Show an explanation to the user why the permission is needed
        }
        // Request the permission
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission is granted, proceed with the camera
            } else {
                // Permission is denied, show a message or take action
                binding.resultText.text = "Camera permission is required to use this feature."
            }
        }
    }

    private fun initWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder().url("wss://e73a-2001-448a-4040-91bc-873b-685d-4540-ac1b.ngrok-free.app/user-client").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                super.onOpen(webSocket, response)
                println("WebSocket Connected")
                runOnUiThread {
                    binding.resultText.text = "WebSocket Connected"
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                println("Received message: $text")
                val data = gson.fromJson(text, PacketData::class.java)
                runOnUiThread {
                    if (resultData == "") {
                        resultData = data.data
                        binding.resultText.text = resultData
                    } else {
                        closeCamera()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                println("WebSocket Closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                super.onFailure(webSocket, t, response)
                println("WebSocket Error: ${t.message}")
                runOnUiThread {
                    binding.resultText.text = "WebSocket Error: ${t.message}"
                }
            }
        })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Use a local variable for the cameraProvider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get() // Fetch the provider
            bindCamera(cameraProvider)
            initWebSocket() // Initialize WebSocket when the camera starts
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .setTargetResolution(Size(640, 480))
            .build()
            .also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalyzer.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
            processImageProxy(imageProxy)
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()

        // Convert to Base64 and send over WebSocket
        val base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT)

        val packetData = PacketData(
            from = "user-client",
            receiver = "app-analyzer",
            data = base64Image
        )

        webSocket?.send(gson.toJson(packetData).toString()) // Send data over WebSocket

        imageProxy.close()
    }

    private fun closeCamera() {
        // This will safely unbind all use cases (stopping the camera)
        ProcessCameraProvider.getInstance(this).get().unbindAll()

        // Close the WebSocket connection when the camera is stopped
        webSocket?.close(1000, "Camera closed")
        webSocket = null // Reset WebSocket to null
    }
}

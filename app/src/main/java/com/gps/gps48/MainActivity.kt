package com.gps.gps48

import ApiResponse
import ApiService
import InventoryRequest
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var cameraExecutor: ExecutorService

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://optimumdrag.com/test2024/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            CameraScreen()
        }
    }

    @Composable
    fun CameraScreen() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        // Initialize previewView outside AndroidView to avoid threading issues
        val previewView = remember { PreviewView(context) }

        LaunchedEffect(Unit) {
            startCamera(context, lifecycleOwner, previewView) // ✅ Ensures Main Thread execution
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            Text("GPS48: Scanning VINs...")
        }
    }

    private fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider) // ✅ FIXED: Now has a valid surface
            }

            val imageAnalyzer = ImageAnalysis.Builder().build().apply {
                setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    processImage(imageProxy) // ✅ Process on Main Thread
                }
            }

            cameraProvider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
            )
        }, ContextCompat.getMainExecutor(context)) // ✅ Ensures execution on Main Thread
    }


    @OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val vin = result.text.replace(Regex("[IioOQSs]")) {
                    when (it.value) {
                        "I", "i" -> "1"
                        "o", "O" -> "0"
                        "Q" -> "9"
                        "s", "S" -> "5"
                        else -> it.value
                    }
                }
                updateDatabase(vin)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun updateDatabase(vin: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {

            requestLocationPermission() // ✅ Request permission if not granted
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val coordinates = "${location.latitude}, ${location.longitude}"
                val request = InventoryRequest(vin, coordinates)

                lifecycleScope.launch {
                    try {
                        // Switch to background thread for network call
                        val response = withContext(Dispatchers.IO) {
                            val service = retrofit.create(ApiService::class.java)
                            service.updateInventory(request)
                        }

                        // Check if the response is successful
                        if (response.isSuccessful) {
                            val responseBody = response.body()
                            if (responseBody != null) {
                                handleSuccess(responseBody)
                            } else {
                                Log.e("GPS48", "Response body is null")
                            }
                        } else {
                            Log.e("GPS48", "Request failed: ${response.code()}")
                        }
                    } catch (e: Exception) {
                        // Callback for failure
                        handleError(e)
                    }
                }
            } else {
                Log.e("GPS48", "Failed to get location: Location is null")
            }
        }
    }

    // Success callback
    private fun handleSuccess(response: ApiResponse) {
        Log.d("GPS48", "Server Response: ${response.status}")
        if (response.status == "success") {
            // Do something with the successful response, such as UI update
            Log.d("GPS48", "Updated successfully.")
        } else {
            Log.e("GPS48", "Updated Failed.")
        }
    }

    // Error callback
    private fun handleError(e: Exception) {
        Log.e("GPS48", "Network call failed: ${e.message}")
        // Optionally display a user-friendly error message or retry the request
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("GPS48", "Location permission granted")
        } else {
            Log.e("GPS48", "Location permission denied")
        }
    }

    private fun requestLocationPermission() {
        locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

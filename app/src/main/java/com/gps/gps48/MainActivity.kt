package com.gps.gps48

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.gps.gps48.ui.theme.GPS48Theme
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://optimumdrag.com/test2024/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            Column(Modifier.fillMaxSize(), Arrangement.Center) {
                Text("GPS48: Scanning VINs...")
            }
        }

        startCamera()
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply { setSurfaceProvider(null) }
            val imageAnalyzer = ImageAnalysis.Builder().build().apply {
                setAnalyzer(executor) { imageProxy ->
                    processImage(imageProxy)
                }
            }
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
        }, executor)
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
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            val coordinates = "${location.latitude}, ${location.longitude}"
            lifecycleScope.launch {
                val service = retrofit.create(ApiService::class.java)
                service.updateInventory(vin, coordinates)
            }
        }
    }
}
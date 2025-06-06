package com.example.arapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment

class MainActivity : AppCompatActivity() {

    private lateinit var arFragment: ArFragment
    private var session: Session? = null
    private var installRequested = false

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Verificar permisos de cámara
        if (!checkCameraPermission()) {
            requestCameraPermission()
        } else {
            setupARFragment()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
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
                setupARFragment()
            } else {
                Toast.makeText(this, "Permiso de cámara requerido para AR", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupARFragment() {
        arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as ArFragment

        // Configurar el listener para cuando se toque la pantalla
        arFragment.setOnTapArPlaneListener { hitResult, plane, motionEvent ->
            // Aquí es donde colocaremos el objeto 3D
            placeObject(hitResult)
        }
    }

    private fun placeObject(hitResult: com.google.ar.core.HitResult) {
        // Cargar modelo 3D
        ModelRenderable.builder()
            .setSource(this, R.raw.model) // Archivo 3D en la carpeta raw
            .build()
            .thenAccept { modelRenderable ->
                addModelToScene(hitResult, modelRenderable)
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Error cargando modelo", throwable)
                null
            }
    }

    private fun addModelToScene(
        hitResult: com.google.ar.core.HitResult,
        modelRenderable: ModelRenderable
    ) {
        val anchor = hitResult.createAnchor()
        val anchorNode = com.google.ar.sceneform.AnchorNode(anchor)
        anchorNode.setParent(arFragment.arSceneView.scene)

        val modelNode = com.google.ar.sceneform.Node()
        modelNode.renderable = modelRenderable
        modelNode.setParent(anchorNode)

        // Opcional: animar el objeto
        modelNode.setOnTapListener { _, _ ->
            // Aquí puedes agregar animaciones o interacciones
            Toast.makeText(this, "¡Objeto tocado!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            var exception: Exception? = null
            var message: String? = null

            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        // ARCore está instalado
                    }
                }

                // Verificar permisos de cámara
                if (!checkCameraPermission()) {
                    return
                }

                // Crear sesión ARCore
                session = Session(this)

            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Por favor instala ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "ARCore es requerido para esta aplicación"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Por favor actualiza ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Por favor actualiza la aplicación"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "Este dispositivo no soporta AR"
                exception = e
            } catch (e: Exception) {
                message = "Error al inicializar AR"
                exception = e
            }

            if (message != null) {
                Log.e(TAG, "Error creando sesión AR", exception)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Error accediendo a la cámara", e)
            session = null
            return
        }
    }

    override fun onPause() {
        super.onPause()
        session?.pause()
    }
}
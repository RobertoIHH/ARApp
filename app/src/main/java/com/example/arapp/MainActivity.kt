package com.example.arapp

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private var session: Session? = null
    private var installRequested = false
    private var backgroundTextureId = -1
    private val anchors = mutableListOf<Anchor>()
    private var displayRotationHelper: DisplayRotationHelper? = null

    // Shaders simples para rendering
    private val vertexShaderCode = """
        attribute vec4 vPosition;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private var shaderProgram = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpMatrixHandle = 0

    // Vertices para un cubo simple
    private val cubeVertices = floatArrayOf(
        // Front face
        -0.1f, -0.1f,  0.1f,
        0.1f, -0.1f,  0.1f,
        0.1f,  0.1f,  0.1f,
        -0.1f,  0.1f,  0.1f,
        // Back face
        -0.1f, -0.1f, -0.1f,
        -0.1f,  0.1f, -0.1f,
        0.1f,  0.1f, -0.1f,
        0.1f, -0.1f, -0.1f
    )

    private val cubeIndices = shortArrayOf(
        0, 1, 2, 0, 2, 3,    // front
        4, 5, 6, 4, 6, 7,    // back
        0, 4, 7, 0, 7, 1,    // bottom
        2, 6, 5, 2, 5, 3,    // top
        0, 3, 5, 0, 5, 4,    // left
        1, 7, 6, 1, 6, 2     // right
    )

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var indexBuffer: ByteBuffer

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "ARActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceview)
        displayRotationHelper = DisplayRotationHelper(this)

        // Configurar GLSurfaceView
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Verificar permisos
        if (!checkCameraPermission()) {
            requestCameraPermission()
        }

        // Touch listener para colocar objetos
        surfaceView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleTap(event.x, event.y)
            }
            true
        }

        // Preparar buffers
        prepareBuffers()
    }

    private fun prepareBuffers() {
        val bb = ByteBuffer.allocateDirect(cubeVertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(cubeVertices)
        vertexBuffer.position(0)

        indexBuffer = ByteBuffer.allocateDirect(cubeIndices.size * 2)
        indexBuffer.order(ByteOrder.nativeOrder())
        val shortBuffer = indexBuffer.asShortBuffer()
        shortBuffer.put(cubeIndices)
        indexBuffer.position(0)
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permiso concedido", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permiso de cámara requerido", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun handleTap(x: Float, y: Float) {
        session?.let { session ->
            try {
                // Actualizar sesión en el hilo GL
                surfaceView.queueEvent {
                    try {
                        val frame = session.update()
                        val hits = frame.hitTest(x, y)

                        for (hit in hits) {
                            val trackable = hit.trackable
                            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                                anchors.add(hit.createAnchor())
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "¡Objeto colocado!", Toast.LENGTH_SHORT).show()
                                }
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error al colocar objeto en GL thread", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al colocar objeto", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }

                if (!checkCameraPermission()) return

                session = Session(this)
                val config = Config(session)
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                session!!.configure(config)

            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando ARCore", e)
                Toast.makeText(this, "Error iniciando AR: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            session?.resume()
            surfaceView.onResume()
            displayRotationHelper?.onResume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Cámara no disponible", e)
            session = null
        }
    }

    override fun onPause() {
        super.onPause()
        displayRotationHelper?.onPause()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Crear shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        shaderProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(shaderProgram, vertexShader)
        GLES20.glAttachShader(shaderProgram, fragmentShader)
        GLES20.glLinkProgram(shaderProgram)

        // Obtener handles
        positionHandle = GLES20.glGetAttribLocation(shaderProgram, "vPosition")
        colorHandle = GLES20.glGetUniformLocation(shaderProgram, "vColor")
        mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix")

        // Crear textura para background
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        backgroundTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        // Configurar ARCore con la textura
        try {
            session?.setCameraTextureName(backgroundTextureId)
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando textura ARCore", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        displayRotationHelper?.onSurfaceChanged(width, height)
        session?.setDisplayGeometry(displayRotationHelper?.displayRotation ?: 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = this.session ?: return

        try {
            displayRotationHelper?.updateSessionIfNeeded(session)
            val frame = session.update()
            val camera = frame.camera

            if (camera.trackingState != TrackingState.TRACKING) {
                return
            }

            // Dibujar objetos en los anchors
            for (anchor in anchors) {
                if (anchor.trackingState == TrackingState.TRACKING) {
                    drawCube(camera, anchor.pose)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en render", e)
        }
    }

    private fun drawCube(camera: Camera, pose: Pose) {
        val projMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)

        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
        camera.getViewMatrix(viewMatrix, 0)
        pose.toMatrix(modelMatrix, 0)

        // Multiplicar matrices: MVP = Projection * View * Model
        val tempMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, tempMatrix, 0)

        // Usar shader program
        GLES20.glUseProgram(shaderProgram)

        // Configurar vertices
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

        // Color rojo para el cubo
        GLES20.glUniform4f(colorHandle, 1.0f, 0.2f, 0.2f, 1.0f)

        // Aplicar matriz
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // Dibujar
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, cubeIndices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}

// Clase helper para manejar rotación de pantalla
class DisplayRotationHelper(private val context: AppCompatActivity) {
    var displayRotation = 0
        private set

    fun onResume() {
        // Implementación básica
    }

    fun onPause() {
        // Implementación básica
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        displayRotation = when (context.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> 0
        }
    }

    fun updateSessionIfNeeded(session: Session) {
        val newRotation = when (context.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> 0
        }

        if (displayRotation != newRotation) {
            displayRotation = newRotation
            // La superficie ya maneja esto automáticamente
        }
    }
}
package es.elb4t.lupa

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import java.util.*




class MainActivity : AppCompatActivity() {
    val TAG = "PreviewBasico"
    private lateinit var textureview: TextureView
    private var mCameraId: String? = null
    private var mCameraDevice: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mPreviewRequestBuilder: CaptureRequest.Builder? = null
    private var dimensionesImagen: Size? = null
    //* Thread adicional para ejecutar tareas que no bloqueen Int usuario.
    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null

    var separacion = 0f
    var zoom_level = 1.0
    private var pixels_anchura_sensor: Int = 0
    private var pixels_altura_sensor: Int = 0
    var maxzoom: Float = 0.toFloat()
    var zoom: Rect? = null

    val PERMISOS = arrayOf(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.CAMERA
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ActivityCompat.requestPermissions(this, PERMISOS, 1)
        textureview = findViewById<View>(R.id.textureView) as TextureView
        assert(textureview != null)
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume")
        startBackgroundThread()
        Log.i(TAG, "Setting textureListener a textureview")
        textureview.surfaceTextureListener = textureListener
    }

    protected fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("Camera Background")
        mBackgroundThread?.start()
        mBackgroundHandler = Handler(mBackgroundThread?.looper)
    }

    protected fun stopBackgroundThread() {
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

    }

    private fun abrirCamara() {
        Log.i(TAG, "En abrir Camara")
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            mCameraId = manager.cameraIdList[0] //La primera cámara
            val characteristics = manager.getCameraCharacteristics(mCameraId)
            maxzoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            val m:Rect = characteristics.get( CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            pixels_anchura_sensor = m.width()
            pixels_altura_sensor = m.height()

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            dimensionesImagen = map.getOutputSizes(SurfaceTexture::class.java)[0]
            zoom_level = maxzoom.toDouble()

            val width = (pixels_anchura_sensor / zoom_level).toInt()
            val height = (pixels_altura_sensor / zoom_level).toInt()
            val startx = (pixels_anchura_sensor - width) / 2
            val starty = (pixels_altura_sensor - height) / 2
            val zonaActiva = Rect(startx, starty, startx + width, starty + height)

            zoom = zonaActiva

            Log.i(TAG, "Dimensiones Imagen = $dimensionesImagen Dimensiones Sensor: $m Maxzoom= $maxzoom")
            manager.openCamera(mCameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }


    private fun crearPreviewCamara() {
        try {
            val texture = textureview.surfaceTexture!!
            texture.setDefaultBufferSize(dimensionesImagen!!.width, dimensionesImagen!!.height)
            val surface = Surface(texture)
            mPreviewRequestBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mPreviewRequestBuilder?.addTarget(surface)
            mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            mPreviewRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, zoom)

            val statecallback: CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Configuration change failed", Toast.LENGTH_SHORT).show()
                }

                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    Log.i(TAG, "Sesión de captura configurada para preview")
                    //The camera is closed
                    if (null == mCameraDevice) {
                        return
                    }
                    // Cuando la sesion este lista empezamos a visualizer imags .
                    mCaptureSession = cameraCaptureSession
                    comenzarPreview()
                }
            }
            mCameraDevice?.createCaptureSession(Arrays.asList(surface), statecallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    protected fun comenzarPreview() {
        if (null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return")
        }
        try {
            mCaptureSession?.setRepeatingRequest(mPreviewRequestBuilder?.build(), null, mBackgroundHandler)
            Log.v(TAG, "*****setRepeatingRequest")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            1 -> {
                grantResults.forEach { result ->
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        Snackbar.make(textureview,
                                "Hay permisos necesarios para la aplicación sin activar", Snackbar.LENGTH_INDEFINITE)
                                .setAction("Activar", {
                                    ActivityCompat.requestPermissions(this, PERMISOS, 1)
                                }).show()
                        return
                    }
                }
                return
            }
        }
    }

    // Listeners
    private val textureListener: TextureView.SurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            //open your camera here
            Log.i(TAG, "Abriendo camara desde onSurfaceTextureAvailable")
            abrirCamara()
        }
    }

    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.e(TAG, "onOpened");
            mCameraDevice = camera
            crearPreviewCamara()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            mCameraDevice = null
        }
    }
}
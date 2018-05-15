package es.elb4t.lupa

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.design.widget.FloatingActionButton
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.io.*
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
    var zoom_level = 0.0
    private var pixels_anchura_sensor: Int = 0
    private var pixels_altura_sensor: Int = 0
    var maxzoom: Float = 0.toFloat()
    var zoom: Rect? = null

    private val btnCapture: FloatingActionButton? = null
    private var mJPEGRequestBuilder: CaptureRequest.Builder? = null
    private var mImageReader: ImageReader? = null
    private var dimensionesJPEG: Size? = null

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

        btnCaptura.setOnClickListener {
            tomarImagen()
        }
        textureview.setOnTouchListener(handleTouch)
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
            val m: Rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            pixels_anchura_sensor = m.width()
            pixels_altura_sensor = m.height()

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            dimensionesImagen = map.getOutputSizes(SurfaceTexture::class.java)[0]
            dimensionesJPEG = map.getOutputSizes(ImageFormat.JPEG)[0]
            //zoom_level = maxzoom.toDouble()

            val width = (pixels_anchura_sensor / zoom_level).toInt()
            val height = (pixels_altura_sensor / zoom_level).toInt()
            val startx = (pixels_anchura_sensor - width) / 2
            val starty = (pixels_altura_sensor - height) / 2
            val zonaActiva = Rect(startx, starty, startx + width, starty + height)

            zoom = zonaActiva

            Log.e(TAG, "Dimensiones Imagen = $dimensionesImagen , Dimensiones JPEG = $dimensionesJPEG , Dimensiones Sensor: $m Maxzoom= $maxzoom")
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
        configurarImageReader()
        try {
            val texture = textureview.surfaceTexture!!
            texture.setDefaultBufferSize(dimensionesImagen!!.width, dimensionesImagen!!.height)
            val surface = Surface(texture)
            mPreviewRequestBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mPreviewRequestBuilder?.addTarget(surface)
            mPreviewRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, zoom)
            mCameraDevice?.createCaptureSession(
                    Arrays.asList(surface, mImageReader?.surface),
                    cameraCaptureSessionStatecallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun configurarImageReader() {
        try {
            mImageReader = ImageReader.newInstance(dimensionesJPEG!!.width, dimensionesJPEG!!.height, ImageFormat.JPEG, 1)
            mImageReader?.setOnImageAvailableListener(readerListener, mBackgroundHandler)
            mJPEGRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

            //Decirle donde se dejarán las imágenes
            mJPEGRequestBuilder?.addTarget(mImageReader?.surface)

        } catch (e: CameraAccessException) {
            e.printStackTrace();
        }
    }

    protected fun updatePreview() {
        if (null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return")
        }
        mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        try {
            mCaptureSession?.setRepeatingRequest(mPreviewRequestBuilder?.build(), null, mBackgroundHandler)
            Log.i(TAG, "*****setRepeatingRequest. Captura preview arrancada")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun tomarImagen() {
        try {
            mJPEGRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            mJPEGRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            mJPEGRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            mJPEGRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, zoom)

            val captureCallback: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    comenzarPreview()
                }
            }
            mCaptureSession?.capture(mJPEGRequestBuilder?.build(), captureCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun getFingerSpacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
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

    private val cameraCaptureSessionStatecallback: CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
            if (null == mCameraDevice) {
                return
            }
            mCaptureSession = cameraCaptureSession
            updatePreview()
        }

        override fun onConfigureFailed(camCaptureSession: CameraCaptureSession) {
            Toast.makeText(this@MainActivity, "Configuracion fallida", Toast.LENGTH_SHORT).show()
        }
    }

    private val readerListener: ImageReader.OnImageAvailableListener = object : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            var imagen: Image? = null
            try {
                imagen = reader.acquireLatestImage()
                //Aquí se podría guardar o procesar la imagen
                val buffer = imagen.planes[0].buffer
                val bytes = ByteArray(buffer.capacity())
                buffer.get(bytes)
                guardar(bytes)
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                if (imagen != null) {
                    imagen.close()
                }
            }
        }

        private fun guardar(bytes: ByteArray) {
            val fichero: File = File("${Environment.getExternalStorageDirectory()}/micamara2.jpg")
            Toast.makeText(this@MainActivity, "/micamara2.jpg saved", Toast.LENGTH_SHORT).show()

            var output: OutputStream? = null
            try {
                output = FileOutputStream(fichero)
                output.write(bytes)
                output.close()
            } finally {
                if (null != output) {
                    output.close()
                }
            }
        }
    }

    private val handleTouch: View.OnTouchListener = View.OnTouchListener { v, event ->
        val action = event.action
        val sep_actual: Float
        if (event.getPointerCount() > 1) {
            sep_actual = getFingerSpacing(event)
            if (separacion != 0f) {
                if (sep_actual > separacion && maxzoom > zoom_level + 0.1) {
                    zoom_level += 0.1
                } else if (sep_actual < separacion && zoom_level >= 1.1) {
                    zoom_level -= 0.1
                }
                val width = (pixels_anchura_sensor / zoom_level).toInt()
                val height = (pixels_altura_sensor / zoom_level).toInt()
                val startx = (pixels_anchura_sensor - width) / 2
                val starty = (pixels_altura_sensor - height) / 2
                val zonaActiva = Rect(startx, starty, startx + width, starty + height)
                zoom = zonaActiva
                mPreviewRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, zonaActiva)
            }
            separacion = sep_actual
        } else {
            separacion = 0f
        }
        try {
            mCaptureSession?.setRepeatingRequest(mPreviewRequestBuilder?.build(), null, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (ex: NullPointerException) {
            ex.printStackTrace()
        }
        return@OnTouchListener true
    }
}
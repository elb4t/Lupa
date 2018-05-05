package es.elb4t.lupa

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
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
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import java.io.*
import kotlin.experimental.and


class MainActivity : AppCompatActivity() {
    val TAG = "PreviewBasico"
    private lateinit var textureview: TextureView
    private var mCameraId: String? = null
    private var mCameraDevice: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mPreviewRequestBuilder: CaptureRequest.Builder? = null
    private lateinit var dimensionesImagen: Size
    //* Thread adicional para ejecutar tareas que no bloqueen Int usuario.
    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null

    val PERMISOS = arrayOf(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.CAMERA
    )

    private lateinit var btnCapture: FloatingActionButton
    private lateinit var mJPEGRequestBuilder: CaptureRequest.Builder
    private lateinit var mImageReader: ImageReader
    private var dimensionesPreview: Size? = null
    private var dimensionesJPEG: Size? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ActivityCompat.requestPermissions(this, PERMISOS, 1)
        textureview = findViewById<View>(R.id.textureView) as TextureView
        assert(textureview != null)

        btnCapture = findViewById(R.id.btnCaptura)
        btnCapture.setOnClickListener(View.OnClickListener {
            tomarImagen()
        })
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume")
        startBackgroundThread()
        Log.i(TAG, "Setting textureListener a textureview")
        textureview.surfaceTextureListener = textureListener
    }

    override fun onStop() {
        super.onStop()
        stopBackgroundThread()
    }

    val textureListener: TextureView.SurfaceTextureListener = object : TextureView.SurfaceTextureListener {
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
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            configurarImageReader(map)
            dimensionesPreview = map.getOutputSizes(SurfaceTexture::class.java)[0] //El primer tamaño posible. Normalmente el mayor
            dimensionesJPEG = map.getOutputSizes(ImageFormat.JPEG)[0] //El primer tamaño posible. Normalmente el mayor
            Log.e(TAG, "Dimensiones Preview = $dimensionesPreview")
            Log.e(TAG, "Dimensiones JPEG = $dimensionesJPEG")

            manager.openCamera(mCameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.e(TAG, "onOpened")
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

    private fun crearPreviewCamara() {
        try {
            val texture = textureview.surfaceTexture!!
            texture.setDefaultBufferSize(dimensionesPreview!!.width, dimensionesPreview!!.height)
            val surface = Surface(texture)
            mPreviewRequestBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            var surfaces: ArrayList<Surface> = ArrayList()
            mPreviewRequestBuilder?.addTarget(surface)
            surfaces.add(surface)
            mPreviewRequestBuilder?.addTarget(mImageReader.surface)
            surfaces.add(mImageReader.surface)

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
            mCameraDevice?.createCaptureSession(surfaces, statecallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun comenzarPreview() {
        if (null == mCameraDevice) {
            Log.e(TAG, "Error en comenzarPreview")
        }
        mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        try {
            mCaptureSession?.setRepeatingRequest(mPreviewRequestBuilder?.build(), null, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun configurarImageReader() {
        try {
            mImageReader = ImageReader.newInstance(dimensionesJPEG!!.width, dimensionesJPEG!!.height, ImageFormat.JPEG, 1)
            mImageReader.setOnImageAvailableListener(readerListener, mBackgroundHandler)
            mJPEGRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

            //Decirle donde se dejarán las imágenes
            mJPEGRequestBuilder.addTarget(mImageReader.surface)

        } catch (e: CameraAccessException) {
            e.printStackTrace();
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
            mJPEGRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            mJPEGRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            mJPEGRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            val CaptureCallback: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    comenzarPreview()
                }
            }
            mCaptureSession?.capture(mJPEGRequestBuilder.build(), CaptureCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    fun configurarImageReader(map: StreamConfigurationMap) {
        val tam = map.getOutputSizes(ImageFormat.YUV_420_888)[0]
        mImageReader = ImageReader.newInstance(tam.width, tam.height, ImageFormat.YUV_420_888, 2)
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)
    }

    private val mOnImageAvailableListener: ImageReader.OnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val imagen = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        procesarImagen(imagen)
        imagen.close()
    }

    private fun procesarImagen(imagen: Image){
        val width = imagen.width
        val height = imagen.height
        val planes:Array<Image.Plane> = imagen.planes
        val yplane:Image.Plane= planes[0]
        val uplane:Image.Plane= planes[1]

        val y_pixelstride = yplane.pixelStride
        val y_rowstride = yplane.rowStride
        val u_pixelstride = uplane.pixelStride
        val u_rowstride = uplane.rowStride

        Log.v(TAG, "Tengo una imagen YUV420 !! $width x $height")
        Log.v(TAG, "Tengo una imagen YUV420 Stride PixelY= $y_pixelstride StridePixelUV= $u_pixelstride StrideRowY= $y_rowstride RowStrideUV= $u_rowstride ")
        val x = width / 2
        val y = height / 2

        val yBuffer = imagen.planes[0].buffer
        val pixelcentral:Byte = yBuffer.get(x * y_pixelstride + y * y_rowstride)
        val pixel = pixelcentral and 0xFF.toByte()
        Log.v(TAG, "Tengo una imagen YUV420. Pixel Central=$pixel")
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
}

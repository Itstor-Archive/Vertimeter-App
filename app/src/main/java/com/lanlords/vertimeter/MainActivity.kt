package com.lanlords.vertimeter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.graphics.toColorInt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.components.BuildConfig
import com.lanlords.vertimeter.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    val viewModel: MainViewModel by viewModels()

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraExecutor: ExecutorService
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var imageAnalysis: ImageAnalysis? = null

    private lateinit var pickVideoLauncher: ActivityResultLauncher<Intent>

    private val instructions = listOf(
        "Step back until your entire body is within the camera's frame.",
        "Remain still when the countdown appears.",
        "Jump as soon as the countdown finishes."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        pickVideoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val selectedVideoUri = result.data?.data
                if (selectedVideoUri != null) {
                    showSettingsDialog {
                            viewModel.startJumpVideoAnalysis(selectedVideoUri, this@MainActivity)
                    }
                }
            }
        }

        binding.tvDebug.visibility = if (BuildConfig.DEBUG) android.view.View.VISIBLE else android.view.View.GONE

        viewModel.countdownTime.observe(this) {
            if (it > 0) {
                binding.tvCountdown.text = it.toString()
            } else {
                hideAllDialog()
            }
        }

        viewModel.toastMessage.observe(this) {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }

        viewModel.analysisState.observe(this) {
            val buttonColor = if (it == AnalysisState.NOT_STARTED || it == AnalysisState.DONE) {
                "#00000000".toColorInt() // Transparent
            } else {
                "#FF0000".toColorInt() // Red
            }

            binding.btnStart.setBackgroundColor(buttonColor)

            binding.btnSwitchCamera.isActivated = it == AnalysisState.NOT_STARTED || it == AnalysisState.DONE

            when (it) {
                AnalysisState.NOT_STARTED -> {
                    hideAllDialog()
                }
                AnalysisState.WAITING_FOR_BODY -> {
                    showInfoDialog(InfoType.STEP_BACK)
                }
                AnalysisState.BODY_IN_FRAME -> {
                    showInfoDialog(InfoType.STOP)
                }
                AnalysisState.COUNTDOWN -> {
                    showCountdownDialog()
                }
                AnalysisState.VIDEO_ANALYZING -> {
                    showVideoAnalyzeProgressDialog()
                }
                AnalysisState.DONE -> {
                    hideAllDialog()
                    showResultDialog()
                    stopImageAnalysis()
                }
                else -> {
                    hideAllDialog()
                }
            }
        }

        binding.btnVideoGallery.setOnClickListener {
            val pickVideoIntent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            pickVideoLauncher.launch(pickVideoIntent)

        }

        binding.btnSwitchCamera.setOnClickListener {
            switchCamera()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnStart.setOnClickListener {
            if (viewModel.analysisState.value != AnalysisState.NOT_STARTED) {
                stopImageAnalysis()
                viewModel.reset()

                return@setOnClickListener
            }

            if (viewModel.height.value == null) {
                Toast.makeText(this, "Please set your height first", Toast.LENGTH_SHORT).show()
                showSettingsDialog()

                return@setOnClickListener
            }

            if (!viewModel.skipInstructions) {
                showInstructionDialog {
                    startImageAnalysis()
                }
            } else {
                startImageAnalysis()
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun showVideoAnalyzeProgressDialog() {
        binding.flCountdown.visibility = android.view.View.GONE
        binding.flInfo.visibility = android.view.View.GONE

        binding.flVideoProcessing.visibility = android.view.View.VISIBLE
    }

    @SuppressLint("SetTextI18n")
    private fun showInfoDialog(type: InfoType) {
        // Hide countdown
        binding.flCountdown.visibility = android.view.View.GONE
        binding.flVideoProcessing.visibility = android.view.View.GONE

        when (type) {
            InfoType.STEP_BACK -> {
                binding.ivInfo.setImageResource(R.drawable.baseline_info_outline_24)
                binding.tvInfo.text = "Step back"
            }

            InfoType.STOP -> {
                binding.ivInfo.setImageResource(R.drawable.baseline_front_hand_24)
                binding.tvInfo.text = "Stop"
            }
        }

        binding.flInfo.visibility = android.view.View.VISIBLE
    }

    private fun showCountdownDialog() {
        binding.flInfo.visibility = android.view.View.GONE
        binding.flVideoProcessing.visibility = android.view.View.GONE
        binding.flCountdown.visibility = android.view.View.VISIBLE
    }

    private fun hideAllDialog() {
        binding.flInfo.visibility = android.view.View.GONE
        binding.flCountdown.visibility = android.view.View.GONE
        binding.flVideoProcessing.visibility = android.view.View.GONE
    }

    private fun showResultDialog() {
        val df = java.text.DecimalFormat("#.##")

        MaterialAlertDialogBuilder(this).setTitle("Result")
            .setMessage("You jumped ${df.format(viewModel.jumpResult!!.jumpHeight)} cm high!\n" +
                    "Your jump duration was ${df.format(viewModel.jumpResult!!.jumpDuration)} seconds.")
            .setPositiveButton("Detail") { _, _ ->
                val intent = android.content.Intent(this, ResultActivity::class.java)
                intent.putExtra(ResultActivity.JUMP_RESULT, viewModel.jumpResult)
                viewModel.reset()
                startActivity(intent)}
            .setNeutralButton("Close") { _, _ -> viewModel.reset()}.show()
    }

    private fun showInstructionDialog(onStart: () -> Unit) {
        val builder = MaterialAlertDialogBuilder(this).setTitle("Instructions")
            .setMessage(instructions[viewModel.instructionsCurrentStep])
            .setNeutralButton("Cancel") { _, _ -> }

        if (viewModel.instructionsCurrentStep > 0) {
            builder.setNegativeButton("Prev") { _, _ ->
                if (viewModel.instructionsCurrentStep > 0) {
                    viewModel.instructionsCurrentStep--
                    showInstructionDialog(onStart)
                }
            }
        } else {
            builder.setNegativeButton("Skip") { _, _ ->
                viewModel.skipInstructions = true
                onStart()
            }
        }

        builder.setPositiveButton(if (viewModel.instructionsCurrentStep < instructions.size - 1) "Next" else "Start") { _, _ ->
            if (viewModel.instructionsCurrentStep < instructions.size - 1) {
                viewModel.instructionsCurrentStep++
                showInstructionDialog(onStart)
            } else {
                viewModel.skipInstructions = true
                onStart()
            }
        }
        builder.show()
    }

    private fun showSettingsDialog(onClickSave: (() -> Unit)? = null) {
        val editText = EditText(this)
        val frameLayout = FrameLayout(this)

        editText.hint = "Height in cm"
        editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        if (viewModel.height.value != null) {
            editText.setText(viewModel.height.value.toString())
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )

        params.leftMargin = 58
        params.rightMargin = 58

        frameLayout.addView(editText, params)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setMessage("Input your height in cm:")
            .setView(frameLayout)
            .setNeutralButton("Cancel") { _, _ -> }
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            val button = (it as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                editText.text.toString().toIntOrNull()?.let { height ->
                    if (height > 0) {
                        viewModel.setHeight(height)
                        if (onClickSave != null) onClickSave()
                        Toast.makeText(this, "Height saved", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this, "Invalid height", Toast.LENGTH_SHORT).show()
                    }
                } ?: run {
                    Toast.makeText(this, "Invalid height", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )
            } catch (exc: Exception) {
                Toast.makeText(
                    this,
                    "Unable to start camera",
                    Toast.LENGTH_SHORT
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startImageAnalysis() {
        viewModel.setAnalysisState(AnalysisState.WAITING_FOR_BODY)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageAnalysis = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, JumpAnalyzer(this))
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Toast.makeText(
                    this,
                    "Unable to start camera",
                    Toast.LENGTH_SHORT
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopImageAnalysis() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            imageAnalysis?.let {
                cameraProvider.unbind(it)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun switchCamera() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing)
            CameraSelector.LENS_FACING_BACK
        else
            CameraSelector.LENS_FACING_FRONT

        startCamera()
    }

    @SuppressLint("SetTextI18n")
    fun setDebugText(message: String? = null, pxToCmScale: Float? = null) {
        val state = viewModel.analysisState.value.toString()

        binding.tvDebug.text = "state: $state\nmessage: $message\npxToCmScale: $pxToCmScale"
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

enum class InfoType {
    STEP_BACK,
    STOP
}

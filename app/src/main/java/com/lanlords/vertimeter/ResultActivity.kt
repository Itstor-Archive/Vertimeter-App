package com.lanlords.vertimeter

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.lanlords.vertimeter.databinding.ActivityResultBinding
import java.text.DecimalFormat

class ResultActivity : AppCompatActivity() {
    private lateinit var binding: ActivityResultBinding

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)

        setContentView(binding.root)

        setSupportActionBar(binding.tlMain)
        title = "Jump Result"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val time = System.currentTimeMillis()

        val jumpResult = intent.getParcelableExtra<JumpResult>(JUMP_RESULT)
        if (jumpResult == null) {
            finish()
            return
        }
        val df = DecimalFormat("#.##")
        binding.tvUserHeight.text = jumpResult.height.toString() + " cm"
        binding.tvJumpPeak.text = df.format(jumpResult.jumpHeight) + " cm"
        binding.tvJumpDuration.text = df.format(jumpResult.jumpDuration) + " s"

        val entries = ArrayList<Entry>()
        jumpResult.jumpData.forEach {
            entries.add(Entry(it.key, it.value))
        }

        val dataSet = LineDataSet(entries, "Jump Data")

        val textColor = if (isDarkTheme()) {
            ResourcesCompat.getColor(resources, R.color.white, null)
        } else {
            ResourcesCompat.getColor(resources, R.color.black, null)
        }

        binding.chResult.xAxis.textColor = textColor
        binding.chResult.axisLeft.textColor = textColor
        binding.chResult.axisRight.textColor = textColor

        binding.chResult.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawAxisLine(true)
            setDrawGridLines(false)
            granularity = 1f
            axisMinimum = 0f
            textSize = 12f
            valueFormatter = XAxisFormatter()
        }

        binding.chResult.axisLeft.apply {
            setDrawAxisLine(true)
            setDrawGridLines(true)
            granularity = 1f
            axisMinimum = 0f
            textSize = 12f
        }

        binding.chResult.data = LineData(dataSet)
        binding.chResult.description.isEnabled = false
        binding.chResult.invalidate()

        binding.btnSaveCsv.setOnClickListener {
            val csv = StringBuilder()
            csv.append("Time (s),Height (cm)\n")
            jumpResult.jumpData.forEach {
                csv.append("${it.key},${it.value}\n")
            }

            val filename = "jump_data_${time}.csv"
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            try {
                uri?.let {
                    resolver.openOutputStream(it)?.bufferedWriter().use { writer ->
                        writer?.write(csv.toString())
                    }
                    Toast.makeText(this, "Saved to ${Environment.DIRECTORY_DOWNLOADS}/$filename", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to save file", Toast.LENGTH_LONG).show()
            } finally {
            }
        }

        binding.btnSavePdf.setOnClickListener {
            val chartBitmap = Bitmap.createBitmap(binding.chResult.width, binding.chResult.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(chartBitmap)
            binding.chResult.draw(canvas)

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(chartBitmap.width, chartBitmap.height + 250, 1).create()
            val page = pdfDocument.startPage(pageInfo)

            page.canvas.drawBitmap(chartBitmap, 0f, 0f, null)
            val textPaint = Paint().apply {
                color = Color.BLACK
                textSize = 36f
            }
            page.canvas.drawText("User Height: ${jumpResult.height} cm", 50f, chartBitmap.height + 50f, textPaint)
            page.canvas.drawText("Jump Height: ${binding.tvJumpPeak.text}", 50f, chartBitmap.height + 100f, textPaint)
            page.canvas.drawText("Jump Duration: ${binding.tvJumpDuration.text}", 50f, chartBitmap.height + 150f, textPaint)
            page.canvas.drawText("Date Taken: ${DateFormat.format("dd/MM/yyyy hh:mm:ss", time)}", 50f, chartBitmap.height + 200f, textPaint)

            pdfDocument.finishPage(page)

            val filename = "jump_data_${time}.pdf"
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            try {
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        pdfDocument.writeTo(outputStream)
                        Toast.makeText(this, "Saved to ${Environment.DIRECTORY_DOWNLOADS}/$filename", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to save PDF", Toast.LENGTH_LONG).show()
            } finally {
                pdfDocument.close()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun isDarkTheme(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val TAG = "ResultActivity"
        const val JUMP_RESULT = "jumpResult"
    }
}

class XAxisFormatter : ValueFormatter() {
    override fun getFormattedValue(value: Float): String {
        return String.format("%.2f", value)
    }
}
package com.example.budget_predictor_android

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.onnxruntime.*
import java.nio.FloatBuffer
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView = findViewById<TextView>(R.id.outputTextView)

        try {
            val ortEnv = OrtEnvironment.getEnvironment()
            val session = ortEnv.createSession(
                assets.open("model_ratio_U001.onnx").readBytes()
            )

            val inputTensor = OnnxTensor.createTensor(
                ortEnv,
                generateCategoryInputSequence(),
                longArrayOf(1, 21, 29)
            )

            val inputMap = mapOf("input" to inputTensor)

            val start = SystemClock.elapsedRealtime()
            val results = session.run(inputMap)
            val end = SystemClock.elapsedRealtime()

            val yScaled = (results[0].value as Array<FloatArray>)[0]

            val mean = floatArrayOf(5.173333f, 1.000000f, 3.833333f, 100000.000000f, 0.011111f, 0.093333f, 0.055556f, 0.066667f, 0.046667f, 0.046667f, 0.048889f, 0.048889f, 0.048889f, 0.046667f, 0.046667f, 1265.651852f, 974.277778f, 110.688889f, 15.777778f, 791.200000f, 16.000000f, 80.288889f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f)
            val scale = floatArrayOf(8.870986f, 1.634353f, 6.494870f, 141421.356237f, 0.104822f, 0.290899f, 0.229061f, 0.249444f, 0.210924f, 0.210924f, 0.215636f, 0.215636f, 0.215636f, 0.210924f, 0.210924f, 2351.353313f, 2026.558654f, 546.360524f, 159.568245f, 1735.756338f, 99.947394f, 869.605099f, 1.000000f, 1.000000f, 1.000000f, 1.000000f, 1.000000f, 1.000000f, 1.000000f)

            val yReal = FloatArray(7) { i ->
                yScaled[i] * scale[i] + mean[i]
            }

            val labels = listOf("Food", "Transport", "Entertainment", "Clothing", "Utilities", "Social", "Other")
            val resultText = yReal.mapIndexed { i, v ->
                "${labels[i]}: ¥${"%,.0f".format(v)}"
            }.joinToString("\n")

            textView.text = "カテゴリ別予測:\n$resultText\n処理時間: ${end - start} ms"
            Log.d("ONNX", "✅ 推論結果: $resultText")
            Log.d("ONNX", "🕒 推論時間: ${end - start} ms")

        } catch (e: Exception) {
            textView.text = "エラー: ${e.message}"
            Log.e("ONNX", "❌ 推論エラー", e)
        }
    }
}

fun generateCategoryInputSequence(): FloatBuffer {
    val sequenceLength = 21
    val featureSize = 29
    val inputData = FloatArray(sequenceLength * featureSize)

    val fixedCategories = listOf("food", "transport", "entertainment", "clothing_beauty_daily", "utilities", "social", "other")
    val rollingWindow = 3
    val categorySumsHistory = mutableListOf<Map<String, Float>>()

    for (i in 0 until sequenceLength) {
        val date = LocalDate.now().minusDays((sequenceLength - 1 - i).toLong())
        val day = date.dayOfMonth.toFloat()
        val month = date.monthValue.toFloat()
        val week = date.get(WeekFields.ISO.weekOfWeekBasedYear()).toFloat()

        val isPayday = if (day == 25f) 1f else 0f
        val isWeekend = if (date.dayOfWeek.value >= 6) 1f else 0f
        val isMonthStart = if (day <= 5f) 1f else 0f
        val isMonthEnd = if (day >= 25f) 1f else 0f
        val monthlyIncome = 300000f

        val dow = (date.dayOfWeek.value % 7)
        val dowOneHot = FloatArray(7) { if (it == dow) 1f else 0f }

        val dailyCategorySums = fixedCategories.associateWith { (1000..10000).random().toFloat() }
        categorySumsHistory.add(dailyCategorySums)

        val rollingMean = fixedCategories.map { cat ->
            val recentValues = categorySumsHistory.takeLast(rollingWindow).map { it[cat] ?: 0f }
            if (recentValues.isNotEmpty()) recentValues.average().toFloat() else 0f
        }

        val diff = if (i > 0) fixedCategories.mapIndexed { idx, cat ->
            abs((dailyCategorySums[cat] ?: 0f) - (categorySumsHistory[i - 1][cat] ?: 0f))
        } else List(fixedCategories.size) { 0f }

        val std = fixedCategories.map { cat ->
            val values = categorySumsHistory.takeLast(rollingWindow).map { it[cat] ?: 0f }
            val mean = values.average().toFloat()
            val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
            sqrt(variance)
        }

        val spikeFlag = diff.mapIndexed { idx, d -> if (std[idx] != 0f && d > 2 * std[idx]) 1f else 0f }

        val offset = i * featureSize
        inputData[offset + 0] = day
        inputData[offset + 1] = month
        inputData[offset + 2] = week
        inputData[offset + 3] = monthlyIncome
        inputData[offset + 4] = isPayday
        inputData[offset + 5] = isWeekend
        inputData[offset + 6] = isMonthStart
        inputData[offset + 7] = isMonthEnd
        for (j in 0 until 7) inputData[offset + 8 + j] = dowOneHot[j]
        for (j in 0 until 7) inputData[offset + 15 + j] = rollingMean[j]
        for (j in 0 until 7) inputData[offset + 22 + j] = spikeFlag[j]
    }

    return FloatBuffer.wrap(inputData)
}

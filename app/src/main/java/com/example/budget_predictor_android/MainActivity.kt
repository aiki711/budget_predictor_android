package com.example.budget_predictor_android

import ai.onnxruntime.*
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.nio.FloatBuffer
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val predictMonthButton = findViewById<Button>(R.id.predictMonthButton)
        val predictWeekButton = findViewById<Button>(R.id.predictWeekButton)
        val outputTextView = findViewById<TextView>(R.id.outputTextView)

        val categoryNames = listOf("食費", "交通", "娯楽", "衣類・美容・日用品", "光熱費", "交際費", "その他")

        val userBudget = loadLastMonthBudget(this, categoryNames)

        fun getRisk(pred: Float, budget: Float): String {
            return when {
                pred > budget * 1.2 -> "🔴 高リスク"
                pred > budget -> "🟠 中リスク"
                else -> "🟢 低リスク"
            }
        }

        predictMonthButton.setOnClickListener {
            try {
                val inputRatio = generateScaledInputFromCsv(this)
                val inputTotal = generateTotalInputFromCsv(this)

                val totalAmountMonth = predictDays(this, inputTotal, 30)
                val ratioArray = runRatioInference(this, inputRatio)

                val categoryDetails = mutableListOf<String>()
                val riskSummaries = mutableListOf<String>()

                ratioArray.mapIndexed { i, r ->
                    val yen = r * totalAmountMonth
                    val name = categoryNames.getOrElse(i) { "カテゴリ${i + 1}" }
                    val budget = userBudget[name] ?: 0f
                    val risk = getRisk(yen, budget)
                    categoryDetails.add("$name: %.0f 円".format(yen))
                    riskSummaries.add("$name（予算: %.0f 円）→ %s".format(budget, risk))
                }

                outputTextView.text = "\uD83D\uDCC5 月末までの予測支出合計: %.0f 円\n\n%s\n\n\u26A0\uFE0F リスク評価\n%s".format(
                    totalAmountMonth,
                    categoryDetails.joinToString("\n"),
                    riskSummaries.joinToString("\n")
                )

            } catch (e: Exception) {
                outputTextView.text = "⚠️ 月末予測エラー: ${e.message}"
                Log.e("DEBUG", "月末予測失敗", e)
            }
        }

        predictWeekButton.setOnClickListener {
            try {
                val inputRatio = generateScaledInputFromCsv(this)
                val inputTotal = generateTotalInputFromCsv(this)

                val totalAmountWeek = predictDays(this, inputTotal, 7)
                val ratioArray = runRatioInference(this, inputRatio)

                val categoryDetails = mutableListOf<String>()
                val riskSummaries = mutableListOf<String>()

                ratioArray.mapIndexed { i, r ->
                    val yen = r * totalAmountWeek
                    val name = categoryNames.getOrElse(i) { "カテゴリ${i + 1}" }
                    val budget = userBudget[name] ?: 0f
                    val risk = getRisk(yen, budget)
                    categoryDetails.add("$name: %.0f 円".format(yen))
                    riskSummaries.add("$name（予算: %.0f 円）→ %s".format(budget, risk))
                }

                outputTextView.text = "\uD83D\uDD52 7日後までの予測支出合計: %.0f 円\n\n%s\n\n\u26A0\uFE0F リスク評価\n%s".format(
                    totalAmountWeek,
                    categoryDetails.joinToString("\n"),
                    riskSummaries.joinToString("\n")
                )

            } catch (e: Exception) {
                outputTextView.text = "⚠️ 7日予測エラー: ${e.message}"
                Log.e("DEBUG", "7日予測失敗", e)
            }
        }
    }

    fun predictDays(context: Context, initialInput: FloatBuffer, nDays: Int): Float {
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(context.assets.open("model_total_U001.onnx").readBytes(), OrtSession.SessionOptions())
        val shape = longArrayOf(1, 21, 17)

        val inputArray = initialInput.array()
        val featureSize = 17
        val result = mutableListOf<Float>()

        val totalMean = 9871.933333f
        val totalStd = 7781.569396f

        var current = inputArray.copyOf()

        for (i in 0 until nDays) {
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(current), shape)
            val output = session.run(mapOf("input" to inputTensor))
            val predZ = (output[0].value as Array<FloatArray>)[0][0]
            val predYen = predZ * totalStd + totalMean
            result.add(predYen)

            val shifted = current.copyOfRange(featureSize, current.size) + FloatArray(featureSize) { 0f }
            shifted[shifted.lastIndex - (featureSize - 1)] = predZ
            current = shifted
        }

        return result.sum()
    }

    fun loadLastMonthBudget(context: Context, categoryNames: List<String>): Map<String, Float> {
        val file = File(context.filesDir, "spending.csv")
        if (!file.exists()) return categoryNames.associateWith { 0f }

        val lines = file.readLines()
        val lastMonth = YearMonth.now().minusMonths(1)

        val filtered = lines.mapNotNull {
            val parts = it.split(",")
            if (parts.size >= 3) {
                val date = try {
                    OffsetDateTime.parse(parts[0]).toLocalDate()
                } catch (e: Exception) {
                    null
                }
                val category = parts[1]
                val amount = parts[2].toFloatOrNull() ?: 0f
                if (date != null && YearMonth.from(date) == lastMonth) Pair(category, amount) else null
            } else null
        }

        val budgetMap = mutableMapOf<String, Float>()
        for ((category, amount) in filtered) {
            budgetMap[category] = budgetMap.getOrDefault(category, 0f) + amount
        }
        budgetMap["total_budget"] = budgetMap.values.sum()
        return budgetMap
    }


    fun generateScaledInputFromCsv(context: Context): FloatBuffer {
        val file = File(context.filesDir, "spending.csv")
        if (!file.exists()) throw Exception("spending.csv が存在しません")

        val lines = file.readLines()
        val records = lines.mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size == 3) Triple(parts[0], parts[1], parts[2].toFloatOrNull() ?: 0f) else null
        }

        val grouped = records.groupBy { it.first }.mapValues { (_, list) ->
            list.groupBy({ it.second }, { it.third }).mapValues { it.value.sum() }
        }

        val dates = grouped.keys.sorted().takeLast(21)
        val fixedCategories = listOf("food", "transport", "entertainment", "clothing_beauty_daily", "utilities", "social", "other")
        val input = FloatArray(21 * 29)
        val rollingWindow = 3
        val categorySumsHistory = mutableListOf<Map<String, Float>>()

        for ((i, dateStr) in dates.withIndex()) {
            val date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_DATE)
            val day = date.dayOfMonth.toFloat()
            val month = date.monthValue.toFloat()
            val week = date.get(WeekFields.ISO.weekOfWeekBasedYear()).toFloat()
            val isPayday = if (day == 25f) 1f else 0f
            val isWeekend = if (date.dayOfWeek.value >= 6) 1f else 0f
            val isMonthStart = if (day <= 5f) 1f else 0f
            val isMonthEnd = if (day >= 25f) 1f else 0f
            val monthlyIncome = 300000f
            val dow = date.dayOfWeek.value % 7
            val dowOneHot = FloatArray(7) { if (it == dow) 1f else 0f }

            val dailyCat = grouped[dateStr] ?: emptyMap()
            categorySumsHistory.add(dailyCat)
            val total = dailyCat.values.sum().takeIf { it > 0 } ?: 1f
            val recent = categorySumsHistory.takeLast(rollingWindow)

            val rollingMean = fixedCategories.map { cat ->
                recent.map { it[cat] ?: 0f }.average().toFloat()
            }

            val prev = if (i > 0) categorySumsHistory[i - 1] else emptyMap()
            val diff = fixedCategories.map { cat ->
                abs((dailyCat[cat] ?: 0f) - (prev[cat] ?: 0f))
            }

            val std = fixedCategories.map { cat ->
                val vals = recent.map { it[cat] ?: 0f }
                val mean = vals.average().toFloat()
                sqrt(vals.map { (it - mean) * (it - mean) }.average().toFloat())
            }

            val spike = diff.mapIndexed { idx, d -> if (std[idx] != 0f && d > 2 * std[idx]) 1f else 0f }

            val offset = i * 29
            input[offset + 0] = day
            input[offset + 1] = month
            input[offset + 2] = week
            input[offset + 3] = monthlyIncome
            input[offset + 4] = isPayday
            input[offset + 5] = isWeekend
            input[offset + 6] = isMonthStart
            input[offset + 7] = isMonthEnd
            for (j in 0 until 7) input[offset + 8 + j] = dowOneHot[j]
            for (j in 0 until 7) input[offset + 15 + j] = rollingMean[j]
            for (j in 0 until 7) input[offset + 22 + j] = spike[j]
        }

        return FloatBuffer.wrap(scaleInput(input))
    }

    fun scaleInput(input: FloatArray): FloatArray {
        val mean = floatArrayOf(
            5.173333f, 1.000000f, 3.833333f, 100000.000000f, 0.011111f, 0.093333f, 0.055556f,
            0.066667f, 0.046667f, 0.046667f, 0.048889f, 0.048889f, 0.048889f, 0.046667f, 0.046667f,
            1265.651852f, 974.277778f, 110.688889f, 15.777778f, 791.200000f, 16.000000f, 80.288889f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f
        )
        val scale = floatArrayOf(
            8.870986f, 1.634353f, 6.494870f, 141421.356237f, 0.104822f, 0.290899f, 0.229061f,
            0.249444f, 0.210924f, 0.210924f, 0.215636f, 0.215636f, 0.215636f, 0.210924f, 0.210924f,
            2351.353313f, 2026.558654f, 546.360524f, 159.568245f, 1735.756338f, 99.947394f, 869.605099f,
            1f, 1f, 1f, 1f, 1f, 1f, 1f
        )
        return input.mapIndexed { i, x -> (x - mean[i % 29]) / scale[i % 29] }.toFloatArray()
    }

    fun generateTotalInputFromCsv(context: Context): FloatBuffer {
        val file = File(context.filesDir, "spending.csv")
        if (!file.exists()) throw Exception("spending.csv が存在しません")

        val lines = file.readLines()
        val daily = lines.mapNotNull {
            val parts = it.split(",")
            if (parts.size == 3) Triple(parts[0], parts[1], parts[2].toFloatOrNull() ?: 0f) else null
        }.groupBy { it.first }.mapValues { entry ->
            entry.value.sumOf { it.third.toDouble() }.toFloat()
        }

        val dates = daily.keys.sorted().takeLast(21)
        val input = FloatArray(21 * 17)

        for ((i, dateStr) in dates.withIndex()) {
            val date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_DATE)
            val amount = daily[dateStr] ?: 0f
            val dow = date.dayOfWeek.value % 7
            val isHoliday = if (dow == 0 || dow == 6) 1f else 0f
            val isWeekend = if (dow >= 5) 1f else 0f
            val isStart = if (date.dayOfMonth <= 5) 1f else 0f
            val isEnd = if (date.dayOfMonth >= 25) 1f else 0f
            val daysFromPayday = (date.dayOfMonth - 25).toFloat()

            val offset = i * 17
            input[offset + 0] = amount
            input[offset + 1] = dow.toFloat()
            input[offset + 2] = isHoliday
            input[offset + 3] = isWeekend
            input[offset + 4] = isStart
            input[offset + 5] = isEnd
            input[offset + 6] = daysFromPayday
        }

        return FloatBuffer.wrap(input)
    }

    fun runRatioInference(context: Context, input: FloatBuffer): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open("model_ratio_U001.onnx").readBytes()
        val session = env.createSession(modelBytes, OrtSession.SessionOptions())

        val shape = longArrayOf(1, 21, 29)
        val inputTensor = OnnxTensor.createTensor(env, input, shape)
        val result = session.run(mapOf("input" to inputTensor))
        val output = result[0].value as Array<FloatArray>

        return output[0]
    }

    fun runTotalInference(context: Context, input: FloatBuffer): Float {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open("model_total_U001.onnx").readBytes()
        val session = env.createSession(modelBytes, OrtSession.SessionOptions())

        val shape = longArrayOf(1, 21, 17)
        val inputTensor = OnnxTensor.createTensor(env, input, shape)
        val result = session.run(mapOf("input" to inputTensor))
        val output = result[0].value as Array<FloatArray>

        return output[0][0]
    }
}

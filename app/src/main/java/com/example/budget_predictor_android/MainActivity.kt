package com.example.budget_predictor_android

import kotlin.math.abs
import kotlin.math.sqrt
import ai.onnxruntime.*
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.nio.FloatBuffer
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields

import io.grpc.ManagedChannelBuilder
import spendingapi.FederatedClientGrpc
import spendingapi.Spending
import java.time.LocalDate

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        //ファイルのどこを見ればいいの書いてほしい！
        val predictMonthButton = findViewById<Button>(R.id.predictMonthButton)
        val predictWeekButton = findViewById<Button>(R.id.predictWeekButton)
        val rebalanceButton = findViewById<Button>(R.id.rebalanceButton)
        val dailyLimitButton = findViewById<Button>(R.id.dailyLimitButton)
        val alertButton = findViewById<Button>(R.id.alertButton)
        val outputTextView = findViewById<TextView>(R.id.outputTextView)
        val downloadTotalModelButton = findViewById<Button>(R.id.downloadTotalModelButton)
        downloadTotalModelButton.setOnClickListener {
            fetchAndSaveModel(this, "total")  // "ratio"もOK
        }
        val downloadRatioModelButton = findViewById<Button>(R.id.downloadRatioModelButton)
        downloadRatioModelButton.setOnClickListener {
            fetchAndSaveModel(this, "ratio")  // "ratio"もOK
        }

        val categoryNames = listOf("食費", "交通", "娯楽", "衣類・美容・日用品", "光熱費", "交際費", "その他")

        //リスク評価を行う関数
        fun getRisk(pred: Float, budget: Float): String {
            return when {
                pred > budget * 1.2 -> "🔴 高リスク"
                pred > budget -> "🟠 中リスク"
                else -> "🟢 低リスク"
            }
        }

        //リバランス提案を行う関数
        fun getRebalanceSuggestion(predicted: Map<String, Float>, budget: Map<String, Float>): String {
            val excess = mutableMapOf<String, Float>()
            val deficit = mutableMapOf<String, Float>()

            for ((name, pred) in predicted) {
                val limit = budget[name] ?: 0f
                val diff = pred - limit
                if (diff > 0) deficit[name] = diff
                else excess[name] = -diff
            }

            val totalExcess = excess.values.sum()
            val totalDeficit = deficit.values.sum()
            if (totalDeficit == 0f) return "✅ すべてのカテゴリが予算内です"

            val suggestions = mutableListOf<String>()
            for ((cat, amt) in deficit) {
                val share = if (totalExcess > 0) amt / totalDeficit else 0f
                val covered = share * totalExcess
                suggestions.add("$cat に %.0f 円 補填提案".format(covered))
            }
            return suggestions.joinToString("\n")
        }

        //日割り計算を行う関数
        fun getDailyLimit(budget: Float): String {
            val today = LocalDate.now()
            val endOfMonth = YearMonth.now().atEndOfMonth()
            val remainingDays = ChronoUnit.DAYS.between(today, endOfMonth).toInt().coerceAtLeast(1)
            val dailyLimit = budget / remainingDays
            return "📆 月末まで残り $remainingDays 日\n日割り支出上限: %.0f 円/日".format(dailyLimit)
        }

        //csvからデータを読み込む関数
        fun loadLastMonthBudget(context: Context, categoryNames: List<String>): Map<String, Float> {
            val file = File(context.filesDir, "spending.csv")
            if (!file.exists()) return categoryNames.associateWith { 0f }

            //カテゴリを日本語にするためのマッピング
            val categoryMap = mapOf(
                "food" to "食費",
                "transport" to "交通",
                "entertainment" to "娯楽",
                "clothing_beauty_daily" to "衣類・美容・日用品",
                "utilities" to "光熱費",
                "social" to "交際費",
                "other" to "その他"
            )

            val lines = file.readLines()
            val lastMonth = YearMonth.now().minusMonths(1)

            val filtered = lines.mapNotNull {
                val parts = it.split(",")
                if (parts.size >= 4) {
                    val date = try {
                        OffsetDateTime.parse(parts[0]).toLocalDate()
                    } catch (e: Exception) {
                        null
                    }
                    val rawCategory = parts[2]
                    val category = categoryMap[rawCategory] ?: return@mapNotNull null
                    val amount = parts.last().toFloatOrNull() ?: 0f
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

        //月末予測用ボタンの設定
        predictMonthButton.setOnClickListener {
            try {
                val inputRatio = generateScaledInputFromCsv(this)
                val inputTotal = generateTotalInputFromCsv(this)

                val totalAmountMonth = predictDays(this, inputTotal, 30)
                val ratioArray = runRatioInference(this, inputRatio)

                val userBudget = loadLastMonthBudget(this, categoryNames)
                val categoryDetails = mutableListOf<String>()
                val riskSummaries = mutableListOf<String>()
                val predicted = mutableMapOf<String, Float>()

                ratioArray.mapIndexed { i, r ->
                    val yen = r * totalAmountMonth
                    val name = categoryNames.getOrElse(i) { "カテゴリ${i + 1}" }
                    val budget = userBudget[name] ?: 0f
                    val risk = getRisk(yen, budget)
                    categoryDetails.add("$name: %.0f 円".format(yen))
                    riskSummaries.add("$name（予算: %.0f 円）→ %s".format(budget, risk))
                    predicted[name] = yen
                }

                val rebalanceText = getRebalanceSuggestion(predicted, userBudget)

                outputTextView.text = "\uD83D\uDCC5 月末までの予測支出合計: %.0f 円\n\n%s\n\n\u26A0\uFE0F リスク評価\n%s\n\n\uD83D\uDD04 リバランス提案\n%s".format(
                    totalAmountMonth,
                    categoryDetails.joinToString("\n"),
                    riskSummaries.joinToString("\n"),
                    rebalanceText
                )

            } catch (e: Exception) {
                outputTextView.text = "⚠️ 月末予測エラー: ${e.message}"
                Log.e("DEBUG", "月末予測失敗", e)
            }
        }

        //7日後予測用ボタンの設定
        predictWeekButton.setOnClickListener {
            try {
                val inputRatio = generateScaledInputFromCsv(this)
                val inputTotal = generateTotalInputFromCsv(this)

                val totalAmountWeek = predictDays(this, inputTotal, 7)
                val ratioArray = runRatioInference(this, inputRatio)

                val userBudget = loadLastMonthBudget(this, categoryNames)
                val categoryDetails = mutableListOf<String>()
                val riskSummaries = mutableListOf<String>()
                val predicted = mutableMapOf<String, Float>()

                ratioArray.mapIndexed { i, r ->
                    val yen = r * totalAmountWeek
                    val name = categoryNames.getOrElse(i) { "カテゴリ${i + 1}" }
                    val budget = userBudget[name] ?: 0f
                    val risk = getRisk(yen, budget)
                    categoryDetails.add("$name: %.0f 円".format(yen))
                    riskSummaries.add("$name（予算: %.0f 円）→ %s".format(budget, risk))
                    predicted[name] = yen
                }

                val rebalanceText = getRebalanceSuggestion(predicted, userBudget)

                outputTextView.text = "\uD83D\uDD52 7日後までの予測支出合計: %.0f 円\n\n%s\n\n\u26A0\uFE0F リスク評価\n%s\n\n\uD83D\uDD04 リバランス提案\n%s".format(
                    totalAmountWeek,
                    categoryDetails.joinToString("\n"),
                    riskSummaries.joinToString("\n"),
                    rebalanceText
                )

            } catch (e: Exception) {
                outputTextView.text = "⚠️ 7日予測エラー: ${e.message}"
                Log.e("DEBUG", "7日予測失敗", e)
            }
        }

        //リバランス提案用ボタンの設定
        rebalanceButton.setOnClickListener {
            try {
                val inputRatio = generateScaledInputFromCsv(this)
                val inputTotal = generateTotalInputFromCsv(this)
                val totalWeek = predictDays(this, inputTotal, 7)
                val totalMonth = predictDays(this, inputTotal, 30)
                val ratio = runRatioInference(this, inputRatio)

                val categoryNames = listOf("食費", "交通", "娯楽", "衣類・美容・日用品", "光熱費", "交際費", "その他")
                val userBudget = loadLastMonthBudget(this, categoryNames)

                val weekMap = categoryNames.mapIndexed { i, name -> name to (ratio[i] * totalWeek) }.toMap()
                val monthMap = categoryNames.mapIndexed { i, name -> name to (ratio[i] * totalMonth) }.toMap()

                val weekText = getRebalanceSuggestion(weekMap, userBudget)
                val monthText = getRebalanceSuggestion(monthMap, userBudget)

                outputTextView.text = "🔄 リバランス提案\n\n🕒 7日後までの予測に基づく提案：\n$weekText\n\n📅 月末までの予測に基づく提案：\n$monthText"

            } catch (e: Exception) {
                outputTextView.text = "⚠️ リバランス提案エラー: ${e.message}"
            }
        }

        //日割り計算用ボタンの設定
        dailyLimitButton.setOnClickListener {
            try {
                val userBudget = loadLastMonthBudget(this, categoryNames)
                val totalBudget = userBudget["total_budget"] ?: 0f
                val dailyLimit = getDailyLimit(totalBudget)
                outputTextView.text = dailyLimit
            } catch (e: Exception) {
                outputTextView.text = "⚠️ 日割り上限エラー: ${e.message}"
            }
        }

        //支出超過警告用ボタンの設定
        alertButton.setOnClickListener {
            try {
                val userBudget = loadLastMonthBudget(this, categoryNames)
                val remainingDays = ChronoUnit.DAYS.between(LocalDate.now(), YearMonth.now().atEndOfMonth()).toInt().coerceAtLeast(1)
                val dailyLimits = userBudget.mapValues { it.value / remainingDays }

                val categoryMap = mapOf(
                    "food" to "食費",
                    "transport" to "交通",
                    "entertainment" to "娯楽",
                    "clothing_beauty_daily" to "衣類・美容・日用品",
                    "utilities" to "光熱費",
                    "social" to "交際費",
                    "other" to "その他"
                )

                val file = File(this.filesDir, "spending.csv")
                if (!file.exists()) throw Exception("spending.csv が存在しません")

                val lines = file.readLines()
                val today = LocalDate.now()
                val todaySpending = lines.mapNotNull {
                    val parts = it.split(",")
                    if (parts.size >= 4) {
                        val date = try {
                            OffsetDateTime.parse(parts[0]).toLocalDate()
                        } catch (e: Exception) {
                            null
                        }
                        val rawCategory = parts[2]
                        val category = categoryMap[rawCategory] ?: return@mapNotNull null
                        val amount = parts.last().toFloatOrNull() ?: 0f
                        if (date == today) Pair(category, amount) else null
                    } else null
                }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }

                Log.d("DEBUG", "todaySpending: $todaySpending")
                Log.d("DEBUG", "dailyLimits: $dailyLimits")

                val alerts = todaySpending.mapNotNull { (cat, spent) ->
                    val limit = dailyLimits[cat] ?: 0f
                    when {
                        limit == 0f -> "$cat：📎 先月の支出なし → 日割り上限なし (使用: ${"%.0f".format(spent)} 円)"
                        spent > limit -> "$cat：${"%.0f".format(spent)} 円 ➡️ ⚠️ 上限 ${"%.0f".format(limit)} 円超過"
                        else -> "$cat：${"%.0f".format(spent)} 円 / 上限 ${"%.0f".format(limit)} 円"
                    }
                }

                outputTextView.text = if (alerts.isEmpty()) "✅ 本日の支出は記録されていません" else "📊 本日のカテゴリ別支出状況\n\n" + alerts.joinToString("\n")
            } catch (e: Exception) {
                outputTextView.text = "⚠️ アラートエラー: ${e.message}"
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

    fun fetchAndSaveModel(context: Context, modelType: String = "total") {
        val channel = ManagedChannelBuilder.forAddress("10.0.2.2", 50051)
            .usePlaintext()
            .build()
        val stub = FederatedClientGrpc.newBlockingStub(channel)

        Log.d("gRPC", "🛰️ Starting model download for: $modelType")

        try {
            val versionResponse = stub.checkModelVersion(
                Spending.VersionRequest.newBuilder().setClientId("U001").build()
            )
            val downloadRequest = Spending.ModelRequest.newBuilder()
                .setModelType(modelType)
                .build()
            val modelResponse = stub.downloadModel(downloadRequest)
            val modelBytes = modelResponse.modelData.toByteArray()
            Log.d("gRPC", "📦 Model bytes received: ${modelBytes.size}")

            // 保存先ファイル名
            val fileName = if (modelType == "total") "model_total_U001.onnx" else "model_ratio_U001.onnx"
            val file = File(context.filesDir, fileName)
            file.writeBytes(modelBytes)

            Log.d("gRPC", "✅ Model ($modelType) saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("gRPC", "❌ Download failed: ${e.message}")
        } finally {
            channel.shutdown()
        }
    }
}

package com.example.budget_predictor_android

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class RecordActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val categorySpinner = findViewById<Spinner>(R.id.categorySpinner)
        val amountEditText = findViewById<EditText>(R.id.amountEditText)
        val saveButton = findViewById<Button>(R.id.saveButton)


        saveButton.setOnClickListener {
            val category = categorySpinner.selectedItem.toString()
            val amount = amountEditText.text.toString().toFloatOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(this, "金額を正しく入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
            val entry = "$today,$category,$amount\n"
            val file = File(filesDir, "spending.csv")
            file.appendText(entry)

            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
            amountEditText.text.clear()
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        finish()  // ← 戻る動作を処理
        return true
    }
}

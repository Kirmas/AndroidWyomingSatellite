package com.wyoming.satellite

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import ai.onnxruntime.OrtEnvironment

class ConfigActivity : AppCompatActivity() {

    private lateinit var modelSpinner: Spinner
    private lateinit var addBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var deleteBtn: Button

    private val prefsName = "wyoming_settings"
    private val prefKey = "selected_model"

    // parallel lists: display names and internal ids (eg "assets:jarvis.onnx" or "assets:hey_nabu.onnx")
    private val displayList = mutableListOf<String>()
    private val idList = mutableListOf<String>()

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { handlePickedUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        modelSpinner = findViewById(R.id.model_spinner)
        addBtn = findViewById(R.id.btn_add_model)
        deleteBtn = findViewById(R.id.btn_delete_model)
        saveBtn = findViewById(R.id.btn_save_model)

        addBtn.setOnClickListener {
            // allow user to pick any file; we'll accept .onnx file names
            openDocument.launch(arrayOf("*/*"))
        }

        saveBtn.setOnClickListener {
            val pos = modelSpinner.selectedItemPosition
            if (pos >= 0 && pos < idList.size) {
                val id = idList[pos]
                getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(prefKey, id).apply()
                Toast.makeText(this, "Saved model: ${displayList[pos]}", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, "No model selected", Toast.LENGTH_SHORT).show()
            }
        }


        deleteBtn.setOnClickListener {
            // Gather user models only
            val userModels = idList.withIndex().filter { it.value.startsWith("user:") }
            if (userModels.isEmpty()) {
                deleteBtn.isEnabled = false
                Toast.makeText(this, "No user models to delete", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val userNames = userModels.map { displayList[it.index] }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete user model")
                .setItems(userNames) { _, which ->
                    val idx = userModels[which].index
                    val id = idList[idx]
                    val name = id.removePrefix("user:")
                    val userDir = File(filesDir, "models")
                    val f = File(userDir, name)
                    val ok = if (f.exists()) f.delete() else false
                    if (ok) {
                        refreshModelList()
                        Toast.makeText(this, "Deleted $name", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refreshModelList()
    }

    private fun refreshModelList(selectId: String? = null) {
        displayList.clear()
        idList.clear()

        // Load built-in models from assets/models/wakeword
        try {
            val wakewordModels = assets.list("models/wakeword") ?: arrayOf()
            for (name in wakewordModels) {
                if (name.endsWith(".onnx", true)) {
                    displayList.add(name + " (built-in)")
                    idList.add("assets:$name")
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        // Load user models from app filesDir/models
        val userDir = File(filesDir, "models")
        if (userDir.exists()) {
            userDir.listFiles()?.filter { it.isFile && it.name.endsWith(".onnx", true) }?.forEach { f ->
                displayList.add(f.name + " (user)")
                idList.add("user:${f.name}")
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter

        // Preselect saved model if present, or optionally select passed selectId
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val saved = selectId ?: prefs.getString(prefKey, null)
        if (saved != null) {
            val idx = idList.indexOf(saved)
            if (idx >= 0) modelSpinner.setSelection(idx)
        }

        // Enable/disable delete button depending on user models
        val hasUserModels = idList.any { it.startsWith("user:") }
        deleteBtn.isEnabled = hasUserModels
    }

    private fun handlePickedUri(uri: Uri) {
        // get file name
        val name = queryName(contentResolver, uri) ?: uri.lastPathSegment ?: "model.onnx"
        if (!name.endsWith(".onnx", true)) {
            Toast.makeText(this, "Please pick an ONNX (.onnx) file", Toast.LENGTH_LONG).show()
            return
        }

        val userDir = File(filesDir, "models")
        if (!userDir.exists()) userDir.mkdirs()

        val dest = File(userDir, name)
        val temp = File(cacheDir, "tmp_model.onnx")
        try {
            // Copy to temp file first
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw Exception("Cannot open input stream")
                FileOutputStream(temp).use { out ->
                    input.copyTo(out)
                }
            }
            // Validate temp file
            val err = verifyUserModel(temp)
            if (err != null) {
                temp.delete()
                Toast.makeText(this, "Model invalid: $err", Toast.LENGTH_LONG).show()
            } else {
                // Move to models dir
                if (dest.exists()) dest.delete()
                if (temp.renameTo(dest)) {
                    Toast.makeText(this, "Copied to user models: ${dest.name}", Toast.LENGTH_SHORT).show()
                    refreshModelList("user:${dest.name}")
                } else {
                    temp.delete()
                    Toast.makeText(this, "Failed to move model to user dir", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            temp.delete()
            Toast.makeText(this, "Failed to copy model: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /* Verify that a user-supplied ONNX model file can be loaded by ONNX Runtime.
    * Returns null on success, or an error message on failure.
    */
    private fun verifyUserModel(file: File): String? {
        if (!file.exists()) return "Model file not found"
        return try {
            val env = OrtEnvironment.getEnvironment()
            try {
                file.inputStream().use { fis ->
                    val bytes = fis.readBytes()
                    env.createSession(bytes).use { /* success */ }
                }
                null // success
            } catch (e: Exception) {
                "Model failed to load: ${e.message}"
            } finally {
                try { env.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            "ONNX environment init failed: ${e.message}"
        }
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? {
        var result: String? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                result = cursor.getString(nameIndex)
            }
        }
        return result
    }
}

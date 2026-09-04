package com.vltv.play

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.ProfileEntity
import com.vltv.play.databinding.ActivityEditProfileBinding
import com.vltv.play.ui.AvatarSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var currentProfileId: Int = -1

    // ✅ Agora salva o ID do drawable (ex: "av_iron_man") em vez de URL
    private var selectedAvatarId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentProfileId = intent.getIntExtra("PROFILE_ID", -1)

        loadProfileData()

        binding.btnSaveProfile.setOnClickListener { saveChanges() }
        binding.btnCancelEdit.setOnClickListener { finish() }
        binding.avatarFrame.setOnClickListener { openAvatarPicker() }
        binding.btnDeleteProfile.setOnClickListener { confirmDeletion() }
    }

    private fun loadProfileData() {
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) {
                db.streamDao().getAllProfiles().find { it.id == currentProfileId }
            }
            profile?.let {
                binding.etEditName.setText(it.name)
                selectedAvatarId = it.imageUrl
                // ✅ Carrega drawable local pelo nome — instantâneo, sem rede
                exibirAvatar(it.imageUrl)
            }
        }
    }

    private fun openAvatarPicker() {
        // ✅ Construtor novo: sem apiKey, recebe drawableId em vez de URL
        val dialog = AvatarSelectionDialog(this) { drawableId ->
            selectedAvatarId = drawableId
            exibirAvatar(drawableId)
        }
        dialog.show()
    }

    // ✅ Resolve "av_iron_man" → R.drawable.av_iron_man e exibe sem Glide
    private fun exibirAvatar(drawableId: String?) {
        val drawable = if (!drawableId.isNullOrEmpty()) {
            val resId = resources.getIdentifier(drawableId, "drawable", packageName)
            if (resId != 0) ContextCompat.getDrawable(this, resId)
            else ContextCompat.getDrawable(this, R.drawable.ic_profile_placeholder)
        } else {
            ContextCompat.getDrawable(this, R.drawable.ic_profile_placeholder)
        }
        binding.ivEditAvatar.setImageDrawable(drawable)
    }

    private fun saveChanges() {
        val newName = binding.etEditName.text.toString()
        if (newName.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            // ✅ imageUrl agora guarda o ID do drawable (ex: "av_iron_man")
            val profile = ProfileEntity(
                id = currentProfileId,
                name = newName,
                imageUrl = selectedAvatarId
            )
            db.streamDao().updateProfile(profile)
            withContext(Dispatchers.Main) { finish() }
        }
    }

    private fun confirmDeletion() {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Perfil?")
            .setMessage("Tem certeza que deseja apagar este perfil?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val profile = ProfileEntity(id = currentProfileId, name = "", imageUrl = null)
                    db.streamDao().deleteProfile(profile)
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

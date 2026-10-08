/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.voice.VoiceModel
import com.osfans.trime.data.voice.VoiceModelManager
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().voice) {
    private val prefs = AppPrefs.defaultInstance().voice

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val appContext = requireContext().applicationContext
            lifecycleScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching { VoiceModelManager.importFromTree(appContext, uri) }
                    }
                result
                    .onSuccess { model ->
                        prefs.modelId.setValue(model.id)
                        requireContext().toast(getString(R.string.voice_model_import_done, model.name))
                        rebuild()
                    }
                    .onFailure {
                        requireContext().toast(
                            getString(R.string.voice_model_import_failed, it.message.orEmpty()),
                        )
                    }
            }
        }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val models = VoiceModelManager.listModels()
        val selected = models.firstOrNull { it.id == prefs.modelId.getValue() }

        screen.addCategory(getString(R.string.voice_model)) {
            addPreference(createModelListPreference(models, selected))
            addPreference(
                R.string.voice_model_import,
                R.string.voice_model_import_summary,
            ) {
                importLauncher.launch(null)
            }
            if (selected != null) {
                addPreference(R.string.voice_model_delete) { confirmDelete(selected.name) }
            }
        }

        // Also surface the long-press-space trigger on this page, next to the
        // model settings, since it is what opens the local recogniser. Reuse
        // the widget the general settings page declares for the same key.
        val triggerUi =
            AppPrefs.defaultInstance().general.preferenceDelegatesUi
                .firstOrNull { it.key == AppPrefs.General.VOICE_INPUT_MODE }
        if (triggerUi != null) {
            screen.addPreference(triggerUi.createUi(requireContext()))
        }
    }

    private fun createModelListPreference(
        models: List<VoiceModel>,
        selected: VoiceModel?,
    ): ListPreference = ListPreference(requireContext()).apply {
        key = AppPrefs.Voice.MODEL_ID
        setTitle(R.string.voice_model)
        isIconSpaceReserved = false
        isSingleLineTitle = false
        entries =
            if (models.isEmpty()) {
                arrayOf(getString(R.string.voice_model__none))
            } else {
                models.map { it.name }.toTypedArray()
            }
        entryValues =
            if (models.isEmpty()) {
                arrayOf("")
            } else {
                models.map { it.id }.toTypedArray()
            }
        value = prefs.modelId.getValue()
        summary = selected?.name ?: getString(R.string.voice_model__none)
        isEnabled = models.isNotEmpty()
    }

    private fun confirmDelete(name: String) {
        AlertDialog
            .Builder(requireContext())
            .setMessage(getString(R.string.voice_model_delete_confirm, name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                VoiceModelManager.findModel(prefs.modelId.getValue())?.let {
                    VoiceModelManager.deleteModel(it)
                }
                prefs.modelId.setValue("")
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rebuild() {
        onCreatePreferences(Bundle.EMPTY, null)
    }
}

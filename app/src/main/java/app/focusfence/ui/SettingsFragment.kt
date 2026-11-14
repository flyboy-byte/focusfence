package app.focusfence.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.focusfence.DiagnosticsActivity
import app.focusfence.HelpActivity
import app.focusfence.R
import app.focusfence.UiUtil
import app.focusfence.data.AdListStore
import app.focusfence.data.BlockedAppsStore
import app.focusfence.data.BlocklistStore
import app.focusfence.data.ExcludedAppsStore
import app.focusfence.databinding.FragmentSettingsBinding
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.adSwitch.isChecked = AdListStore.isEnabled(requireContext())
        binding.adSwitch.setOnCheckedChangeListener { _, checked ->
            val context = requireContext()
            AdListStore.setEnabled(context, checked)
            if (checked && !AdListStore.file(context).exists()) {
                updateAdList()
            } else {
                refreshAdStatus()
            }
        }
        binding.adUpdateButton.setOnClickListener { updateAdList() }

        binding.excludedAppsButton.setOnClickListener {
            UiUtil.chooseExcludedApps(requireContext()) { refreshAll() }
        }
        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.diagnosticsButton.setOnClickListener {
            startActivity(Intent(requireContext(), DiagnosticsActivity::class.java))
        }
        binding.guideButton.setOnClickListener {
            startActivity(Intent(requireContext(), HelpActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refreshAll() {
        refreshAdStatus()
        refreshAccessibility()
        val b = _binding ?: return
        b.excludedAppsButton.text = getString(
            R.string.excluded_apps_button,
            ExcludedAppsStore.get(requireContext()).size,
        )
    }

    private fun refreshAccessibility() {
        val b = _binding ?: return
        val context = requireContext()
        val configured = BlocklistStore.rules(context).any { it.hasPathExceptions } ||
            BlockedAppsStore.get(context).isNotEmpty()
        val enabled = UiUtil.isUrlFilterEnabled(context)
        b.accessibilityStatusText.text = when {
            enabled -> getString(R.string.section_enabled)
            configured -> getString(R.string.section_disabled)
            else -> getString(R.string.section_idle)
        }
    }

    private fun updateAdList() {
        val b = _binding ?: return
        b.adUpdateButton.isEnabled = false
        b.adStatusText.text = getString(R.string.adlist_downloading)
        val appContext = requireContext().applicationContext
        Thread {
            val error = try {
                AdListStore.download(appContext)
                null
            } catch (e: IOException) {
                e.message ?: "error"
            }
            _binding?.root?.post {
                val binding = _binding ?: return@post
                binding.adUpdateButton.isEnabled = true
                if (error != null && isAdded) {
                    Toast.makeText(requireContext(), R.string.adlist_error, Toast.LENGTH_LONG)
                        .show()
                }
                refreshAdStatus()
            }
        }.start()
    }

    private fun refreshAdStatus() {
        val b = _binding ?: return
        val context = requireContext()
        val count = AdListStore.entryCount(context)
        val updatedAt = AdListStore.updatedAt(context)
        b.adStatusText.text = if (count == 0 || updatedAt == 0L) {
            getString(R.string.adlist_none)
        } else {
            getString(
                R.string.adlist_status,
                NumberFormat.getIntegerInstance().format(count),
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(updatedAt)),
            )
        }
    }
}

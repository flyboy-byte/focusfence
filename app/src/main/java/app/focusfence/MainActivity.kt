package app.focusfence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.focusfence.data.BlocklistStore
import app.focusfence.databinding.ActivityMainBinding
import app.focusfence.ui.BlocklistFragment
import app.focusfence.ui.HomeFragment
import app.focusfence.ui.SettingsFragment
import app.focusfence.ui.StatsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var classic = false
    private var currentTab = R.id.nav_home

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Five tabs by default; Settings → Classic layout brings the old four back (rollback without a release).
        classic = app.focusfence.data.UiPrefs.classic(this)
        binding.bottomNav.menu.clear()
        binding.bottomNav.inflateMenu(if (classic) R.menu.menu_bottom_nav else R.menu.menu_bottom_nav_tabs)
        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_blocklist -> BlocklistFragment()
                R.id.nav_stats -> StatsFragment()
                R.id.nav_partners -> AccountabilityFragment.asTab()
                R.id.nav_monitoring -> MonitoringFragment.asTab()
                R.id.nav_settings -> SettingsFragment()
                else -> HomeFragment()
            }
            supportActionBar?.title = if (item.itemId == R.id.nav_home) getString(R.string.app_name) else item.title
            currentTab = item.itemId
            invalidateOptionsMenu()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit()
            true
        }
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!BlocklistStore.prefs(this).getBoolean(OnboardingActivity.KEY_ONBOARDED, false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // No Stats tab in the five-tab layout: Home's toolbar opens it instead.
        menu.findItem(R.id.action_stats)?.isVisible = !classic && currentTab == R.id.nav_home
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_stats) {
            startActivity(Intent(this, StatsActivity::class.java))
            return true
        }
        if (item.itemId == R.id.action_help) {
            startActivity(Intent(this, HelpActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

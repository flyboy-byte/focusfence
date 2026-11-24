package app.focusfence

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Opens [AccountabilityFragment] on its own (the classic layout, notifications and links); the five-tab layout shows it as a tab. */
class AccountabilityActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, AccountabilityFragment()).commit()
        }
    }
}

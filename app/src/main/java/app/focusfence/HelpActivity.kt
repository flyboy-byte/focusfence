package app.focusfence

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** The full guide, reachable anytime from the toolbar's ? action. */
class HelpActivity : AppCompatActivity() {

    // Help and setup are one guide now (redesign step 5); kept so old links and shortcuts still land somewhere.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(android.content.Intent(this, SetupActivity::class.java))
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

package app.novavpn.core.update

import androidx.core.content.FileProvider
import com.example.R

/**
 * Project-owned FileProvider subclass for sharing downloaded APK updates with the system installer.
 */
class NovaUpdateFileProvider : FileProvider(R.xml.update_paths)

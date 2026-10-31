package com.mydigitaldevil.musicplayer.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * What the user let us read. On Android 14+ video access can be partial ("Select videos"),
 * in which case MediaStore only returns the videos they picked.
 */
data class MediaAccess(val audio: Boolean, val video: Boolean, val videoPartial: Boolean) {
    val any: Boolean get() = audio || video || videoPartial
}

object MediaPermissions {

    /** Everything requested in one go from the library screen. */
    val requested: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): MediaAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val video = granted(Manifest.permission.READ_MEDIA_VIDEO)
                MediaAccess(
                    audio = granted(Manifest.permission.READ_MEDIA_AUDIO),
                    video = video,
                    videoPartial = !video && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
                )
            }
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE).let { MediaAccess(it, it, false) }
        }
    }
}

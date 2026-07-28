package com.projectmemento.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class PhotoAccessLevel {
    FULL,
    PARTIAL,
    NONE,
}

object PhotoAccess {
    fun current(context: Context): PhotoAccessLevel {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)) {
                return PhotoAccessLevel.FULL
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            ) {
                return PhotoAccessLevel.PARTIAL
            }
            return PhotoAccessLevel.NONE
        }

        return if (isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            PhotoAccessLevel.FULL
        } else {
            PhotoAccessLevel.NONE
        }
    }

    fun requestPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
        )

        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

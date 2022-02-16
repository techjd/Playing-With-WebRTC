package com.techjd.webrtcdemo

import android.Manifest
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.techjd.webrtcdemo.databinding.ActivityMainBinding
import com.techjd.webrtcdemo.databinding.ActivityStartBinding

class StartActivity : AppCompatActivity() {

    private var binding: ActivityStartBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartBinding.inflate(layoutInflater)
        val view = binding!!.root
        setContentView(view)

        binding!!.start.setOnClickListener {
            start()

        }

    }

    private fun start() {
        Dexter.withContext(this)
            .withPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    if (report.areAllPermissionsGranted()) {
                        Intent(this@StartActivity, MainActivity::class.java).apply {
                            startActivity(this)
                        }
                    } else {
                        if (report.isAnyPermissionPermanentlyDenied) {
                        }
                    }
                }

                override fun onPermissionRationaleShouldBeShown(
                    permissions: MutableList<PermissionRequest>?,
                    token: PermissionToken?
                ) {
                    token?.continuePermissionRequest()
                }
            }).check()
    }
}
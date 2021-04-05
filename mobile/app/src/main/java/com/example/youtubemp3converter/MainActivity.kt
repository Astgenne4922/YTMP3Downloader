package com.example.youtubemp3converter

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.core.widget.doAfterTextChanged
import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler
import com.github.hiteshsondhi88.libffmpeg.FFmpeg
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegNotSupportedException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.echeung.youtubeextractor.YTMetadata
import me.echeung.youtubeextractor.YouTubeExtractor
import java.io.File

class MainActivity : AppCompatActivity() {
    // Records for files and directories frequently used
    private val tempDir = File(Environment.getExternalStorageDirectory(), "/.YT_MP3_temp")
    private val thumbFile = File(tempDir, "/thumb.jpg")
    private val m4aFile = File(tempDir, "/audio.m4a")

    // Record
    private var thumbDownloadId: Long = -1L
    private var audioDownloadId: Long = -1L

    private var videoUrl: String = ""
    private var videoMeta: YTMetadata? = null

    private var ffmpeg: FFmpeg? = null

    /*
    * Called on application start
    */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.setContentView(R.layout.activity_main)

        // Check for storage permission
        this.isStoragePermissionGranted()
        // Create notification channel
        this.createNotificationChannel()

        // Load FFmpeg library, if not supported a toast shows up
        this.ffmpeg = FFmpeg.getInstance(this)
        try {
            this.ffmpeg!!.loadBinary(null)
        } catch (e: FFmpegNotSupportedException) {
            Toast.makeText(this, this.getString(R.string.toast_ffmpeg_not_supp), Toast.LENGTH_LONG)
                .show()
        }

        this.convertBtn.shrink()
        // Initialize event listeners
        this.initEventListeners()

        // Delete the temporary directory for precaution
        this.deleteDownloadTemp()

        // Register a receiver for the end of a download
        this.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id: Long = intent!!.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                val dm: DownloadManager =
                    this@MainActivity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val cursor: Cursor = dm.query(DownloadManager.Query().setFilterById(id))

                if (cursor.moveToFirst()) {
                    when (id) {
                        -1L -> {
                        }
                        /* if the thumbnail download ended */ this@MainActivity.thumbDownloadId -> {
                        when (cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))) {
                            /* if the download ended successfully */ DownloadManager.STATUS_SUCCESSFUL -> {
                            // Load the UI with the video thumbnail and metadata
                            this@MainActivity.loadDownloadUI()

                            // Send notification: Video Found
                            with(NotificationManagerCompat.from(this@MainActivity)) {
                                this.notify(
                                    0,
                                    this@MainActivity.createNotificationBuilder(
                                        this@MainActivity.getString(R.string.noti_title_video_found),
                                        null,
                                        false
                                    ).build()
                                )
                            }
                        }
                            /* if the download failed*/ DownloadManager.STATUS_FAILED -> {
                            // Re-enable the insert button
                            this@MainActivity.insertURLBtn.isEnabled = true

                            // Send notification: Video Not Found
                            with(NotificationManagerCompat.from(this@MainActivity)) {
                                this.notify(
                                    0,
                                    this@MainActivity.createNotificationBuilder(
                                        this@MainActivity.getString(R.string.noti_title_not_found),
                                        null,
                                        false
                                    ).build()
                                )
                            }
                        }
                        }
                    }
                        /* if the audio download ended */ this@MainActivity.audioDownloadId -> {
                        when (cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))) {
                            /* if the download ended successfully */ DownloadManager.STATUS_SUCCESSFUL -> {
                            // Get artist and title inputs
                            val artist: String =
                                this@MainActivity.artistTxt.editText?.text.toString().trim()
                            val title: String =
                                this@MainActivity.titleTxt.editText?.text.toString().trim()

                            // Generate a random string for the album (this is for preserving the unique cover art of the song)
                            val album: String = this@MainActivity.getRandomString()

                            // The path to the final file in the default Music directory in the format "Artist - Title.mp3"
                            var mp3 = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                                String.format("/%s - %s.mp3", artist, title)
                            )

                            var i = 0
                            while (mp3.exists()) {
                                mp3 = File(
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                                    String.format("/%s - %s ($i).mp3", artist, title)
                                )
                                i++
                            }

                            // The start position for the cut and the length
                            val startCut: Int =
                                this@MainActivity.cuttingSlider.values[0].toInt()
                            val cutLen: Int =
                                this@MainActivity.cuttingSlider.values[1].toInt() - startCut

                            // The complete command for FFmpeg
                            val convertCmd: Array<String> =
                                "-ss,${startCut},-t,${cutLen},-i,${this@MainActivity.m4aFile},-i,${this@MainActivity.thumbFile},-map,0:0,-map,1:0,-c,copy,-id3v2_version,3,-metadata,title=${title},-metadata,artist=${artist},-metadata,album=${album},-f,mp3,-c:v,copy,-c:a,libmp3lame,-q:a,4,${mp3}"
                                    .split(",").toTypedArray()

                            // Create notification with endless progress bar: Song Conversion - Converting
                            var builder = this@MainActivity.createNotificationBuilder(
                                this@MainActivity.getString(R.string.noti_title_song_conversion),
                                this@MainActivity.getString(R.string.noti_text_converting),
                                true
                            )

                            /*
                            * Start the FFmpeg conversion
                            * If FFmpeg ia already running an exception is raised.
                            * In this application since the only way to start FFmpeg is via the convert button
                            * the exception shouldn't be raised because upon click the button is disabled
                            * until either the conversion finished successfully or an error has occurred.
                            * If FFmpeg is somehow still executed again a toast shows up to catch the exception
                            */
                            try {
                                // Execute the FFmpeg command
                                this@MainActivity.ffmpeg!!.execute(
                                    convertCmd,
                                    object : ExecuteBinaryResponseHandler() {
                                        // Called at the start of the execution
                                        override fun onStart() {
                                            // Send notification with endless progress bar: Song Conversion - Converting
                                            with(NotificationManagerCompat.from(this@MainActivity)) {
                                                this.notify(0, builder.build())
                                            }
                                        }

                                        // Called if the execution was successful
                                        override fun onSuccess(message: String?) {
                                            // Set the description of the notification to: Conversion completed
                                            builder = this@MainActivity.createNotificationBuilder(
                                                this@MainActivity.getString(R.string.noti_title_song_conversion),
                                                this@MainActivity.getString(R.string.noti_text_conv_completed),
                                                false
                                            ).setStyle(
                                                NotificationCompat.BigTextStyle()
                                                    .bigText(getString(R.string.noti_text_file_saved) + mp3)
                                            )

                                            // Deletes the temporary directory and resets the UI
                                            this@MainActivity.deleteDownloadTemp()
                                            this@MainActivity.resetUI()
                                        }

                                        // Called if the execution failed
                                        override fun onFailure(message: String?) {
                                            // Re-enable the UI
                                            this@MainActivity.insertURLBtn.isEnabled = true
                                            this@MainActivity.downloadUIisEnabled(true)

                                            // If an output is produced it's probably corrupted
                                            if (mp3.exists()) mp3.delete()

                                            // Set the description of the notification to: Conversion completed
                                            builder = this@MainActivity.createNotificationBuilder(
                                                this@MainActivity.getString(R.string.noti_title_song_conversion),
                                                this@MainActivity.getString(R.string.noti_text_conv_fail),
                                                false
                                            )
                                        }

                                        // Called at the end of the execution, after onSuccess/onFailure
                                        override fun onFinish() {
                                            // Send notification: Song Conversion - Conversion completed/failed
                                            with(NotificationManagerCompat.from(this@MainActivity)) {
                                                this.notify(
                                                    0,
                                                    builder.setOngoing(false)
                                                        .setProgress(0, 0, false)
                                                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                                                        .setAutoCancel(true)
                                                        .build()
                                                )
                                            }
                                        }
                                    })
                            } catch (e: FFmpegCommandAlreadyRunningException) {
                                Toast.makeText(
                                    this@MainActivity,
                                    this@MainActivity.getString(R.string.toast_conv_run),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                            /* if the download failed*/ DownloadManager.STATUS_FAILED -> {
                            // Re-enable the UI
                            this@MainActivity.insertURLBtn.isEnabled = true
                            this@MainActivity.downloadUIisEnabled(true)

                            // Send notification: Couldn't Retrieve Audio
                            with(NotificationManagerCompat.from(this@MainActivity)) {
                                this.notify(
                                    0,
                                    this@MainActivity.createNotificationBuilder(
                                        this@MainActivity.getString(R.string.noti_title_not_retrieve),
                                        null,
                                        false
                                    ).build()
                                )
                            }
                        }
                        }
                    }
                    }
                }
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

        // Handles the intent from the share button of youtube videos and start searching for that video
        val url: String? = this.intent.extras?.getString(Intent.EXTRA_TEXT)
        if (url != null) this.searchVideo(url)
    }

    /*
    * Called on application close
    */
    override fun onDestroy() {
        super.onDestroy()

        // Delete the temporary directory for downloads
        this.deleteDownloadTemp()
    }

    /*
    * Sets the event listeners for the UI elements
    */
    private fun initEventListeners() {
        // Upon click an alert dialog with a text field shows up to get the YT url/video ID and start searching
        this.insertURLBtn.setOnClickListener {
            this.deleteDownloadTemp()
            val urlTxt = EditText(this)
            MaterialAlertDialogBuilder(this)
                .setTitle(this.getString(R.string.alert_insert_url))
                .setView(urlTxt)
                .setPositiveButton(R.string.ok) { _, _ ->
                    this.searchVideo(urlTxt.text.toString())
                }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()
        }
        // When either end of the slider changes the values in the text fields are updated
        this.cuttingSlider.addOnChangeListener { _, _, fromUser ->
            if (fromUser) {
                this.minCutTxt.editText?.setText(this.cuttingSlider.values[0].toInt().toString())
                this.maxCutTxt.editText?.setText(this.cuttingSlider.values[1].toInt().toString())
            }
        }
        // When the value changes the corresponding end of the slider is updated
        this.minCutTxt.editText?.doAfterTextChanged {
            if (it?.isNotBlank() == true && it.isDigitsOnly()) {
                val num: Float = it.toString().toFloat()

                if (num < this.cuttingSlider.values[1] && num >= 0)
                    this.cuttingSlider.setValues(num, this.cuttingSlider.values[1])
            }
        }
        // When the value changes the corresponding end of the slider is updated
        this.maxCutTxt.editText?.doAfterTextChanged {
            if (it?.isNotBlank() == true && it.isDigitsOnly()) {
                val num: Float = it.toString().toFloat()

                if (num > this.cuttingSlider.values[0] && num <= cuttingSlider.valueTo)
                    this.cuttingSlider.setValues(this.cuttingSlider.values[0], num)
            }
        }
        // Upon click, if the artist and the title are valid inputs, the download of the audio and the conversion start
        this.convertBtn.setOnClickListener {
            // Check if artist and title are valid inputs, if not a Toast shows up to alert the user
            if (!this.artistTxt.editText?.text.toString().trim()
                    .matches("^[0-9a-zA-Z\\s]+$".toRegex())
            ) {
                Toast.makeText(
                    this,
                    this.getString(R.string.toast_invalid_artist),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            if (!this.titleTxt.editText?.text.toString().trim()
                    .matches("^[0-9a-zA-Z\\s]+$".toRegex())
            ) {
                Toast.makeText(
                    this,
                    this.getString(R.string.toast_invalid_title),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            // Disable all UI elements while converting
            this.insertURLBtn.isEnabled = false
            this.downloadUIisEnabled(false)

            // Send notification with endless progress bar: Song Conversion - Retrieving Audio
            with(NotificationManagerCompat.from(this@MainActivity)) {
                this.notify(
                    0,
                    this@MainActivity.createNotificationBuilder(
                        this@MainActivity.getString(R.string.noti_title_song_conversion),
                        this@MainActivity.getString(R.string.noti_text_retrieve_audio),
                        true
                    ).build()
                )
            }

            // Start the download of the audio file
            this.audioDownloadId = this.downloadFromUrl(this.videoUrl, this.m4aFile)
        }
    }

    /*
    * Searches for the video data of the given youtube url/video ID and starts loading the UI upon finding the video
    */
    private fun searchVideo(url: String) {
        // While searching disable the input button until the research has ended
        this.insertURLBtn.isEnabled = false

        // Send notification with endless progress bar: Searching the video
        with(NotificationManagerCompat.from(this)) {
            this.notify(
                0,
                this@MainActivity.createNotificationBuilder(
                    this@MainActivity.getString(R.string.noti_title_start_search),
                    null,
                    true
                ).build()
            )
        }

        // Search the video in a coroutine
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Start searching for the video with the given url/video ID
                with(YouTubeExtractor(this@MainActivity).extract(url)) {
                    // Save the url for the audio file
                    this@MainActivity.videoUrl = this?.files?.get(140)?.url ?: ""

                    // Save the video metadata and start the download of the thumbnail
                    this?.metadata?.let {
                        this@MainActivity.videoMeta = it
                        this@MainActivity.thumbDownloadId =
                            this@MainActivity.downloadFromUrl(
                                it.maxResImageUrl,
                                this@MainActivity.thumbFile
                            )
                    }
                }
            } /* If the video is not found an exception is raised */ catch (e: IllegalStateException) {
                withContext(Dispatchers.Main) {
                    this@MainActivity.insertURLBtn.isEnabled = true
                }

                // Send notification: Video Not Found
                with(NotificationManagerCompat.from(this@MainActivity)) {
                    this.notify(
                        0,
                        this@MainActivity.createNotificationBuilder(
                            this@MainActivity.getString(R.string.noti_title_not_found),
                            null,
                            false
                        ).build()
                    )
                }
            }
        }
    }

    /*
    * Creates a NotificationBuilder for a notification that can have an endless progress bar.
    * If text is null then no description will be displayed
    */
    private fun createNotificationBuilder(
        title: String,
        text: String?,
        inProgress: Boolean
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, "YT_MP3_Converter_Notification_Channel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setOngoing(inProgress)
            .setProgress(0, 0, inProgress)
            .apply {
                if (!inProgress)
                    this.setDefaults(NotificationCompat.DEFAULT_ALL)
                else
                    this.setNotificationSilent()

                if (text != null) this.setContentText(text)
            }
    }

    /*
    * Loads all the UI element related to the selected video
    */
    private fun loadDownloadUI() {
        this.thumbnail.setImageURI(Uri.fromFile(this.thumbFile))

        this.artistTxt.editText?.setText(this.videoMeta!!.author)
        this.titleTxt.editText?.setText(this.videoMeta!!.title)
        this.cuttingSlider.valueTo = this.videoMeta!!.duration.toFloat()
        this.cuttingSlider.values = listOf(0F, this.videoMeta!!.duration.toFloat())
        this.minCutTxt.editText?.setText("0")
        this.maxCutTxt.editText?.setText(this.videoMeta!!.duration.toString())

        this.insertURLBtn.isEnabled = true
        this.downloadUIisEnabled(true)
    }

    /*
    * Disables and empties all the UI elements except for the insertURL Button
    */
    private fun resetUI() {
        this.thumbnail.setImageResource(android.R.drawable.ic_menu_report_image)
        this.insertURLBtn.isEnabled = true
        this.downloadUIisEnabled(false)
        this.artistTxt.editText?.setText("")
        this.titleTxt.editText?.setText("")
        this.cuttingSlider.values = listOf(0f, cuttingSlider.valueTo)
        this.minCutTxt.editText?.setText("")
        this.maxCutTxt.editText?.setText("")
    }

    /*
    * Enables/disables the UI elements related to the selected video
    */
    private fun downloadUIisEnabled(enabled: Boolean) {
        this.convertBtn.isEnabled = enabled
        if (enabled) this.convertBtn.extend() else this.convertBtn.shrink()
        this.artistTxt.isEnabled = enabled
        this.titleTxt.isEnabled = enabled
        this.cuttingSlider.isEnabled = enabled
        this.minCutTxt.isEnabled = enabled
        this.maxCutTxt.isEnabled = enabled
    }

    /*
    * Start the download from the url, with no notification, in the standard Download directory
    * PathToFile is the path to the downloaded file relative to the Download directory (the file name and extension must be specified)
    */
    private fun downloadFromUrl(url: String, pathToFile: File): Long {
        if (pathToFile.exists() && pathToFile.isFile) pathToFile.delete()

        val request: DownloadManager.Request = DownloadManager.Request(Uri.parse(url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setDestinationUri(pathToFile.toUri())
        //.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, pathToFile)

        val manager: DownloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    /*
    * Check if storage permission is granted else the user is prompted to grant the permission
    */
    private fun isStoragePermissionGranted() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1
            )
        }
    }

    /*
    * Creates a notification channel
    */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel: NotificationChannel = NotificationChannel(
                "YT_MP3_Converter_Notification_Channel",
                "YT MP3 Converter Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                this.description = "YT MP3 Converter Notification Channel"
            }

            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /*
    * Deletes the temporary directory for the downloaded files
    */
    private fun deleteDownloadTemp() {
        if (this.tempDir.exists()) this.tempDir.deleteRecursively()
    }

    /*
    * Generate a random alphanumeric string with 12 characters
    */
    private fun getRandomString(): String {
        val all = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..12).map { all.random() }.joinToString("")
    }
}
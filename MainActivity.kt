package com.example.canalesdetv

import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.*
import org.videolan.libvlc.util.VLCVideoLayout
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.net.URL
import kotlin.concurrent.thread
import java.io.File
import androidx.core.content.ContextCompat
import java.io.IOException // Asegúrate de que estas dos importaciones estén presentes
import org.xmlpull.v1.XmlPullParserException // para el manejo de errores en loadChannels

class MainActivity : AppCompatActivity() {

    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var listView: ListView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var videoPlaceholder: View

    // === INICIALIZACIÓN DE LOS OVERLAYS ===
    private lateinit var pauseOverlay: TextView // Este es el TextView para "Pausado"
    private lateinit var timeOverlay: TextView  // Este es el TextView para "Tiempo"

    private val overlayHandler = Handler() // Handler para gestionar la visibilidad de los overlays temporales

    // Runnables específicos para cada overlay que se oculta temporalmente
    private var timeOverlayRunnable: Runnable? = null
    private var playingOverlayRunnable: Runnable? = null // Nuevo Runnable para el mensaje "Reproduciendo" del pauseOverlay


    private val xspfUrl =
        "https://github.com/patitogit/mariavision/raw/refs/heads/main/Peliculas.xspf"

    private var channels = mutableListOf<Channel>()
    private var currentChannelIndex = -1
    private var listVisible = true

    private var autoHideHandler = Handler()
    private var autoHideRunnable: Runnable? = null
    private var autoHideStarted = false

    private val prefs by lazy { getSharedPreferences("tv_app_times", MODE_PRIVATE) }

    private var continueDialog: AlertDialog? = null

    private var lastPlayedUrl: String? = null

    data class Channel(val title: String, val url: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoLayout = findViewById(R.id.video_surface)
        listView = findViewById(R.id.channel_list)
        loadingIndicator = findViewById(R.id.loading_indicator)
        videoPlaceholder = findViewById(R.id.video_placeholder)

        // === INICIALIZACIÓN CORRECTA DE LOS OVERLAYS DESDE XML ===
        pauseOverlay = findViewById(R.id.pause_overlay) // Asegúrate de que este ID existe en tu XML
        timeOverlay = findViewById(R.id.time_overlay)   // Asegúrate de que este ID existe en tu XML

        // Ambos overlays deben empezar ocultos
        pauseOverlay.visibility = View.GONE
        timeOverlay.visibility = View.GONE

        // --- ELIMINAR ESTE BLOQUE ---
        // Era el código para el overlayTextView programático que ya no necesitamos.
        /*
        overlayTextView = TextView(this).apply {
            setBackgroundResource(android.R.color.black)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            textSize = 24f
            gravity = Gravity.CENTER
            alpha = 0.7f
            visibility = View.GONE
            setPadding(30, 15, 30, 15)
        }
        (videoLayout.parent as ViewGroup).addView(
            overlayTextView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        */
        // --- FIN DEL BLOQUE A ELIMINAR ---


        libVLC = LibVLC(this, arrayListOf("--no-video-title-show"))
        mediaPlayer = MediaPlayer(libVLC)
        mediaPlayer.attachViews(videoLayout, null, false, false)

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EncounteredError -> runOnUiThread {
                    Toast.makeText(this, "Error al reproducir el canal", Toast.LENGTH_SHORT).show()
                    loadingIndicator.visibility = View.GONE
                    videoPlaceholder.visibility = View.GONE
                    mediaPlayer.stop()
                    showChannelList()
                    lastPlayedUrl = null
                }
                MediaPlayer.Event.Playing -> runOnUiThread {
                    loadingIndicator.visibility = View.GONE
                    videoPlaceholder.visibility = View.GONE
                    videoLayout.visibility = View.VISIBLE
                    // Ocultar cualquier mensaje de "Pausado" cuando la reproducción empieza
                    pauseOverlay.visibility = View.GONE
                    timeOverlay.visibility = View.GONE // También ocultar el de tiempo si estaba visible
                    // Asegurarse de cancelar cualquier temporizador que pudiera estar activo
                    timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
                    playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
                }
                MediaPlayer.Event.EndReached -> runOnUiThread {
                    Log.d("MainActivity", "Reproducción finalizada para URL: $lastPlayedUrl")

                    val currentUrl = if (currentChannelIndex in channels.indices) channels[currentChannelIndex].url else null
                    if (currentUrl != null && currentUrl.lowercase().endsWith(".mp4")) {
                        if (currentUrl == lastPlayedUrl) {
                            Log.d("MainActivity", "Removiendo tiempo guardado para '$currentUrl' (Película terminada)")
                            prefs.edit().remove(currentUrl).commit()
                        }
                        pauseOverlay.text = "Película terminada"
                        pauseOverlay.visibility = View.VISIBLE
                        timeOverlay.visibility = View.GONE // Asegurarse de que el de tiempo esté oculto

                        Handler(mainLooper).postDelayed({
                            pauseOverlay.visibility = View.GONE
                            showChannelList()
                            currentChannelIndex = -1
                            lastPlayedUrl = null
                        }, 3000) // Muestra "Película terminada" por 3 segundos
                    } else {
                        mediaPlayer.stop()
                        showChannelList()
                        lastPlayedUrl = null
                    }
                    videoPlaceholder.visibility = View.VISIBLE
                }
            }
        }

        try {
            val prefsFile = File(applicationInfo.dataDir, "shared_prefs/tv_app_times.xml")
            if (prefsFile.exists()) {
                Log.d("MainActivity", "Archivo de preferencias 'tv_app_times.xml' existe. Intentando cargar...")
                val dummyCheck = prefs.getString("some_dummy_key_to_load", null)
                Log.d("MainActivity", "Prefs inicializadas y forzadas a cargar. Dummy check: $dummyCheck")
            } else {
                Log.d("MainActivity", "Archivo de preferencias 'tv_app_times.xml' NO existe aún.")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al intentar forzar la carga de SharedPreferences: ${e.message}")
        }

        loadChannels()
        startAutoHideListTimer()
    }

    override fun onResume() {
        super.onResume()
        if (!mediaPlayer.isPlaying) {
            videoPlaceholder.visibility = View.VISIBLE
            videoLayout.visibility = View.INVISIBLE
        }
        showChannelList()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d("MainActivity", "Se presionó Home, cerrando app...")
        saveCurrentPlaybackProgress()
        cerrarAplicacion()
    }

    override fun onStop() {
        saveCurrentPlaybackProgress()
        super.onStop()
        Log.d("MainActivity", "onStop detectado, cerrando app...")
        cerrarAplicacion()
    }

    private fun saveCurrentPlaybackProgress() {
        if (mediaPlayer.isPlaying && lastPlayedUrl != null && lastPlayedUrl!!.lowercase().endsWith(".mp4")) {
            val currentTime = mediaPlayer.time
            if (currentTime > 2000L) {
                Log.d("MainActivity", "Guardando progreso para '$lastPlayedUrl' en onStop/saveCurrentPlaybackProgress: ${formatTime(currentTime)}")
                prefs.edit().putLong(lastPlayedUrl!!, currentTime).commit()
            } else {
                Log.d("MainActivity", "No se guardó el progreso para '$lastPlayedUrl' porque el tiempo (${formatTime(currentTime)}) es menor o igual a 2 segundos.")
            }
        }
    }

    private fun cerrarAplicacion() {
        try {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVLC.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finishAffinity()
        System.exit(0)
    }

    private fun loadChannels() {
        thread {
            try {
                val url = URL(xspfUrl)
                val inputStream = url.openStream()
                val parser: XmlPullParser = Xml.newPullParser()
                parser.setInput(inputStream, null)

                var eventType = parser.eventType
                var currentTitle = ""
                var currentLocation = ""
                var insideTrack = false

                channels.clear()

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val name = parser.name
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (name.equals("track", ignoreCase = true)) {
                                insideTrack = true
                            } else if (insideTrack) {
                                if (name.equals("title", ignoreCase = true)) {
                                    currentTitle = parser.nextText()
                                } else if (name.equals("location", ignoreCase = true)) {
                                    currentLocation = parser.nextText()
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (name.equals("track", ignoreCase = true)) {
                                if (currentTitle.isNotEmpty() && currentLocation.isNotEmpty()) {
                                    channels.add(Channel(currentTitle, currentLocation))
                                }
                                currentTitle = ""
                                currentLocation = ""
                                insideTrack = false
                            }
                        }
                    }
                    eventType = parser.next()
                }
                inputStream.close()

                runOnUiThread {
                    channels.sortBy { it.title.lowercase() }

                    val adapter = ArrayAdapter(
                        this@MainActivity,
                        R.layout.channel_list_item,
                        R.id.channel_name,
                        channels.map { it.title }
                    )
                    listView.adapter = adapter

                    currentChannelIndex = 0
                    listView.setSelection(0)
                    listView.post {
                        listView.setItemChecked(0, true)
                        listView.requestFocusFromTouch()
                    }

                    listView.setOnItemClickListener { _, _, position, _ ->
                        if (position in channels.indices) {
                            saveCurrentPlaybackProgress()

                            currentChannelIndex = position
                            val url = channels[position].url
                            Handler(mainLooper).postDelayed({
                                playChannel(url)
                                hideChannelList()
                            }, 200)
                        }
                    }

                    listView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            listView.setItemChecked(position, true)
                            currentChannelIndex = position
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {
                            // no action
                        }
                    }

                    listView.setOnKeyListener { _, _, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            autoHideRunnable?.let {
                                autoHideHandler.removeCallbacks(it)
                                autoHideRunnable = null
                            }
                        }
                        false
                    }

                    showChannelList()
                }
            } catch (e: IOException) {
                // Captura errores específicos de red/IO
                Log.e("MainActivity", "Error de red/IO al cargar canales: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Error de red al cargar canales. Verifique su conexión.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: XmlPullParserException) {
                // Captura errores específicos de parsing XML
                Log.e("MainActivity", "Error de parsing XML al cargar canales: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Error en el formato del archivo de canales. Intente de nuevo más tarde.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                // Captura cualquier otra excepción general
                Log.e("MainActivity", "Error desconocido al cargar canales: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Error al cargar canales: ${e.message}", // Muestra el mensaje de la excepción para depuración
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun playChannel(url: String) {
        currentChannelIndex = channels.indexOfFirst { it.url == url }
        if (currentChannelIndex == -1) {
            Toast.makeText(this, "Canal no encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("MainActivity", "playChannel llamado para URL: $url")
        lastPlayedUrl = url

        videoPlaceholder.visibility = View.VISIBLE
        videoLayout.visibility = View.INVISIBLE
        loadingIndicator.visibility = View.VISIBLE

        mediaPlayer.stop()

        mediaPlayer.detachViews()

        Handler(mainLooper).postDelayed({
            mediaPlayer.attachViews(videoLayout, null, false, false)

            val media = Media(libVLC, Uri.parse(url))
            media.addOption(":no-video-title-show")
            media.addOption(":no-osd")
            media.addOption(":no-spu")
            mediaPlayer.media = media
            media.release()

            mediaPlayer.setScale(1.0f)
            mediaPlayer.setAspectRatio(null)

            val savedTime = prefs.getLong(url, 0L)
            Log.d("MainActivity", "Tiempo guardado recuperado para URL '$url': ${savedTime} ms")

            if (url.lowercase().endsWith(".mp4") && savedTime > 2000L) {
                Log.d("MainActivity", "Mostrando diálogo de continuar para '$url' en ${savedTime} ms")
                val themedContext = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                continueDialog = AlertDialog.Builder(themedContext)
                    .setTitle("Continuar reproducción")
                    .setMessage("¿Deseas continuar desde ${formatTime(savedTime)} o empezar desde el principio?")
                    .setPositiveButton("Continuar") { _, _ ->
                        mediaPlayer.play()
                        mediaPlayer.time = savedTime
                        Log.d("MainActivity", "Continuando reproducción para '$url' desde ${savedTime} ms")
                        continueDialog = null
                    }
                    .setNegativeButton("Desde el principio") { _, _ ->
                        mediaPlayer.play()
                        Log.d("MainActivity", "Iniciando reproducción para '$url' desde el principio")
                        continueDialog = null
                    }
                    .setOnCancelListener {
                        mediaPlayer.play()
                        Log.d("MainActivity", "Diálogo cancelado, iniciando reproducción para '$url' desde el principio")
                        continueDialog = null
                    }
                    .create()

                continueDialog?.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        true
                    } else {
                        false
                    }
                }
                continueDialog?.show()
                continueDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            } else {
                Log.d("MainActivity", "No se muestra diálogo de continuar para '$url'. ¿Es MP4?: ${url.lowercase().endsWith(".mp4")}, Tiempo guardado: ${savedTime} ms")
                mediaPlayer.play()
            }

            Handler(mainLooper).postDelayed({
                selectAudioTrackInSpanish()
            }, 10000)

            getSharedPreferences("tv_app", MODE_PRIVATE)
                .edit()
                .putInt("last_channel", currentChannelIndex)
                .apply()

        }, 50)
    }

    private fun selectAudioTrackInSpanish() {
        val tracks = mediaPlayer.audioTracks
        if (tracks != null) {
            for (track in tracks) {
                val nameLower = track.name.lowercase()
                if (nameLower.contains("es") || nameLower.contains("spa") || nameLower.contains("español")) {
                    mediaPlayer.audioTrack = track.id
                    break
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        autoHideRunnable?.let {
            autoHideHandler.removeCallbacks(it)
            autoHideRunnable = null
        }

        if (currentChannelIndex < 0 || currentChannelIndex >= channels.size) {
            return super.onKeyDown(keyCode, event)
        }

        val currentUrl = channels[currentChannelIndex].url
        val isMp4 = currentUrl.lowercase().endsWith(".mp4")

        if (isMp4) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.pause()
                        // Cuando se pausa, queremos que "Pausado" se quede indefinidamente
                        // Aseguramos que solo se muestre "Pausado" sin temporizador de ocultación
                        showPauseOverlay(true) // Llama solo a la parte que muestra "Pausado"
                    } else {
                        mediaPlayer.play()
                        // Cuando se reanuda, el Event.Playing de VLC ya oculta los overlays.
                        // Podríamos también mostrar un mensaje breve de "Reproduciendo" aquí.
                        // Para este caso, vamos a mostrar "Reproduciendo" brevemente.
                        showPlayingOverlayBriefly() // Nuevo método para mostrar "Reproduciendo" brevemente
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val newTime = (mediaPlayer.time + 15000).coerceAtMost(mediaPlayer.length)
                    mediaPlayer.time = newTime
                    showTimeOverlay(newTime, mediaPlayer.length)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val newTime = (mediaPlayer.time - 15000).coerceAtLeast(0)
                    mediaPlayer.time = newTime
                    showTimeOverlay(newTime, mediaPlayer.length)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    val currentTime = mediaPlayer.time
                    if (currentTime > 2000L) {
                        Log.d("MainActivity", "Guardando progreso para '$currentUrl' en onKeyDown(BACK): ${formatTime(currentTime)}")
                        prefs.edit().putLong(currentUrl, currentTime).commit()
                    } else {
                        Log.d("MainActivity", "No se guardó el progreso para '$currentUrl' en onKeyDown(BACK) porque el tiempo (${formatTime(currentTime)}) es menor o igual a 2 segundos.")
                    }

                    if (!listVisible) {
                        mediaPlayer.stop()
                        videoPlaceholder.visibility = View.VISIBLE
                        videoLayout.visibility = View.INVISIBLE
                        showChannelList()
                        return true
                    } else {
                        showExitConfirmation()
                        return true
                    }
                }
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (continueDialog?.isShowing == true) {
                        return true
                    } else if (!listVisible) {
                        mediaPlayer.stop()
                        videoPlaceholder.visibility = View.VISIBLE
                        videoLayout.visibility = View.INVISIBLE
                        showChannelList()
                        return true
                    } else {
                        showExitConfirmation()
                        return true
                    }
                }
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                showSearchDialog()
                true
            }
            KeyEvent.KEYCODE_R -> {
                showChannelList()
                loadChannels()
                Toast.makeText(this, "Recargando canales...", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // === MÉTODOS DE OVERLAY MODIFICADOS ===

    private fun showPauseOverlay(paused: Boolean) {
        // Cancelar cualquier temporizador de ocultación de overlays
        timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
        playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }

        timeOverlay.visibility = View.GONE // Ocultar el de tiempo

        if (paused) {
            // Mostrar "Pausado" y mantenerlo visible indefinidamente
            pauseOverlay.text = "Pausado"
            pauseOverlay.visibility = View.VISIBLE
        } else {
            // Este 'else' ahora solo se usará si se llama con `paused = false`
            // que en nuestro caso, debería ser manejado por `showPlayingOverlayBriefly()`
            // Si por alguna razón se llama `showPauseOverlay(false)` directamente,
            // seguirá mostrando "Reproduciendo" brevemente.
            showPlayingOverlayBriefly()
        }
    }

    // Nuevo método para mostrar "Reproduciendo" brevemente
    private fun showPlayingOverlayBriefly() {
        // Asegúrate de ocultar el timeOverlay si estaba visible
        timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
        timeOverlay.visibility = View.GONE

        // Asegúrate de cancelar el runnable del mensaje "Reproduciendo" si estaba activo
        playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }

        pauseOverlay.text = "Reproduciendo"
        pauseOverlay.visibility = View.VISIBLE
        playingOverlayRunnable = Runnable {
            pauseOverlay.visibility = View.GONE
        }
        overlayHandler.postDelayed(playingOverlayRunnable!!, 2000) // Se oculta después de 2 segundos
    }


    private fun showTimeOverlay(currentMs: Long, totalMs: Long) {
        // Asegúrate de ocultar el pauseOverlay y el mensaje "Reproduciendo"
        pauseOverlay.visibility = View.GONE
        playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }


        timeOverlay.text = "Tiempo: ${formatTime(currentMs)} / ${formatTime(totalMs)}"
        // Usamos timeOverlayRunnable para este caso
        timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) } // Cancelar cualquier temporizador anterior
        timeOverlay.visibility = View.VISIBLE
        timeOverlayRunnable = Runnable {
            timeOverlay.visibility = View.GONE
        }
        overlayHandler.postDelayed(timeOverlayRunnable!!, 2000) // Se oculta después de 2 segundos
    }


    private fun showChannelList() {
        listView.visibility = View.VISIBLE
        listView.requestFocus()
        if (currentChannelIndex in channels.indices) {
            listView.setSelection(currentChannelIndex)
            listView.setItemChecked(currentChannelIndex, true)
        }
        listVisible = true
        videoPlaceholder.visibility = View.VISIBLE
        videoLayout.visibility = View.INVISIBLE

        // Ocultar cualquier overlay visible al mostrar la lista
        pauseOverlay.visibility = View.GONE
        timeOverlay.visibility = View.GONE

        // === Limpiar los runnables correctamente ===
        timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
        playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) } // Limpiar el runnable del mensaje "Reproduciendo"
    }

    private fun hideChannelList() {
        listView.visibility = View.GONE
        listVisible = false
    }

    private fun startAutoHideListTimer() {
        if (autoHideStarted) return
        autoHideStarted = true

        autoHideRunnable = Runnable {
            if (listVisible) {
                // No ocultar lista automáticamente
            }
        }
        autoHideHandler.postDelayed(autoHideRunnable!!, 5000)
    }

    private fun showSearchDialog() {
        val themedContext = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val input = EditText(themedContext)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_DONE

        val layout = LinearLayout(themedContext)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 30, 50, 10)
        layout.addView(input)

        val resultList = ListView(themedContext)
        layout.addView(resultList)

        val adapter = object : ArrayAdapter<String>(
            this,
            R.layout.search_list_item,
            R.id.search_result_text
        ) {}
        resultList.adapter = adapter

        val dialog = AlertDialog.Builder(themedContext)
            .setTitle("Buscar canal")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .create()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.lowercase() ?: ""
                val results = channels.filter { it.title.lowercase().contains(query) }
                adapter.clear()
                adapter.addAll(results.map { it.title })
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(input.windowToken, 0)
                true
            } else {
                false
            }
        }

        resultList.setOnItemClickListener { _, _, position, _ ->
            val selectedTitle = adapter.getItem(position)
            val index = channels.indexOfFirst { it.title == selectedTitle }
            if (index != -1) {
                saveCurrentPlaybackProgress()

                currentChannelIndex = index
                listView.setSelection(index)
                listView.setItemChecked(index, true)
                playChannel(channels[index].url)
                dialog.dismiss()
            }
        }

        dialog.setOnShowListener {
            input.requestFocus()
        }

        dialog.show()
    }

    private fun showExitConfirmation() {
        val themedContext = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

        val dialog = AlertDialog.Builder(themedContext)
            .setTitle("Salir")
            .setMessage("¿Deseas salir de la aplicación?")
            .setPositiveButton("Sí") { _, _ ->
                finish()
            }
            .setNegativeButton("No", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer.stop()
        mediaPlayer.detachViews()
        mediaPlayer.release()
        libVLC.release()
    }
}

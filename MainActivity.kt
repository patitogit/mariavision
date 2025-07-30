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
import java.io.IOException
import org.xmlpull.v1.XmlPullParserException
import retrofit2.http.GET // This is crucial for @GET
import retrofit2.http.Query // This is crucial for @Query
// === NUEVAS IMPORTACIONES PARA TMDB, RETROFIT Y GSON ===
import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
// =======================================================

class MainActivity : AppCompatActivity() {

    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var listView: ListView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var videoPlaceholder: View

    private lateinit var pauseOverlay: TextView
    private lateinit var timeOverlay: TextView

    private val overlayHandler = Handler()

    private var timeOverlayRunnable: Runnable? = null
    private var playingOverlayRunnable: Runnable? = null

    private val xspfUrl =
        "https://github.com/patitogit/mariavision/raw/refs/heads/main/Peliculas.xspf"

    // === CAMBIOS AQUÍ: La clase Channel ahora incluye posterUrl ===
    data class Channel(val title: String, val valUrl: String, var posterUrl: String? = null)
    // ============================================================

    private var channels = mutableListOf<Channel>()
    private var channelAdapter: ChannelAdapter? = null // === CAMBIO AQUÍ: Usamos nuestro ChannelAdapter ===
    private var currentChannelIndex = -1
    private var listVisible = true

    private var autoHideHandler = Handler()
    private var autoHideRunnable: Runnable? = null
    private var autoHideStarted = false

    private val prefs by lazy { getSharedPreferences("tv_app_times", MODE_PRIVATE) }

    private var continueDialog: AlertDialog? = null

    private var lastPlayedUrl: String? = null

    // === NUEVOS CAMPOS PARA TMDB API ===
    private val TMDB_API_KEY = "aaab89a062f042c014ccd0ad6ac652f4" // Tu API Key
    private val TMDB_API_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJhYWFiODlhMDYyZjA0MmMwMTRjY2QwYWQ2YWM2NTJmNCIsIm5iZiI6MTc1MzkwODU4Mi40NjQsInN1YiI6IjY4OGE4NTY2YjkxODg2MjRlNjVhZTM2ZCIsInNjb3BlcCI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.iEKsammmU0l_fnu_2PbeGPPUq_UXegqSbKRpR00fib0" // Tu API Token (para el header Authorization)
    private val TMDB_BASE_URL = "https://api.themoviedb.org/3/"
    private val TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500" // w500 para el tamaño del póster

    // Retrofit service
    private val tmdbService: TmdbApiService by lazy {
        Retrofit.Builder()
            .baseUrl(TMDB_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApiService::class.java)
    }

    // Interfaz para definir las llamadas a la API de TMDB
    interface TmdbApiService {
        @GET("search/movie")
        fun searchMovie(
            @Query("api_key") apiKey: String,
            @Query("query") query: String,
            @Query("language") language: String = "es-ES" // Opcional: especificar idioma
        ): Call<MovieSearchResponse>
    }

    // Clases de datos para parsear la respuesta JSON de TMDB
    data class MovieSearchResponse(
        val results: List<MovieResult>
    )

    data class MovieResult(
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("title") val title: String,
        @SerializedName("release_date") val releaseDate: String?
    )
    // =======================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoLayout = findViewById(R.id.video_surface)
        listView = findViewById(R.id.channel_list)
        loadingIndicator = findViewById(R.id.loading_indicator)
        videoPlaceholder = findViewById(R.id.video_placeholder)

        pauseOverlay = findViewById(R.id.pause_overlay)
        timeOverlay = findViewById(R.id.time_overlay)

        pauseOverlay.visibility = View.GONE
        timeOverlay.visibility = View.GONE

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
                    pauseOverlay.visibility = View.GONE
                    timeOverlay.visibility = View.GONE
                    timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
                    playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
                }
                MediaPlayer.Event.EndReached -> runOnUiThread {
                    Log.d("MainActivity", "Reproducción finalizada para URL: $lastPlayedUrl")

                    val currentUrl = if (currentChannelIndex in channels.indices) channels[currentChannelIndex].valUrl else null // === CAMBIO AQUÍ: valUrl ===
                    if (currentUrl != null && currentUrl.lowercase().endsWith(".mp4")) {
                        if (currentUrl == lastPlayedUrl) {
                            Log.d("MainActivity", "Removiendo tiempo guardado para '$currentUrl' (Película terminada)")
                            prefs.edit().remove(currentUrl).commit()
                        }
                        pauseOverlay.text = "Película terminada"
                        pauseOverlay.visibility = View.VISIBLE
                        timeOverlay.visibility = View.GONE

                        Handler(mainLooper).postDelayed({
                            pauseOverlay.visibility = View.GONE
                            showChannelList()
                            currentChannelIndex = -1
                            lastPlayedUrl = null
                        }, 3000)
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
                                    // === CAMBIOS AQUÍ: Creamos el objeto Channel con posterUrl null inicialmente ===
                                    channels.add(Channel(currentTitle, currentLocation, null))
                                    // =========================================================================
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

                    // === CAMBIO AQUÍ: Usamos nuestro ChannelAdapter ===
                    channelAdapter = ChannelAdapter(this@MainActivity, channels)
                    listView.adapter = channelAdapter
                    // ================================================

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
                            val url = channels[position].valUrl // === CAMBIO AQUÍ: valUrl ===
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
                    // === NUEVA LLAMADA AQUÍ: Para cargar los pósteres ===
                    loadPostersForChannels()
                    // ===================================================
                }
            } catch (e: IOException) {
                Log.e("MainActivity", "Error de red/IO al cargar canales: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Error de red al cargar canales. Verifique su conexión.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: XmlPullParserException) {
                Log.e("MainActivity", "Error de parsing XML al cargar canales: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Error en el formato del archivo de canales. Intente de nuevo más tarde.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error desconocido al cargar canales: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Error al cargar canales: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // === NUEVO MÉTODO: loadPostersForChannels ===
    private fun loadPostersForChannels() {
        // Ejecutamos en un hilo separado para no bloquear la UI
        thread {
            channels.forEachIndexed { index, channel ->
                // Solo busca póster si el canal es una película (ej. termina en .mp4)
                // y si aún no tiene un posterUrl asignado
                if (channel.valUrl.lowercase().endsWith(".mp4") && channel.posterUrl == null) {
                    searchMoviePoster(channel.title) { posterUrl ->
                        if (posterUrl != null) {
                            // Si encontramos un póster, actualizamos el objeto Channel
                            channels[index].posterUrl = posterUrl
                            // Y notificamos al adaptador en el hilo principal
                            runOnUiThread {
                                channelAdapter?.notifyDataSetChanged()
                            }
                        }
                    }
                }
            }
        }
    }
    // ============================================

    // === NUEVO MÉTODO: searchMoviePoster ===
    private fun searchMoviePoster(movieTitle: String, callback: (String?) -> Unit) {
        tmdbService.searchMovie(TMDB_API_KEY, movieTitle).enqueue(object : Callback<MovieSearchResponse> {
            override fun onResponse(call: Call<MovieSearchResponse>, response: Response<MovieSearchResponse>) {
                if (response.isSuccessful) {
                    val movieResults = response.body()?.results
                    val firstPosterPath = movieResults?.firstOrNull()?.posterPath

                    if (firstPosterPath != null) {
                        val fullPosterUrl = "$TMDB_IMAGE_BASE_URL$firstPosterPath"
                        Log.d("TMDB", "Póster encontrado para '$movieTitle': $fullPosterUrl")
                        callback(fullPosterUrl)
                    } else {
                        Log.d("TMDB", "No se encontró póster para '$movieTitle'.")
                        callback(null)
                    }
                } else {
                    Log.e("TMDB", "Error en la respuesta de TMDB para '$movieTitle': ${response.code()}")
                    callback(null)
                }
            }

            override fun onFailure(call: Call<MovieSearchResponse>, t: Throwable) {
                Log.e("TMDB", "Fallo al conectar con TMDB para '$movieTitle': ${t.message}")
                callback(null)
            }
        })
    }
    // =======================================

    private fun playChannel(url: String) {
        currentChannelIndex = channels.indexOfFirst { it.valUrl == url } // === CAMBIO AQUÍ: valUrl ===
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

        val currentUrl = channels[currentChannelIndex].valUrl // === CAMBIO AQUÍ: valUrl ===
        val isMp4 = currentUrl.lowercase().endsWith(".mp4")

        if (isMp4) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.pause()
                        showPauseOverlay(true)
                    } else {
                        mediaPlayer.play()
                        showPlayingOverlayBriefly()
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

    private fun showPauseOverlay(paused: Boolean) {
        timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
        playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }

        timeOverlay.visibility = View.GONE

        if (paused) {
            pauseOverlay.text = "Pausado"
            pauseOverlay.visibility = View.VISIBLE
        } else {
            showPlayingOverlayBriefly()
        }
    }

    private fun showPlayingOverlayBriefly() {
        timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
        timeOverlay.visibility = View.GONE

        playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }

        pauseOverlay.text = "Reproduciendo"
        pauseOverlay.visibility = View.VISIBLE
        playingOverlayRunnable = Runnable {
            pauseOverlay.visibility = View.GONE
        }
        overlayHandler.postDelayed(playingOverlayRunnable!!, 2000)
    }

    private fun showTimeOverlay(currentMs: Long, totalMs: Long) {
        pauseOverlay.visibility = View.GONE
        playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }

        timeOverlay.text = "Tiempo: ${formatTime(currentMs)} / ${formatTime(totalMs)}"
        timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
        timeOverlay.visibility = View.VISIBLE
        timeOverlayRunnable = Runnable {
            timeOverlay.visibility = View.GONE
        }
        overlayHandler.postDelayed(timeOverlayRunnable!!, 2000)
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

        pauseOverlay.visibility = View.GONE
        timeOverlay.visibility = View.GONE

        timeOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
        playingOverlayRunnable?.let { overlayHandler.removeCallbacks(it) }
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

        // === CAMBIO AQUÍ: Usamos ChannelAdapter en el diálogo de búsqueda también ===
        val searchResults = mutableListOf<Channel>()
        val searchAdapter = ChannelAdapter(this, searchResults)
        resultList.adapter = searchAdapter
        // =========================================================================

        val dialog = AlertDialog.Builder(themedContext)
            .setTitle("Buscar canal")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .create()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.lowercase() ?: ""
                searchResults.clear()
                searchResults.addAll(channels.filter { it.title.lowercase().contains(query) })
                searchAdapter.notifyDataSetChanged() // Notificar al adaptador del diálogo
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
            // === CAMBIO AQUÍ: Obtenemos el canal directamente del searchResults ===
            val selectedChannel = searchResults[position]
            val indexInMainList = channels.indexOfFirst { it.title == selectedChannel.title && it.valUrl == selectedChannel.valUrl }
            if (indexInMainList != -1) {
                saveCurrentPlaybackProgress()

                currentChannelIndex = indexInMainList
                listView.setSelection(indexInMainList)
                listView.setItemChecked(indexInMainList, true)
                playChannel(channels[indexInMainList].valUrl) // === CAMBIO AQUÍ: valUrl ===
                dialog.dismiss()
            }
            // ===================================================================
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

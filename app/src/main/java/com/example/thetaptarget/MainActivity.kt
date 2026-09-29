package com.example.thetaptarget

import android.content.SharedPreferences
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Random

class MainActivity : AppCompatActivity() {

    companion object {
        // ---- pengaturan permainan (mudah diubah) ----
        private const val DURASI_PERMAINAN_MS = 30_000L   // 30 detik
        private const val POIN_PER_KLIK = 20              // ubah ke 10 bila ingin poin standar
        private const val KLIK_PER_LEVEL = 5              // target mengecil setiap 5 klik
        private const val UKURAN_AWAL_DP = 72f            // sama dengan layout_width imgTarget
        private const val UKURAN_MIN_DP = 40f             // ukuran terkecil target
        private const val PENGURANGAN_DP = 6f             // pengurangan ukuran tiap level
        private const val DURASI_TARGET_MS = 1000L        // target menghilang bila tidak ditekan
        private const val JEDA_MUNCUL_MS = 250L           // jeda target sebelum muncul lagi

        // ---- SharedPreferences ----
        private const val PREF_NAME = "tap_the_target_prefs"
        private const val KEY_SKOR_TERTINGGI = "skor_tertinggi"

        // ---- kunci onSaveInstanceState ----
        private const val STATE_SKOR = "state_skor"
        private const val STATE_WAKTU = "state_waktu"
        private const val STATE_KLIK = "state_klik"
        private const val STATE_JEDA = "state_jeda"
        private const val STATE_SELESAI = "state_selesai"
        private const val STATE_REKOR_BARU = "state_rekor_baru"
        private const val STATE_LATAR = "state_latar"
    }

    // ==== data permainan ====
    private var skor = 0
    private var sisaWaktuDetik = 30
    private var jumlahKlik = 0
    private var timer: CountDownTimer? = null
    private var sedangDijeda = false
    private var permainanSelesai = false
    private var rekorBaru = false
    private var skorTertinggi = 0
    private var indexLatar = 0
    private val acak = Random()

    // daftar gambar latar (satu ImageView, gambar dipilih acak tiap permainan)
    private val daftarLatar = intArrayOf(
        R.drawable.bg_game,
        R.drawable.bg_game_2,
        R.drawable.bg_game_3
    )

    // ==== penjadwalan target menghilang ====
    private val handlerTarget = Handler(Looper.getMainLooper())
    private val runnableTargetHilang = Runnable { targetHilang() }
    private val runnableTargetMuncul = Runnable { targetMuncul() }

    // ==== penyimpanan & suara ====
    private lateinit var prefs: SharedPreferences
    private var toneGenerator: ToneGenerator? = null

    // ==== view ====
    private lateinit var frameGame: FrameLayout
    private lateinit var imgBackground: ImageView
    private lateinit var imgTarget: ImageView
    private lateinit var tvSkor: TextView
    private lateinit var tvWaktu: TextView
    private lateinit var btnPause: Button
    private lateinit var overlayPause: LinearLayout
    private lateinit var overlayGameOver: LinearLayout
    private lateinit var tvSkorJeda: TextView
    private lateinit var tvSkorAkhir: TextView
    private lateinit var tvSkorTertinggi: TextView
    private lateinit var tvRekorBaru: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1) SharedPreferences & efek suara
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        skorTertinggi = prefs.getInt(KEY_SKOR_TERTINGGI, 0)
        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: RuntimeException) {
            null
        }

        // 2) ambil referensi semua view
        frameGame = findViewById(R.id.frameGame)
        imgBackground = findViewById(R.id.imgBackground)
        imgTarget = findViewById(R.id.imgTarget)
        tvSkor = findViewById(R.id.tvSkor)
        tvWaktu = findViewById(R.id.tvWaktu)
        btnPause = findViewById(R.id.btnPause)
        overlayPause = findViewById(R.id.overlayPause)
        overlayGameOver = findViewById(R.id.overlayGameOver)
        tvSkorJeda = findViewById(R.id.tvSkorJeda)
        tvSkorAkhir = findViewById(R.id.tvSkorAkhir)
        tvSkorTertinggi = findViewById(R.id.tvSkorTertinggi)
        tvRekorBaru = findViewById(R.id.tvRekorBaru)

        // 3) pasang event listener
        imgTarget.setOnClickListener { targetDitekan() }
        btnPause.setOnClickListener { jedaPermainan() }
        findViewById<Button>(R.id.btnLanjutkan).setOnClickListener { lanjutkanPermainan() }
        findViewById<Button>(R.id.btnUlangi).setOnClickListener { mulaiPermainan() }
        findViewById<Button>(R.id.btnMainLagi).setOnClickListener { mulaiPermainan() }

        // 4) permainan baru, atau pulihkan bila layar diputar
        if (savedInstanceState == null) {
            mulaiPermainan()
        } else {
            pulihkanPermainan(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SKOR, skor)
        outState.putInt(STATE_WAKTU, sisaWaktuDetik)
        outState.putInt(STATE_KLIK, jumlahKlik)
        outState.putBoolean(STATE_JEDA, sedangDijeda)
        outState.putBoolean(STATE_SELESAI, permainanSelesai)
        outState.putBoolean(STATE_REKOR_BARU, rekorBaru)
        outState.putInt(STATE_LATAR, indexLatar)
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()               // hentikan timer agar tidak bocor
        hentikanJadwalTarget()        // hentikan jadwal target menghilang
        toneGenerator?.release()      // lepaskan resource suara
        toneGenerator = null
    }

    // ================= memulai / mengulang permainan =================
    private fun mulaiPermainan() {
        // hentikan semua proses lama (penting saat Ulangi / Main Lagi)
        timer?.cancel()
        hentikanJadwalTarget()
        imgTarget.animate().cancel()
        overlayPause.animate().cancel()
        overlayGameOver.animate().cancel()

        // 1) reset data permainan
        skor = 0
        jumlahKlik = 0
        sisaWaktuDetik = (DURASI_PERMAINAN_MS / 1000).toInt()
        sedangDijeda = false
        permainanSelesai = false
        rekorBaru = false

        // 2) pilih gambar latar acak
        indexLatar = acak.nextInt(daftarLatar.size)
        terapkanLatar()

        // 3) perbarui HUD
        perbaruiHud()

        // 4) sembunyikan overlay, tampilkan & aktifkan target
        overlayPause.visibility = View.GONE
        overlayGameOver.visibility = View.GONE
        overlayGameOver.alpha = 1f
        overlayPause.alpha = 1f
        imgTarget.visibility = View.VISIBLE
        imgTarget.isEnabled = true
        terapkanSkalaTarget()
        btnPause.isEnabled = true

        // 5) posisi acak pertama (post{} agar ukuran layout sudah terhitung)
        frameGame.post { pindahkanTarget() }

        // 6) jalankan timer 30 detik & jadwal target
        jalankanTimer(DURASI_PERMAINAN_MS)
        jadwalkanTargetHilang()
    }

    // ================= memulihkan permainan setelah layar diputar =================
    private fun pulihkanPermainan(state: Bundle) {
        skor = state.getInt(STATE_SKOR, 0)
        sisaWaktuDetik = state.getInt(STATE_WAKTU, (DURASI_PERMAINAN_MS / 1000).toInt())
        jumlahKlik = state.getInt(STATE_KLIK, 0)
        sedangDijeda = state.getBoolean(STATE_JEDA, false)
        permainanSelesai = state.getBoolean(STATE_SELESAI, false)
        rekorBaru = state.getBoolean(STATE_REKOR_BARU, false)
        indexLatar = state.getInt(STATE_LATAR, 0).coerceIn(0, daftarLatar.size - 1)

        terapkanLatar()
        perbaruiHud()
        terapkanSkalaTarget()

        overlayPause.visibility = View.GONE
        overlayGameOver.visibility = View.GONE
        overlayPause.alpha = 1f
        overlayGameOver.alpha = 1f
        imgTarget.visibility = View.VISIBLE
        btnPause.isEnabled = !permainanSelesai

        frameGame.post { pindahkanTarget() }

        when {
            permainanSelesai -> {
                tampilkanOverlayGameOver(false)
            }
            sedangDijeda -> {
                imgTarget.isEnabled = false
                tvSkorJeda.text = skor.toString()
                overlayPause.visibility = View.VISIBLE
            }
            else -> {
                imgTarget.isEnabled = true
                jalankanTimer(sisaWaktuDetik * 1000L)
                jadwalkanTargetHilang()
            }
        }
    }

    // ================= tampilan (HUD, latar, ukuran target) =================
    private fun perbaruiHud() {
        tvSkor.text = getString(R.string.label_skor, skor)
        tvWaktu.text = getString(R.string.label_waktu, sisaWaktuDetik)
    }

    private fun terapkanLatar() {
        imgBackground.setImageResource(daftarLatar[indexLatar])
    }

    // ukuran target (dp) mengecil setiap KLIK_PER_LEVEL klik, minimal UKURAN_MIN_DP
    private fun ukuranTargetDp(): Float {
        val level = jumlahKlik / KLIK_PER_LEVEL
        return maxOf(UKURAN_MIN_DP, UKURAN_AWAL_DP - (level * PENGURANGAN_DP))
    }

    // skala 1f = ukuran awal. Memakai scaleX/scaleY (bukan mengubah layout)
    // agar gambar target tidak terpotong dan posisi x/y tetap akurat.
    private fun skalaTarget(): Float = ukuranTargetDp() / UKURAN_AWAL_DP

    private fun terapkanSkalaTarget() {
        val skala = skalaTarget()
        imgTarget.scaleX = skala
        imgTarget.scaleY = skala
    }

    // ================= posisi acak target =================
    private fun pindahkanTarget() {
        val lebarArea = frameGame.width
        val tinggiArea = frameGame.height
        val lebarTarget = imgTarget.width
        val tinggiTarget = imgTarget.height

        // jika layout belum diukur, hentikan agar tidak error
        if (lebarArea == 0 || tinggiArea == 0 || lebarTarget == 0 || tinggiTarget == 0) return

        val jarakAman = (24 * resources.displayMetrics.density).toInt()
        val maxX = lebarArea - lebarTarget - (jarakAman * 2)
        val maxY = tinggiArea - tinggiTarget - (jarakAman * 2)
        if (maxX <= 0 || maxY <= 0) return

        // coba beberapa kali agar target tidak tertutup tombol Pause
        var posisiX = 0
        var posisiY = 0
        var percobaan = 0
        do {
            posisiX = jarakAman + acak.nextInt(maxX)
            posisiY = jarakAman + acak.nextInt(maxY)
            percobaan++
        } while (menabrakTombolPause(posisiX, posisiY, lebarTarget, tinggiTarget) && percobaan < 10)

        imgTarget.x = posisiX.toFloat()
        imgTarget.y = posisiY.toFloat()
    }

    private fun menabrakTombolPause(x: Int, y: Int, lebar: Int, tinggi: Int): Boolean {
        if (btnPause.width == 0 || btnPause.height == 0) return false
        return x < btnPause.right &&
                x + lebar > btnPause.left &&
                y < btnPause.bottom &&
                y + tinggi > btnPause.top
    }

    // ================= klik target =================
    private fun targetDitekan() {
        // abaikan klik bila dijeda atau permainan sudah berakhir
        if (sedangDijeda || permainanSelesai) return

        // 1) tambah skor & perbarui HUD
        skor += POIN_PER_KLIK
        jumlahKlik++
        tvSkor.text = getString(R.string.label_skor, skor)

        // 2) efek suara
        mainkanSuara(ToneGenerator.TONE_PROP_BEEP, 60)

        // 3) umpan balik visual: mengecil sebentar lalu kembali ke ukuran level saat ini
        val skala = skalaTarget()
        imgTarget.animate()
            .scaleX(skala * 0.6f).scaleY(skala * 0.6f).setDuration(70)
            .withEndAction {
                val skalaAkhir = skalaTarget()
                imgTarget.animate().scaleX(skalaAkhir).scaleY(skalaAkhir).setDuration(70).start()
            }
            .start()

        // 4) teks poin melayang (harus sebelum target pindah)
        tampilkanPoinMelayang()

        // 5) target berpindah ke posisi acak berikutnya
        pindahkanTarget()

        // 6) mulai hitung ulang waktu target menghilang
        jadwalkanTargetHilang()
    }

    private fun tampilkanPoinMelayang() {
        val teks = getString(R.string.label_poin_melayang, POIN_PER_KLIK)
        val label = TextView(this).apply {
            text = teks
            setTextColor(0xFF86EFAC.toInt())
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }

        // FrameLayout memakai match_parent secara default, jadi beri ukuran wrap_content
        frameGame.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        label.x = imgTarget.x + 20f
        label.y = (imgTarget.y - 30f).coerceAtLeast(0f)

        label.animate()
            .alpha(0f)
            .translationYBy(-60f)
            .setDuration(600)
            .withEndAction { frameGame.removeView(label) }
            .start()
    }

    // ================= target menghilang bila tidak ditekan =================
    private fun jadwalkanTargetHilang() {
        hentikanJadwalTarget()
        handlerTarget.postDelayed(runnableTargetHilang, DURASI_TARGET_MS)
    }

    private fun hentikanJadwalTarget() {
        handlerTarget.removeCallbacks(runnableTargetHilang)
        handlerTarget.removeCallbacks(runnableTargetMuncul)
    }

    private fun targetHilang() {
        if (sedangDijeda || permainanSelesai) return
        imgTarget.visibility = View.INVISIBLE
        handlerTarget.postDelayed(runnableTargetMuncul, JEDA_MUNCUL_MS)
    }

    private fun targetMuncul() {
        if (sedangDijeda || permainanSelesai) return
        pindahkanTarget()
        imgTarget.visibility = View.VISIBLE
        jadwalkanTargetHilang()
    }

    // ================= timer =================
    private fun jalankanTimer(durasi: Long) {
        // hentikan timer lama bila masih berjalan
        timer?.cancel()

        timer = object : CountDownTimer(durasi, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                sisaWaktuDetik = (millisUntilFinished / 1000).toInt()
                tvWaktu.text = getString(R.string.label_waktu, sisaWaktuDetik)
            }

            override fun onFinish() {
                sisaWaktuDetik = 0
                tvWaktu.text = getString(R.string.label_waktu, 0)
                gameOver()
            }
        }.start()
    }

    // ================= jeda & lanjut =================
    private fun jedaPermainan() {
        if (sedangDijeda || permainanSelesai) return
        sedangDijeda = true

        // 1) hentikan timer & jadwal target
        timer?.cancel()
        hentikanJadwalTarget()

        // 2) pastikan target terlihat namun tidak bisa ditekan
        imgTarget.visibility = View.VISIBLE
        imgTarget.isEnabled = false

        // 3) tampilkan overlay jeda beserta skor sementara
        tvSkorJeda.text = skor.toString()
        overlayPause.alpha = 0f
        overlayPause.visibility = View.VISIBLE
        overlayPause.animate().alpha(1f).setDuration(200).start()
    }

    private fun lanjutkanPermainan() {
        if (!sedangDijeda) return
        sedangDijeda = false

        overlayPause.animate().cancel()
        overlayPause.visibility = View.GONE
        overlayPause.alpha = 1f

        imgTarget.visibility = View.VISIBLE
        imgTarget.isEnabled = true

        // lanjut dari sisa waktu terakhir (bukan 30 detik lagi)
        jalankanTimer(sisaWaktuDetik * 1000L)
        jadwalkanTargetHilang()
    }

    // ================= game over =================
    private fun gameOver() {
        if (permainanSelesai) return
        timer?.cancel()
        hentikanJadwalTarget()
        permainanSelesai = true
        sedangDijeda = false

        // cek skor tertinggi & simpan ke SharedPreferences
        rekorBaru = skor > skorTertinggi
        if (rekorBaru) {
            skorTertinggi = skor
            prefs.edit().putInt(KEY_SKOR_TERTINGGI, skorTertinggi).apply()
        }

        mainkanSuara(ToneGenerator.TONE_PROP_NACK, 300)
        tampilkanOverlayGameOver(true)
    }

    private fun tampilkanOverlayGameOver(animasi: Boolean) {
        // target tidak bisa ditekan & disembunyikan; tombol pause dinonaktifkan
        imgTarget.animate().cancel()
        imgTarget.isEnabled = false
        imgTarget.visibility = View.GONE
        btnPause.isEnabled = false
        overlayPause.visibility = View.GONE

        // isi skor akhir, skor tertinggi, dan label rekor baru
        tvSkorAkhir.text = skor.toString()
        tvSkorTertinggi.text = getString(R.string.label_skor_tertinggi, skorTertinggi)
        tvRekorBaru.visibility = if (rekorBaru) View.VISIBLE else View.GONE

        if (animasi) {
            // ubah visibility dulu, baru animasi memudar
            overlayGameOver.alpha = 0f
            overlayGameOver.visibility = View.VISIBLE
            overlayGameOver.animate().alpha(1f).setDuration(350).start()
        } else {
            overlayGameOver.alpha = 1f
            overlayGameOver.visibility = View.VISIBLE
        }
    }

    // ================= efek suara =================
    private fun mainkanSuara(tone: Int, durasiMs: Int) {
        try {
            toneGenerator?.startTone(tone, durasiMs)
        } catch (e: RuntimeException) {
            // abaikan bila perangkat gagal memutar suara
        }
    }
}
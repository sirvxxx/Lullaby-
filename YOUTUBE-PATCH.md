# YouTube source patch (26.2 Fabric)

Ditambahkan: sumber **YouTube** (tanpa login) lewat NewPipeExtractor + decoder Opus/WebM (Concentus).

File baru (common/core/.../music/): YoutubeMusicSource.kt, NewPipeDownloaderImpl.kt, OpusWebmDecoder.kt
File diubah: MusicSources.kt (enum+dispatch), CloudDrives.kt, PartyTrackCodec.kt, AudioDecoder.kt, Mp3Probe.kt,
NeteaseMusicModule.kt (range pilihan source), Sidebar.kt (tanpa tombol login utk YouTube),
common/build.gradle.kts, common/core/build.gradle.kts, modern26/build.gradle.kts (shade + relocate),
.github/workflows/build-26.2-fabric.yml

Listen Together tidak mendukung YouTube (supportsSource() = false).
Build: push ke GitHub -> Actions -> artifact "DangoMusic-26.2-fabric".

## Patch lirik YouTube (1.7.3)
- YoutubeLyrics.kt: judul dibersihkan lebih luas (kurung "(From ...)", "(feat. ...)", "(with ...)", "...", "| Official ..."), dan dicoba beberapa varian judul.
- Pencarian ke LRCLIB ditambah pencarian judul saja; NetEase/QQ/Kugou dicari dengan query judul+artis dan judul saja.
- Toleransi durasi 6 detik (sebelumnya 4-5 detik).

## Optimasi FPS (Low-end mode)
- Lyrics module: opsi baru "Low-end mode" (default mati). Kalau dinyalakan: Deep Glow dimatikan, dan latar kaca (液态玻璃) hanya diambil ulang tiap 4 frame.
- Common core: `me.sorihiko.dango.Performance` (saklar global).
- GlassRenderContext (modern & legacy): pengambilan latar dan pengambilan ulang latar dilewati di frame yang tidak perlu saat low-end aktif.

## Antrean, acak, dan muat lebih cepat
- Antrean: tombol "+" muncul saat baris lagu di-hover (menambahkan lagu ke akhir antrean tanpa memotong lagu yang sedang diputar). Fungsi di core: `MusicPlayer.enqueueLast`, `enqueueNext`.
- Acak: tombol "Shuffle" di samping "Play all" memilih satu lagu acak dari daftar (`MusicPlayer.playRandom`). Mode Shuffle di pemutar tetap berfungsi seperti sebelumnya.
- Muat lebih cepat: lagu berikutnya diunduh di latar (thread "Dango-Prefetch") saat lagu sekarang mulai diputar. Hasilnya disimpan (4 lagu terakhir: yang diputar dan yang sudah diunduh) jadi putar ulang atau kembali ke lagu sebelumnya langsung jalan. Di mode acak, lagu berikutnya ditentukan sekali dan dipakai bersama oleh prefetch dan tombol next.
- Normalisasi volume: kalau server sudah memberi nilai gain, mod tidak lagi mendekode 15 detik audio sebelum lagu mulai.

## Import playlist dari link (YouTube & Spotify)
- Cara pakai: tempel link playlist di kolom search lalu tekan Enter. Link yang dikenali: `youtube.com/playlist?list=...`, `music.youtube.com/...?list=...`, dan `open.spotify.com/playlist/...`.
- YouTube: isi playlist dibaca lewat NewPipe (maks 2000 lagu), setiap video jadi lagu sumber YouTube.
- Spotify: data lagu dibaca dari halaman embed publik. Setiap lagu dicocokkan (judul, artis, durasi ±4 detik) ke NetEase, lalu QQ, lalu YouTube. Lagu yang tidak ketemu dilewati dan jumlahnya ditampilkan di status.
- Playlist hasil impor disimpan di `config/dangomusic/imported-playlists.json` dan muncul di sidebar "My Playlists". Impor ulang link yang sama akan menimpa playlist lama.
- Catatan: halaman embed Spotify dan struktur NewPipe bisa berubah. Kalau impor gagal, status akan menampilkan pesan error.
- Perbaikan build: prefetch memeriksa songUrl null; JsonArray memakai JsonPrimitive (Gson versi lama tidak menerima String di add).
- Spotify: lagu dibaca lewat Web API (token dari web player, paging 100 per halaman) sehingga playlist panjang lengkap. Kalau gagal, kembali ke halaman embed (maks 100 lagu).
- Pencocokan Spotify: versi karaoke / instrumental / tribute tidak dipilih kecuali judul asli memintanya.
- Perbaikan "No decodable audio" (NetEase tamu): URL cadangan outer/url hanya dipakai saat login. Tanpa login mod melaporkan "tidak ada URL" dan melompat ke lagu berikutnya.
- Pencocokan impor: tanpa login, YouTube dicoba dulu. Playlist yang sudah terimpor dari NetEase perlu diimpor ulang (link yang sama akan menimpa).
- Spotify: Client Credentials dari config/dangomusic/spotify.txt (baris 1 Client ID, baris 2 Client Secret). Endpoint /tracks dicoba, lalu /items. Tanpa file, pakai token web player.
- Pencocokan impor tanpa login: hanya YouTube (NetEase dan QQ tidak memberi audio untuk tamu). Impor ulang playlist agar lagu lama NetEase diganti.
- Spotify: setiap panggilan gagal dicatat (kode HTTP + path, tanpa token). Mencoba client credentials lalu token web player. Kalau keduanya gagal, dicatat "falling back to embed page".
- Rebrand tahap 1 (nama tampilan saja): nama mod "Lullaby+" di fabric.mod.json, judul layar utama, teks update, kategori keybind. Namespace `dangomusic`, folder config, dan path aset belum diubah.
- Rebrand tahap 2: id mod `dangomusic` -> `lullaby` (namespace resource, assets/lullaby, mixin lullaby.mixins.json, lullaby-build.properties, config/lullaby). Config lama config/dangomusic disalin otomatis saat pertama jalan (kecuali yt-cache). Nama package Java tetap me.sorihiko.dango.
- Charts & Trending YouTube Music: memakai endpoint internal music.youtube.com (browseId FEmusic_charts). Tidak resmi. Hasil di-cache 1 jam di memori. Home menampilkan kartu "Trending now" (lagunya diacak tiap dibuka), Charts menampilkan "YouTube Music Charts" (urut). Versi klien (REMIX_VERSION) bisa perlu diperbarui kalau YouTube menolak permintaan.
- Mode potato glass (Low-end mode): kaca tidak memakai blur, hanya warna solid rgba(18,18,22,0.72) dengan garis highlight putih 15% di atas sudut melengkung.
- Settings > Player > "Fast glass": saklar global untuk mode potato (blur kaca dimatikan, throttle pengambilan latar, Deep Glow dimatikan). Satu-satunya penulis Performance.lowEnd sekarang NeteaseMusicModule. Low-end mode di modul Lyrics hanya mematikan Deep Glow.
- Settings > Player > "Blur resolution" (1x, 0.5x, 0.25x): memperkecil buffer blur kaca (soft dan frost). Buffer di-resize ulang saat pengaturan berubah.
- Perbaikan build: import java.net.HttpURLConnection dan URL di YoutubeMusicSource.kt sebelumnya dobel (ambiguous). Sudah dihapus.
- Rebrand tampilan: nama "LULLABY+" (sidebar, judul, update, mod list). Warna aksen pink diganti silver (RED = #9AA4B6), hati tetap pink (LOVE). Entri Listen Together dihapus dari sidebar.
- Cache cover: tekstur yang 5 menit tidak dipakai dilepas dari VRAM (gambar tetap di RAM).
- Dynamic Island: pengaturan Position (atas tengah, bawah tengah, sisi kiri, sisi kanan), Offset X, dan Offset Y. Mode sisi (vertikal) menampilkan cover di atas, judul dan artis di bawah, serta progres di bawah. Teks belum diputar 90° (GuiCanvas belum punya rotasi teks), dan Z-index belum ada.
- Dynamic Island mode samping: pil tetap 84x150, cover 48px rata tengah, judul dan artis rata tengah, audio visualizer rata tengah di bawah teks, progres tipis di bawah.
- GUI tahap 1 (sesuai mockup): sidebar berisi logo LULLABY+, Home, Charts, Now Playing, Playlist. Daftar playlist dan cloud drive pindah ke halaman Playlist (grid). Page baru: PLAYLISTS.
- GUI tahap 2-4: Now Playing (antrean kiri, lima tombol bulat di bawah, cover + kotak judul + panel lirik kanan). Home: lagu trending YouTube diacak tiap dibuka, grid 5 kolom. Charts: "Trending Music" dengan garis aksen, grid lagu chart urut. Lirik diambil lewat sumber lagu (LRCLIB/NetEase). File baru: musicui/TrackPages.kt.
- Perbaikan build: MusicScreen meng-import TrackCardGrid dan NowPlayingPage dari paket musicui.
- GUI revisi: search pindah ke header konten (lebar penuh, di atas Home/Charts/Now Playing/Playlist). Now Playing: antrean dan lirik bisa di-scroll dengan roda mouse, volume di bawah lima tombol bulat, konten berhenti di atas dock (ui.bottomInset). Home dan Charts memakai hasil pencarian YouTube (top hits, trending, viral, lagu indonesia terpopuler) kalau chart kosong.
- Dynamic Island mode samping: cover bulat di atas, lalu 9 batang visualizer vertikal (mengikuti mockup). Judul dan progres dihapus dari mode ini.
- Cover Netease diminta ukuran 500x500 (param=500y500) di CoverCache.
- Buffer audio: sudah ~0,25 detik di MusicPlayer, tidak diubah (panduan 16 KB akan memperburuk crackling).
- Audio: clipping dikurangi. Hard clip diganti soft limiter (di atas 85% full scale dibulatkan). Normalisasi tanpa info peak tidak boleh menaikkan gain di atas 1.0.
- Now Playing: slider volume dihapus (tombol 5 tetap). Dynamic Island mode samping: latar hitam solid seperti mockup. Immersive: sampul lagu jadi latar window, diredupkan di bawah kaca.
- Cover: tekstur 256x256 (sebelumnya 128x128 = 144p). Sumber Netease sudah minta 500x500.
- Dynamic Island mode samping: cover bulat di tengah, batang audio vertikal 11 buah yang bergerak.
- Now Playing: tombol bulat dihapus; antrean memakai seluruh tinggi.
- Edge: garis putih hover di tepi window dihapus; dispersion (pinggiran pelangi) dimatikan; highlight kaca diturunkan.
- Logo sidebar: LULLABY+ lebih besar, rata kiri, dengan titik aksen.
- Sidebar: brand hanya "LULLABY+".
- Dynamic Island: posisi (Top/Bottom/Left/Right) diganti klik-ganti, tanpa slider. Mode samping: capsule seperti mode atas diputar, cover bulat di atas, batang audio mengikuti level RMS lagu (MusicPlayer.levelAt).
- Now Playing: kartu dengan kilau tepi, halo aksen di belakang cover, lirik berjalan pelan mengikuti baris aktif dengan glow lembut di baris yang sedang dinyanyikan.
- Cover: bingkai hitam (letterbox) di tepi thumbnail dibuang sebelum dipotong persegi (berlaku juga untuk Dynamic Island).
- Playlist impor: tombol Rename (lewat kotak search; Enter simpan, Esc batal) dan Delete di header playlist.
- Grid Playlist: duplikat ID dibersihkan.
- Dynamic Island: bar sedikit lebih sensitif.
- Romaji: lirik non-Latin (Jepang/Mandarin/Korea/Rusia) diganti romalrc dari NetEase.
- Warna aksen mengikuti warna dominan cover (CoverCache.accent), dipakai untuk RED dan tint window.
- Sudut cover dibulatkan di semua tampilan (CoverCache.track memakai tekstur rounded).
- Now Playing: cover lebih kecil, kartu lebih rapi. Dynamic Island samping: pill hitam solid.
- Dibatalkan: warna aksen dari cover (percobaan 3) dan sudut cover membulat di Home/Charts (percobaan 5). Romaji tetap.
- Dynamic Island samping: kembali abu-abu seperti top/bottom; yang berubah hanya visualizer (batang horizontal mengikuti audio).
- Now Playing: halo dan garis highlight dihapus, cover membulat, kotak judul lebih rapi.
- Halaman Queue (di bawah Charts): daftar antrean dengan tombol ↑ ↓ untuk mengatur urutan, × untuk menghapus. Logika di MusicPlayer.moveQueue / removeQueue.
- Login YouTube Music (cookie): isi config/lullaby/youtube-cookie.txt dengan satu baris cookie dari browser. Playlist milik akun (FEmusic_liked_playlists) muncul di Playlist. Tanpa cookie, sumber YouTube tetap bisa dipakai seperti biasa.
- Home mengikuti login: kalau cookie YouTube Music terpasang, Home menampilkan rekomendasi akun (FEmusic_home) berlabel "For you". Kalau belum, tetap trending.
- Hide playlist: tombol Hide/Unhide di header playlist (semua sumber). Playlist tersembunyi disembunyikan dari grid; "Show hidden" untuk menampilkannya lagi.
- Clear queue: tombol Clear queue di halaman Queue.
- Login lewat aplikasi: tombol Login YouTube / Login Spotify di halaman Playlist. Klik, tempel cookie di kotak search, tekan Enter. Tidak perlu file manual.
- Login Spotify: cookie sp_dc dipakai untuk token Web API atas nama akun (dicoba pertama saat impor).
- Perbaikan build: HiddenPlaylists menulis id lewat JsonPrimitive (Gson versi ini tidak punya add(Long)).
- Perbaikan build: Library.status setter dibuat publik (MusicScreen menulis status login/hapus).
- Dynamic Island (horizontal): jam real-time (HH:mm) di kiri cover. Bisa dimatikan lewat pengaturan "Clock".
- Playlist impor tidak hilang lagi: softRefresh (dipanggil setelah login/refresh) sekarang menggabungkan playlist impor, seperti reloadPlaylists.
- Dynamic Island: posisi hanya di atas (kiri, kanan, bawah dihapus dari pengaturan).
- Belum dikerjakan: island interaktif (Alt untuk membebaskan kursor dan panel expand). Ini perlu hook input dan screen per versi.
- Island interaktif (modern): tahan Alt saat ada lagu untuk membuka panel expand (cover, judul, progres, prev/play/next, volume, lirik, search bar). Lepas Alt untuk menutup. Klik search bar membuka GUI penuh dengan kotak search aktif. Legacy: Alt membuka GUI penuh.
- Animasi island: kaca melebar dari pill ke panel (ease-out), isi memudar masuk setelah kaca hampir terbuka, di-clip ke kaca. Saat Alt dilepas panel menyusut kembali ke pill, lalu layar ditutup.

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

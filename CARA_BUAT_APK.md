# Cara Mendapatkan File APK (tanpa install apa pun di PC)

## Cara A — GitHub (paling mudah, gratis, bisa dari HP/PC)
1. Buat akun di https://github.com lalu buat repository baru (boleh Private).
2. Upload SELURUH isi folder ini (termasuk folder `.github`, `app`, dan file `app/bon-release.jks`).
   Tips: di PC, extract zip lalu drag-and-drop semua isinya ke halaman repository ("uploading an existing file").
   Pastikan folder tersembunyi `.github/workflows/build.yml` ikut terupload.
3. Buka tab **Actions** → pilih **Build APK** → **Run workflow** (atau tunggu otomatis jalan setelah upload).
4. Tunggu ±3–5 menit sampai centang hijau, buka run tersebut, lalu download **BonBengkel-APK** di bagian *Artifacts*.
5. Extract zip-nya → dapat `app-release.apk`. Kirim ke HP (WhatsApp/Drive/kabel) dan install.

## Cara B — Android Studio
1. Install Android Studio → File > Open → pilih folder ini.
2. Tunggu sync selesai → menu Build > Build APK(s) (varian *release*).
3. APK ada di `app/build/outputs/apk/release/app-release.apk`.

## Install di HP
- Izinkan "Install dari sumber tidak dikenal" untuk aplikasi yang membuka APK.
- Jika muncul peringatan Play Protect, pilih "Tetap instal" (aplikasi ini bukan dari Play Store).
- Min. Android 5.0 (API 21); mendukung Android 5 s/d 14+.

## Catatan penting
- Semua fitur aplikasi asli dipertahankan; file `index.html` tidak diubah selain 1 baris `<script src="bridge.js">`.
- Fitur yang di browser butuh Chrome, di sini dijalankan secara native:
  Bluetooth (BLE) printer, Cetak Sistem, Ekspor CSV (tersimpan di folder Download), tombol Kembali.
- Printer Bluetooth harus tipe BLE, sama seperti syarat versi web-nya.
- Keystore `bon-release.jks` disertakan agar update berikutnya bisa menimpa versi lama.
  Jika aplikasi akan disebarkan luas, buat keystore sendiri dan ganti password di `app/build.gradle`.
- Ganti ikon: letakkan ikon di `app/src/main/res/mipmap-*` lalu ubah `android:icon` di AndroidManifest.xml.

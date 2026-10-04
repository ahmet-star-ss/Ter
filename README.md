# Panoptik
Panopticore tarzi HUD kamera / hareket takip uygulamasi (Kotlin, CameraX).

1. Bu klasorun icindekileri GitHub deposunun kok dizinine yukle.
2. Actions sekmesi > "Build APK" calisir (veya Run workflow).
3. Bitince "Panoptik-APK" artifact'ini indir, zip icinden APK'yi cikar.

## Yeni ozellikler
- Sallanmaya dayanikli hareket algilama (kayma telafisi + jiroskop)
- Ayarlar cekmecesi (sag ust ⚙): ESP cizgi (ust/alt), box stili (4), iskelet, renkler
- Insan iskeleti (MediaPipe Pose), kedi/kopek/hayvan tespiti (EfficientDet-Lite0)
- Modeller derleme sirasinda GitHub Actions'ta indirilir (.github/workflows/build.yml)

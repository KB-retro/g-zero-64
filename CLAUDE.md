# G-Zero 64 — notas para Claude

Port Android de G-Diffuser (F-Zero X + Expansion Kit). Repo: https://github.com/KB-retro/g-zero-64,
rama `main`. Original: https://github.com/Zorkats/G-Diffuser (remoto `upstream`); no fusionar cambios
de upstream salvo que se pida.

## Preferencias de KB-retro
- Hablar en español; commits y código en inglés. Changelogs de una línea por cambio.
- Mantener créditos y avisos de licencia del original (README, créditos de la app).
- Nunca subir datos del juego (ROM, .ndd, IPL, .o2r), código generado a partir de ellos ni la clave de firma.
- APK a mano con GitHub Actions (workflow `Android APK`), firmada con la misma clave
  (secretos `GZ64_KEYSTORE_B64`, `GZ64_KEYSTORE_PASSWORD`, alias `gzero64`), versión 0.1.<run>.
- Lanzador: Jugar / Ajustes / Cambiar carpeta; no arrancar solo al elegir carpeta; inicio
  automático opcional y cancelable tocando o pulsando el mando.
- Menú rápido solo con Atrás (no Select+Start): opciones de imagen y sonido; Continuar (verde,
  izquierda) y Salir (rojo, derecha). Sustituye al menú de G-Diffuser, que en Android no se abre.
- Pensado para consolas Android (Retroid / Odin 2 Portal, RG477M con Mali).

## Android
- `android/app`: Java (paquete `io.github.kbretro.gzero64`), Gradle en la raíz (`settings.gradle.kts`).
- `android/SDL`: submódulo SDL2 release-2.32.10 (C y Java deben coincidir).
- El juego es `libmain.so`; el extractor Torch va como `libgdx_extract.so` y se ejecuta como proceso
  hijo (por eso `useLegacyPackaging`). Rutas: `GDX_APP_DIR` (carpeta externa de la app) y
  `GDX_EXTRACT_BIN`, exportadas por `GameActivity` y leídas en `port/gdx_self_exe.h`.
- Bionic no tiene ucontext: el planificador usa `port/gdx_fiber_asm.c` (aarch64/x86-64). Se prueba en
  Linux con `-DGDX_FIBER_ASM=ON`.
- Menú rápido: `QuickMenuDialog.java` lee y cambia CVars a través de `port/gdx_android_bridge.cpp`
  (cola aplicada por el hilo del juego en cada fotograma). Pausa el juego mientras está abierto.
- Parches de submódulos en `android/patches/<submódulo>/`, aplicados al configurar en Android:
  GLES highp, Select solo cierra el menú, FXAA/FSR 1.0 (`gGZ64.Fxaa`, `gGZ64.Fsr`, `gGZ64.FsrSharpness`).
- Textos del menú de G-Diffuser y de la configuración inicial: `port/gdx_i18n*` (español por defecto).
- Ramas `ci/**` compilan en Actions solo para comprobar; los errores salen como anotaciones.
